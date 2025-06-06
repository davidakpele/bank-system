package pesco.withdrawal_service.broker.domain;

import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import pesco.withdrawal_service.broker.domain.logs.TransactionLogger;
import pesco.withdrawal_service.clients.HistoryServiceClient;
import pesco.withdrawal_service.clients.NotificationServiceClient;
import pesco.withdrawal_service.clients.RevenueServiceClient;
import pesco.withdrawal_service.clients.UserServiceClient;
import pesco.withdrawal_service.clients.WalletServiceClient;
import pesco.withdrawal_service.dto.HistoryDTO;
import pesco.withdrawal_service.dto.UserDTO;
import pesco.withdrawal_service.dto.WalletDTO;
import pesco.withdrawal_service.dto.WithdrawHistoryRequestDTO;
import pesco.withdrawal_service.enums.BanActions;
import pesco.withdrawal_service.enums.CurrencyType;
import pesco.withdrawal_service.enums.Symbols;
import pesco.withdrawal_service.enums.TransactionType;
import pesco.withdrawal_service.exceptions.UserClientNotFoundException;
import pesco.withdrawal_service.middleware.UserTransactionsAgent;
import pesco.withdrawal_service.utils.JwtTokenProvider;
import pesco.withdrawal_service.utils.TransferFeeCalculator;
import org.springframework.web.socket.*;

@Component
public class WithdrawalBroker extends AbstractWebSocketHandler {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    private  WalletServiceClient walletServiceClient;

    @Autowired
    private  UserServiceClient userServiceClient;

    @Autowired
    private  NotificationServiceClient notificationServiceClient;

    @Autowired
    private  RevenueServiceClient revenueServiceClient;

    @Autowired
    private TransferFeeCalculator feeCalculator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private  UserTransactionsAgent userTransactionsAgent;
    
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private HistoryServiceClient historyServiceClient;

    @SuppressWarnings("unused")
    @Autowired
    private TransactionLogger transactionLogger;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
         URI uri = session.getUri();
         if (uri == null) {
             session.close();
             return;
         }
         Map<String, String> queryParams = extractQueryParams(uri.getQuery());

         String userId_In_string = queryParams.get("userId");
         long userId = Long.parseLong(userId_In_string);

         String token = extractTokenFromQuery(uri.getQuery());

