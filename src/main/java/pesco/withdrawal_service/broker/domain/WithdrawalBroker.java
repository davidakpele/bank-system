package pesco.withdrawal_service.broker.domain;

import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import jakarta.servlet.http.HttpServletRequest;
import pesco.withdrawal_service.broker.domain.logs.TransactionLogger;
import pesco.withdrawal_service.clients.HistoryServiceClient;
import pesco.withdrawal_service.clients.NotificationServiceClient;
import pesco.withdrawal_service.clients.RevenueServiceClient;
import pesco.withdrawal_service.clients.UserServiceClient;
import pesco.withdrawal_service.clients.WalletServiceClient;
import pesco.withdrawal_service.dto.HistoryDTO;
import pesco.withdrawal_service.dto.UserDTO;
import pesco.withdrawal_service.dto.WalletDTO;
import pesco.withdrawal_service.dto.WalletDTO.BalanceDTO;
import pesco.withdrawal_service.dto.WithdrawHistoryRequestDTO;
import pesco.withdrawal_service.enums.BanActions;
import pesco.withdrawal_service.enums.CurrencyType;
import pesco.withdrawal_service.enums.TransactionType;
import pesco.withdrawal_service.middleware.UserTransactionsAgent;
import pesco.withdrawal_service.payloads.WithdrawInRequest;
import pesco.withdrawal_service.utils.JwtTokenProvider;
import pesco.withdrawal_service.utils.TransferFeeCalculator;

import org.springframework.web.socket.*;

@Component
public class WithdrawalBroker extends AbstractWebSocketHandler {
    // Define a constant for platform's revenue share
    private static final BigDecimal PLATFORM_REVENUE_SHARE = new BigDecimal("0.80"); // 80%
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

    @Autowired
    private HttpServletRequest httpRequest;

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
            
            if (type.isEmpty() || type ==null) {
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
                String walletId = (String) payload.get("walletId");
                String recipientUser = (String) payload.get("recipientUser");
                String amountStr = (String) payload.get("amount");
                String region = (String) payload.get("region");
                String currencyTypeStr = (String) payload.get("currencyType");
                String transferpin = (String) payload.get("transferpin");

                BigDecimal amount = new BigDecimal(amountStr);
                CurrencyType currencyType = CurrencyType.valueOf(currencyTypeStr.toUpperCase());
                // extracting data from payload

                UserDTO fromUser = userServiceClient.getUserByUsername(username);

                UserDTO toUser = userServiceClient.getUserByUsername(recipientUser);

                if (fromUser == null) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Sender user does not exist, please provide a valid username.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

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

                if (toUser == null) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Sender user does not exist, please provide a valid username.");
                    sendMessage(session, responseMessageObject);
                    return;
                }

                // Check suspicious behavior
                if (fromUser != null && userTransactionsAgent.isHighVolumeOrFrequentTransactions(fromUser.getId(), token)) {
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

                if (fromUser != null && userTransactionsAgent.isInconsistentBehavior(fromUser.getId(), fromUser.getId(), token)) {
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

                else if (senderWalletAccount != null&& !passwordEncoder.matches(providedPin, senderWalletAccount.getPassword())) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message",
                            "Invalid transfer pin.\nThe provided transfer pin is incorrect.");
                    sendMessage(session, responseMessageObject);
                    return;
                }
                // Calculate the fee amount
                BigDecimal feeAmount = feeCalculator.calculateFee(amount);
    
                // Calculate the platform's revenue (80% of the fee)
                BigDecimal revenue = feeAmount.multiply(PLATFORM_REVENUE_SHARE).setScale(2,
                        RoundingMode.HALF_UP);

                // Calculate total deduction from sender (Amount + Fee Amount)
                BigDecimal finalDeduction = amount.add(feeAmount);

                // Print values for understanding
                System.out.println("Amount: " + amount);
                System.out.println("Fee Amount: " + feeAmount);
                System.out.println("Revenue (Platform's Share): " + revenue);
                System.out.println("Total Deduction from Sender: " + finalDeduction);

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

                //Handle recipient wallet creation if it doesn't exist
                if (recipientWalletAccount == null && toUser != null) {
                    // Just in case recipient user wallet is not found or null, the create and credit the wallet.
                    CompletableFuture<Void> createNewWallet = CompletableFuture
                            .runAsync(() -> walletServiceClient.createUserWallet(toUser.getId()));
                    createNewWallet.join();
                }
                if (toUser != null && fromUser != null && senderWalletAccount != null) {
                    /**
                     * @Run Multiple Tasks in Parallel
                     * @Run deduction and update wallet concurrently and then wait for all to complete
                     * 
                     *      => Action 1 ( Deduct amount from sender wallet)
                     * 
                     *      => Action 2 (Credit recipient user wallet)
                     */
                    // Action 1
                    String newAmount = amount.toString();
                    CompletableFuture<Void> deductFromSenderWallet = walletServiceClient.DeductAmountFromSenderWallet(
                        fromUser.getId(),
                        fromUser.getUsername(),
                        toUser.getUsername(),
                        newAmount,
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
                        }
                    );
                    // Action 2
                    CompletableFuture<Void> updateCreatedWallet = walletServiceClient.updateUserWallet(
                        toUser.getId(), amount, currencyType, fromUser.getId(), token)
                        .thenAccept(result -> {
                            try {
                                synchronized (session) {
                                    session.sendMessage(new TextMessage(result));
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    );

                    // Log 

                    CompletableFuture<Void> logTransaction = CompletableFuture
                            .runAsync(() -> {
                                try {
                                    synchronized (session) {
                                        transactionLogger.logTransaction(payload);
                                    }
                                } catch (JsonProcessingException e) {
                                    e.printStackTrace();
                                }
                            });

                    //Wait for all threads to complete
                    CompletableFuture.allOf(deductFromSenderWallet, updateCreatedWallet).join();

                    // Create history for both users.
                    WithdrawHistoryRequestDTO senderHistory = new WithdrawHistoryRequestDTO();
                    senderHistory.setAmount(amount.negate());
                    senderHistory.setCurrencyType(currencyType);
                    senderHistory.setDescription("Transfer " + senderWalletAccount.getBalances().get(0).getCurrencySymbol().toString()+ amount + " to " + toUser.getUsername());
                    senderHistory.setType(TransactionType.DEBITED);
                    senderHistory.setRecipientUsername(toUser.getUsername());
                    senderHistory.setUserId(fromUser.getId());
                    senderHistory.setSenderUsername(fromUser.getUsername());
                    senderHistory.setWalletId(senderWalletAccount.getId());

                    CompletableFuture<Void>CreateSenderHistory = CompletableFuture.runAsync(() -> historyServiceClient.createUserCreditHistory(senderHistory, token));
                    CreateSenderHistory.join();

                    WithdrawHistoryRequestDTO recipientHistory = new WithdrawHistoryRequestDTO();
                    recipientHistory.setAmount(amount);
                    recipientHistory.setCurrencyType(currencyType);
                    recipientHistory.setDescription("Credited " + senderWalletAccount.getBalances().get(0).getCurrencySymbol().toString()+ amount + " by " + fromUser.getUsername());
                    recipientHistory.setType(TransactionType.CREDITED);
                    recipientHistory.setRecipientUsername(toUser.getUsername());
                    recipientHistory.setUserId(toUser.getId());
                    recipientHistory.setSenderUsername(fromUser.getUsername());
                    recipientHistory.setWalletId(recipientWalletAccount.getId());

                    CompletableFuture<Void> CreateRecipientHistory = CompletableFuture
                            .runAsync(() -> historyServiceClient.createUserCreditHistory(recipientHistory, token));
                    CreateRecipientHistory.join();

                    CompletableFuture<Void> addToPlatformRevenue =revenueServiceClient.addToPlatformRevenue(feeAmount, currencyType, token)
                        .thenAccept(result -> {
                            try {
                                synchronized (session) {
                                    session.sendMessage(new TextMessage(result));
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    );

                    CompletableFuture.allOf(addToPlatformRevenue).join();
                    

                    // BigDecimal senderNewWalletBalance = walletServiceClient.FetchUserBalance(fromUser.getId(),
                    //         currencyType.toString(), token);
                    // CompletableFuture<Void> sendDebitAlert = CompletableFuture
                    //         .runAsync(() -> notificationServiceClient.sendDebitAlert(fromUser.getEmail(),
                    //                 fromUser.getUsername(), toUser.getUsername(), amount, currencyType.toString(),
                    //                 feeAmount, senderNewWalletBalance));
                    // sendDebitAlert.join();

                    // BigDecimal recieverNewWalletBalance = walletServiceClient.FetchUserBalance(toUser.getId(),
                    //         currencyType.toString(), token);
                    // CompletableFuture<Void> sendCreditAlert = CompletableFuture.runAsync(
                    //         () -> notificationServiceClient.sendCreditAlert(fromUser.getUsername(), toUser.getEmail(),
                    //                 toUser.getUsername(), amount, currencyType.toString(), recieverNewWalletBalance));
                    // sendCreditAlert.join();

                    responseMessageObject.put("status", "success");
                    responseMessageObject.put("type", "message");
                    responseMessageObject.put("message", "Transaction successful\n\n" + amount+ " has been successfully sent to " + recipientUser + ".");
                    sendMessage(session, responseMessageObject);
                   
                   
 
                }

                // else if (type.equals("transfer")) {
                //     String username = (String) payload.get("username");
                //     String walletId = (String) payload.get("walletId");
                //     if (username.isEmpty() || username == null) {
                //         responseMessageObject.put("status", "error");
                //         responseMessageObject.put("message",
                //                 "Missing `Username` param..! Your Username parameter must be provided.");
                //         sendMessage(session, responseMessageObject);
                //     }
                //     // extracting data from payload
                //     ObjectMapper objectMapper = new ObjectMapper();
                //     WithdrawInRequest request = objectMapper.readValue(message.getPayload(), WithdrawInRequest.class);

                //     UserDTO fromUser = userServiceClient.getUserByUsername(username);
                //     if (fromUser == null) {
                //         responseMessageObject.put("status", "error");
                //         responseMessageObject.put("message",
                //                 "Sorry this user does not exist in our system.\nSender User does not exist, please provide valid username.");
                //         sendMessage(session, responseMessageObject);
                //     }

                //     if (fromUser != null && !fromUser.getUsername().equals(request.getSenderUser())) {
                //         responseMessageObject.put("status", "error");
                //         responseMessageObject.put("message",
                //                 "Fraudulent action is taken here, You are not the authorized user to operate this wallet.\nOne more attempt from you again, you will be reported to the Economic and Financial Crimes Commission (EFCC).");
                //         sendMessage(session, responseMessageObject);
                //     }

                //     boolean isLockedAccount = fromUser != null ? fromUser.getRecords().get(0).isLocked() : null;

                //     if (isLockedAccount) {
                //         responseMessageObject.put("status", "error");
                //         responseMessageObject.put("message",
                //                 "Your account has been temporarily locked. Please reach out to our support team to unlock your account.\n");
                //         sendMessage(session, responseMessageObject);
                //     }

                //     boolean isAccountBlocked = fromUser != null ? fromUser.getRecords().get(0).isBlocked() : null;

                //     if (isAccountBlocked) {
                //         responseMessageObject.put("status", "error");
                //         responseMessageObject.put("message",
                //                 "Your account has been blocked due to security concerns. Contact our customer service for assistance with your blocked account.\n");
                //         sendMessage(session, responseMessageObject);
                //     }
                //     if (fromUser != null) {
                //         WalletDTO senderWalletAccount = walletServiceClient.findByUserId(fromUser.getId(), walletId,
                //                 token);
                //         if (senderWalletAccount == null) {
                //             responseMessageObject.put("status", "error");
                //             responseMessageObject.put("message", "Sorry.! Your wallet is not found.");
                //             sendMessage(session, responseMessageObject);
                //         }

                //         // Check for suspicious behaviors
                //         if (userTransactionsAgent.isHighVolumeOrFrequentTransactions(fromUser.getId(), token)) {
                //             responseMessageObject.put("status", "error");
                //             responseMessageObject.put("message",
                //                     "Account temporarily banned due to high volume of transactions.\nPlease contact support.");
                //             sendMessage(session, responseMessageObject);
                //         }

                //         if (userTransactionsAgent.isNewAccountAndHighRisk(fromUser.getId(), token)) {
                //             responseMessageObject.put("status", "error");
                //             responseMessageObject.put("message",
                //                     "Account temporarily banned due to unverified or newly created wallet.\nPlease contact support");
                //             sendMessage(session, responseMessageObject);
                //         }

                //         if (userTransactionsAgent.isFraudulentBehavior(fromUser.getId(), token)) {
                //             responseMessageObject.put("status", "error");
                //             responseMessageObject.put("message",
                //                     "Fraudulent Activity Detected\nYour account has been flagged for suspicious activity. Please contact support immediately.");
                //             sendMessage(session, responseMessageObject);
                //         }

                //         if (senderWalletAccount != null
                //                 && userTransactionsAgent.isFromBlacklistedAddress(senderWalletAccount.getWalletId(),
                //                         token)) {
                //             responseMessageObject.put("status", "error");
                //             responseMessageObject.put("message",
                //                     "Transaction blocked due to blacklisted wallet address.\nPlease contact support.");
                //             sendMessage(session, responseMessageObject);
                //         }

                //         // Verify user transfer pin password
                //         String providedPin = request.getTransferpin().trim();

                //         if (providedPin == null || providedPin.isEmpty()) {
                //             responseMessageObject.put("status", "error");
                //             responseMessageObject.put("message",
                //                     "Transfer pin is required.\nPlease provide your transfer pin.");
                //             sendMessage(session, responseMessageObject);
                //         }

                //         if (senderWalletAccount != null
                //                 && !passwordEncoder.matches(providedPin, senderWalletAccount.getPassword())) {
                //             responseMessageObject.put("message",
                //                     "Invalid transfer pin.\nPlease provide your transfer pin.");
                //             sendMessage(session, responseMessageObject);
                //         }

                //         List<HistoryDTO> activities = senderWalletAccount != null
                //                 ? historyServiceClient.FindByTimestampAfterAndWalletId(
                //                         Instant.now().minus(1, ChronoUnit.MINUTES), senderWalletAccount.getWalletId(),
                //                         token)
                //                 : null;
                //         if (activities != null) {
                //             // detect suspicious patterns
                //             for (HistoryDTO activity : activities) {
                //                 if (activity.getType() == TransactionType.DEPOSIT
                //                         && activity.getTimestamp()
                //                                 .after(Timestamp.from((Instant.now().minus(1, ChronoUnit.MINUTES))))) {
                //                     // update user account status
                //                     userServiceClient.updateUserAccountStatus(fromUser.getId(),
                //                             BanActions.SUSPICIOUS_ACTIVITY,
                //                             token);
                //                 }
                //             }
                //             // get platform fee amount
                //             BigDecimal feePercentage = feeCalculator.calculateFee(request.getAmount());
                //             // Calculate the fee based on the transfer amount
                //             BigDecimal feeAmount = request.getAmount().multiply(feePercentage);
                //             // Calculate the total deduction (amount + fee)
                //             BigDecimal finalDeduction = feeAmount.add(request.getAmount());
                //             try {
                //                 BigDecimal balance = walletServiceClient.FetchUserBalance(fromUser.getId(),
                //                         request.getCurrencyType().toString(), token);
                //                 if (((BigDecimal) balance).compareTo(finalDeduction) < 0) {
                //                     responseMessageObject.put("status", "error");
                //                     responseMessageObject.put("message",
                //                             "Insufficient balance.\nYour account balance is low.");
                //                     sendMessage(session, responseMessageObject);
                //                 }

                //                 // ELSE:

                //                 /**
                //                  * a) Send request to api gateway either paystack, flutterwave depending the
                //                  * user choosen.
                //                  * b) Send PUT request to wallet-service to substract / deduct the amount from
                //                  * user wallet.
                //                  * c) Send POST request Record user history .
                //                  * d) Send notification to user email about the action just process "DEBIT
                //                  * ALERT"
                //                  * e) Return successful.
                //                  *
                //                  * NOTE: Use CompletableFuture.runAsync(()) in a, b, c
                //                  */

                //                 responseMessageObject.put("status", "success");
                //                 responseMessageObject.put("message",
                //                         "Withdraw Successful.!\n\nThe " + request.getCurrencyType() + " "
                //                                 + request.getAmount()
                //                                 + " Withdraw was successful.");
                //                 sendMessage(session, responseMessageObject);
                //             } catch (Exception e) {
                //                 responseMessageObject.put("status", "error");
                //                 responseMessageObject.put("message", "Transaction Fail.!\n\n" + e.getMessage());
                //                 sendMessage(session, responseMessageObject);
                //             }
                //         }
                //     }
                // }
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

            // Small delay to ensure message delivery before closing
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

    private void sendMessage(WebSocketSession session, Object message) throws IOException {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to send message", e);
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

}