         handleUserConnection(session, userId, token);
     }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    private void handleUserConnection(WebSocketSession session, Long userId, String token) throws IOException {
        // JWT validation
        if (token == null || token.isEmpty()) {
            sendErrorAndClose(session, "JWT token is missing", "Authentication required");
            return;
        }

        if (!jwtTokenProvider.validateToken(token)) {
            sendErrorAndClose(session, "Invalid token", "Invalid or expired token");
            return;
        }

        String extractedUserId = jwtTokenProvider.getUserIdFromJWT(token);

        if (!String.valueOf(userId).equals(String.valueOf(extractedUserId))) {
            sendErrorAndClose(session, "Unauthorized", "User ID mismatch");
            return;
        }

        Map<String, Object> responsePayload = new LinkedHashMap<>();
        responsePayload.put("status", "success");
        responsePayload.put("message", "Withdrawal Connection established and user session initialized");
        
        // Store in memory
        sessions.put(session.getId(), session);
        sendMessage(session, responsePayload);
    }

    @SuppressWarnings("null")
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map<String, Object> payload = objectMapper.readValue(message.getPayload(),
                    new TypeReference<Map<String, Object>>() {
                    });
            // response message handler
            Map<String, Object> responseMessageObject = new LinkedHashMap<>();
            // type param
            String type = (String) payload.get("type");
            // extract token from header
            String token = (String) payload.get("token");

            // JWT validation                 
            if (token == null || token.isEmpty()) {
                sendErrorAndClose(session, "JWT token is missing", "Authentication required");
                return;
            }

            if (!jwtTokenProvider.validateToken(token)) {
                sendErrorAndClose(session, "Invalid token", "Invalid or expired token");
                return;
            }

            if (type.isEmpty() || type == null) {
                responseMessageObject.put("status", "error");
                responseMessageObject.put("type", "message");
                responseMessageObject.put("message", "Missing `Type` param..! Type parameter must be provided.");
                sendMessage(session, responseMessageObject);
                return;
            }

            if (type.equals("withdraw")) {
                String username = (String) payload.get("username");
                if (username.isEmpty() || username == null) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Missing `Username` param..! Your Username parameter must be provided.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                String senderUser = (String) payload.get("senderUser");
                String walletIdString = (String) payload.get("walletId");
                String recipientUser = (String) payload.get("recipientUser");
                String amountStr = (String) payload.get("amount");
                String region = (String) payload.get("region");
                String currencyTypeStr = (String) payload.get("currencyType");
                String transferpin = (String) payload.get("transferpin");

                BigDecimal amount = new BigDecimal(amountStr);
                CurrencyType currencyType = CurrencyType.valueOf(currencyTypeStr.toUpperCase());
                // extracting data from payload
                if (walletIdString.isEmpty() || walletIdString.isBlank()) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Missing `Wallet ID` param..! Your Wallet ID parameter must be provided.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                UserDTO fromUser = safeFindUser(username, token, "Sender", session);
                if (fromUser == null)
                    return;

                UserDTO toUser = safeFindUser(recipientUser, token, "Recipient", session);
                if (toUser == null)
                    return;

                if (fromUser != null && !fromUser.getUsername().equals(senderUser)) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Fraudulent action detected. You are not authorized to operate this wallet. \n One more attempt and you will be reported to the Economic and Financial Crimes Commission (EFCC).");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                boolean isLockedAccount = fromUser != null ? fromUser.getRecords().get(0).isLocked() : null;
                boolean isBlockedAccount = fromUser != null ? fromUser.getRecords().get(0).isBlocked() : null;

                if (isLockedAccount) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Your account has been temporarily locked.\n Please contact support to unlock your account.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                if (isBlockedAccount) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message", "The recipient username does not exist in our system.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                // Check suspicious behavior
                if (fromUser != null
                        && userTransactionsAgent.isHighVolumeOrFrequentTransactions(fromUser.getId(), token)) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Account temporarily banned due to suspicious activity.\nAccount temporarily banned due to suspicious activity. Please contact support.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                if (userTransactionsAgent.isHighRiskRegion(region)) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Access Restricted.\nYour account has been temporarily restricted due to activity from a high-risk region.\nPlease contact support for assistance.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                if (fromUser != null && userTransactionsAgent.isNewAccountAndHighRisk(fromUser.getId(), token)) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "New Account Restrictions\n\nNew accounts have transaction limits for security reasons.\n Please verify your identity to continue.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                if (fromUser != null
                        && userTransactionsAgent.isInconsistentBehavior(fromUser.getId(), fromUser.getId(), token)) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Suspicious Activity Detected\n\nUnusual transaction activity has been detected on your account.\n Please contact support.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                if (fromUser != null && userTransactionsAgent.isFraudulentBehavior(fromUser.getId(), token)) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Fraudulent Activity Detected\n\nYour account has been flagged for suspicious activity.\n Please contact support immediately.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                List<HistoryDTO> activities = fromUser != null ? historyServiceClient.FindByTimestampAfterAndWalletId(
                        Instant.now().minus(1, ChronoUnit.MINUTES), fromUser.getId(), token) : null;
                // detect suspicious patterns
                if (activities != null) {
                    for (HistoryDTO activity : activities) {
                        if (activity.getType() == TransactionType.DEPOSIT
                                && activity.getTimestamp()
                                        .after(Timestamp.from((Instant.now().minus(1, ChronoUnit.MINUTES))))) {
                            // update user account status
                            if (fromUser != null)
                                userServiceClient.updateUserAccountStatus(fromUser.getId(),
                                        BanActions.SUSPICIOUS_ACTIVITY,
                                        token);
                        }
                    }
                }

                // Verify transfer pin
                String providedPin = transferpin.trim();
                if (providedPin == null || providedPin.isEmpty()) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Transfer pin is required.\nPlease provide your transfer pin.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                WalletDTO senderWalletAccount = walletServiceClient.findByUserId(fromUser.getId(), token);

                if (senderWalletAccount == null) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message", "Sorry.! Your wallet is not found.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                else if (senderWalletAccount != null
                        && !passwordEncoder.matches(providedPin, senderWalletAccount.getPassword())) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Invalid transfer pin.\nThe provided transfer pin is incorrect.");
                    sendMessage(session, responseMessageObject);
                    return;
                }
                // Calculate the fee amount
                BigDecimal feeAmount = feeCalculator.calculateFee(amount);

                // Calculate total deduction from sender (Amount + Fee Amount)
                BigDecimal finalDeduction = amount.add(feeAmount);
                BigDecimal senderWalletBalance = fromUser != null
                        ? walletServiceClient.FetchUserBalance(fromUser.getId(), currencyType.toString(),
                                token)
                        : null;
                if (senderWalletBalance != null && senderWalletBalance.compareTo(finalDeduction) < 0) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message", "Insufficient balance\nYour account balance is low.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                WalletDTO recipientWalletAccount = fromUser != null && toUser != null
                        ? walletServiceClient.findByUserId(toUser.getId(), token)
                        : null;

                // Handle recipient wallet creation if it doesn't exist
                if (recipientWalletAccount == null && toUser != null) {
                    CompletableFuture<Void> createNewWallet = CompletableFuture
                            .runAsync(() -> walletServiceClient.createUserWallet(toUser.getId()));
                    createNewWallet.join();
                }

                /**
                 * @Run Multiple Tasks in Parallel
                 * @Run deduction and update wallet concurrently and then wait for all to complete
                 * 
                 *      => Action 1 ( Deduct amount from sender wallet)
                 * 
                 *      => Action 2 (Credit recipient user wallet)
                 */

                // Action 1

                CompletableFuture<Void> deductFromSenderWallet = walletServiceClient.DeductAmountFromSenderWallet(
                        fromUser.getId(),
                        fromUser.getUsername(),
                        toUser.getUsername(),
                        amount.toString(),
                        finalDeduction.toString(),
                        currencyType,
                        senderWalletAccount.getId(), token)
                        .thenAccept(result -> {
                            try {
                                synchronized (session) {
                                    session.sendMessage(new TextMessage(result));
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });

                // Log 
                // CompletableFuture<Void> logTransaction = CompletableFuture
                // .runAsync(() -> {
                //     try {
                //         synchronized (session) {
                //             transactionLogger.logTransaction(payload);
                //         }
                //     } catch (JsonProcessingException e) {
                //         e.printStackTrace();
                //     }
                // });

                
                Symbols currencySymbols = Symbols.valueOf(currencyType.toString());
                String symbol = currencySymbols.getSymbol();
                
                //Wait for all threads to complete
                CompletableFuture.allOf(deductFromSenderWallet).join();
                String f_amount = formatBigDecimal(amount);
                // Create history for both users.
                WithdrawHistoryRequestDTO senderHistory = new WithdrawHistoryRequestDTO();
                senderHistory.setAmount(amount.negate());
                senderHistory.setCurrencyType(currencyType);
                senderHistory.setDescription("Transfered " + symbol + f_amount + " to " + toUser.getUsername());
                senderHistory.setType(TransactionType.DEBITED);
                senderHistory.setRecipientUsername(toUser.getUsername());
                senderHistory.setUserId(fromUser.getId());
                senderHistory.setSenderUsername(fromUser.getUsername());
                senderHistory.setWalletId(senderWalletAccount.getId());

                CompletableFuture<Void> CreateSenderHistory = CompletableFuture
                        .runAsync(() -> historyServiceClient.createUserCreditHistory(senderHistory, token));
                CreateSenderHistory.join();

                WithdrawHistoryRequestDTO recipientHistory = new WithdrawHistoryRequestDTO();
                recipientHistory.setAmount(amount);
                recipientHistory.setCurrencyType(currencyType);
                recipientHistory.setDescription("Credited " + symbol+ f_amount+ " by " + fromUser.getUsername());
                recipientHistory.setType(TransactionType.CREDITED);
                recipientHistory.setRecipientUsername(toUser.getUsername());
                recipientHistory.setUserId(toUser.getId());
                recipientHistory.setSenderUsername(fromUser.getUsername());
                recipientHistory.setWalletId(recipientWalletAccount.getId());

                CompletableFuture<Void> CreateRecipientHistory = CompletableFuture
                        .runAsync(() -> historyServiceClient.createUserCreditHistory(recipientHistory, token));
                CreateRecipientHistory.join();

                String revenueAmount = feeAmount.toString();
                CompletableFuture<Void> addToPlatformRevenue = revenueServiceClient
                        .addToPlatformRevenue(fromUser.getId(), revenueAmount, currencyType, token)
                        .thenAccept(result -> {
                            try {
                                synchronized (session) {
                                    session.sendMessage(new TextMessage(result));
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });

                CompletableFuture.allOf(addToPlatformRevenue).join();

                CompletableFuture<BigDecimal> senderNewWalletBalance = walletServiceClient.fetchUserBalance(
                        fromUser.getId(), username, currencyType.toString(), token);

                CompletableFuture<Void> sendDebitAlert = senderNewWalletBalance.thenAccept(latestBalance -> {
                    notificationServiceClient.sendDebitAlert(
                            fromUser.getEmail(),
                            fromUser.getUsername(),
                            toUser.getRecords().get(0).getFirstName()+" "+toUser.getRecords().get(0).getLastName(),
                            amount,
                            currencyType.toString(),
                            feeAmount,
                            latestBalance);
                });

                CompletableFuture<BigDecimal> recieverNewWalletBalance = walletServiceClient.fetchUserBalance(
                        toUser.getId(), toUser.getUsername(), currencyType.toString(), token);

                CompletableFuture<Void> sendCreditAlert = recieverNewWalletBalance.thenAccept(latestBalance -> {
                    notificationServiceClient.sendCreditAlert(
                            fromUser.getUsername(),
                            toUser.getEmail(),
                            toUser.getUsername(),
                            amount,
                            currencyType.toString(),
                            latestBalance);
                });

                CompletableFuture.allOf(sendDebitAlert, sendCreditAlert).join();

                responseMessageObject.put("status", "success");
                responseMessageObject.put("type", "withdraw-message");
                responseMessageObject.put("message", "Transaction successful " + symbol+ f_amount+ " has been successfully sent to " + recipientUser + ".");
                sendMessage(session, responseMessageObject);
                return;
            }
            
            if (type.equals("balance_view")) {
                String currencyTypeStr = (String) payload.get("currencyType");
                String username = (String) payload.get("username");
                CurrencyType currencyType = CurrencyType.valueOf(currencyTypeStr.toUpperCase());
                Long userId = 1001L;

                walletServiceClient.fetchUserBalance(userId, username, currencyType.toString(), token)
                        .thenAccept(balance -> {
                            System.out.println(balance);
                            Map<String, Object> response = new HashMap<>();
                            response.put("status", "success");
                            response.put("type", "message");
                            response.put("message", balance.toPlainString()); 
                            try {
                                sendMessage(session, response);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        })
                        .exceptionally(ex -> {
                            Map<String, Object> error = new HashMap<>();
                            error.put("status", "error");
                            error.put("type", "message");
                            error.put("message", "Failed to fetch balance: " + ex.getMessage());
                            try {
                                sendMessage(session, error);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            return null;
                        });

                return;
            }                     
            
        } catch (Exception e) {
            sendErrorAndClose(session, "Error", "Invalid message format");
        }
    }
    

    private void sendErrorAndClose(WebSocketSession session, String message, String details) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", message);
            error.put("details", details);

            String errorJson = new ObjectMapper().writeValueAsString(error);
            session.sendMessage(new TextMessage(errorJson));

            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }

            if (session.isOpen()) {
                session.close(CloseStatus.NOT_ACCEPTABLE.withReason(message));
            }
        } catch (Exception e) {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.SERVER_ERROR.withReason("Internal server error"));
                }
            } catch (IOException ignored) {
            }
        }
    }

    // Utility methods
    private Map<String, String> extractQueryParams(String query) {
        Map<String, String> queryParams = new HashMap<>();
        if (query == null || query.isEmpty())
            return queryParams;

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            if (keyValue.length == 2) {
                queryParams.put(keyValue[0], keyValue[1]);
            }
        }
        return queryParams;
    }

    private String extractTokenFromQuery(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }

        String[] params = query.split("&");
        for (String param : params) {
            if (param.startsWith("token=")) {
                return param.substring(6);
            }
        }
        return null;
    }

    private UserDTO safeFindUser(String username, String token, String role, WebSocketSession session) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            return userServiceClient.findByUsername(username, token);
        } catch (UserClientNotFoundException ex) {
            response.put("status", "error");
            response.put("type", "message");
            response.put("message", role + " user not found: " + ex.getDetails());
            sendMessage(session, response);
            return null;
        } catch (Exception e) {
            response.put("status", "error");
            response.put("type", "message");
            response.put("message", "Unexpected error while fetching " + role + " user.");
            sendMessage(session, response);
            return null;
        }
    }


    private void sendMessage(WebSocketSession session, Object message) throws IOException {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to send message", e);
        }
    }

     public String formatBigDecimal(BigDecimal amount) {
        String pattern = "#,##0.00";
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        DecimalFormat decimalFormat = new DecimalFormat(pattern, symbols);
        return decimalFormat.format(amount);
    }
}
