package pesco.wallet_service.broker;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import pesco.wallet_service.bootstrap.DataSection;
import pesco.wallet_service.bootstrap.HistorySection;
import pesco.wallet_service.bootstrap.UserSessionData;
import pesco.wallet_service.clients.HistoryClient;
import pesco.wallet_service.clients.UserServiceClient;
import pesco.wallet_service.dtos.UserDTO;
import pesco.wallet_service.dtos.WalletBalanceDTO;
import pesco.wallet_service.dtos.WalletSection;
import pesco.wallet_service.encryptions.battery.SEC46;
import pesco.wallet_service.enums.CurrencyType;
import pesco.wallet_service.models.CurrencyBalance;
import pesco.wallet_service.models.Wallet;
import pesco.wallet_service.respositories.WalletRepository;
import pesco.wallet_service.serviceImplementation.WalletServiceImplementations;
import pesco.wallet_service.utils.JwtTokenProvider;
import org.springframework.web.socket.*;

@Component
public class WalletServiceSocketBroker extends AbstractWebSocketHandler {
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserServiceClient userServiceClient;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private HistoryClient historyClient;

    @Autowired
    private SEC46 sec46;

    @Autowired
    private WalletServiceImplementations walletServiceImplementations;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    // private final Map<String, WebSocketSession> adminSessions = new ConcurrentHashMap<>();
    // private final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    
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

        String redisKey = "user_session:" + userId;

        Map<String, Object> responsePayload = new LinkedHashMap<>();

        if (String.valueOf(userId).equals(String.valueOf(extractedUserId))) {
            // Build new session if not found
            UserDTO user = userServiceClient.findById(userId, token);
            HistorySection history = historyClient.buildUserHistory(userId, token);

            UserSessionData sessionData = UserSessionData.builder()
                    .data(DataSection.builder()
                            .session_date(Instant.now().toString())
                            .sessionId(UUID.randomUUID().toString())
                            .userDetails(user)
                            .build())
                    .wallet(walletServiceImplementations.buildWalletSection(user, token))
                    .history(history)
                    .encrypted_signature(sec46.data_encryption(userId))
                    .build();

            String sessionJson = objectMapper.writeValueAsString(sessionData);
            redisTemplate.opsForValue().set(redisKey, sessionJson);

            // Also store walletId → userId if needed
            Long walletId = sessionData.getWallet().getWalletId();
            String walletKey = "wallet_to_user:" + walletId;

            redisTemplate.opsForValue().set(walletKey, String.valueOf(userId));

            responsePayload.put("status", "success");
            responsePayload.put("type", "message");
            responsePayload.put("message", "Connection established and user session initialized");
            responsePayload.put("data", sessionData);
        }
        sendMessage(session, responsePayload);
    }

    @SuppressWarnings("deprecation")
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
                sendErrorAndClose(session, "JWT token is missing", "Authentication required from request.");
                return;
            }

            if (!jwtTokenProvider.validateToken(token)) {
                sendErrorAndClose(session, "Invalid token", "Invalid or expired token");
                return;
            }
            
            if (type.isEmpty() || type == null) {
                responseMessageObject.put("status", "error");
                responseMessageObject.put("type", "message");
                responseMessageObject.put("message", "Missing Type param..! Type parameter must be provided.");
                sendMessage(session, responseMessageObject);
            }
            
            if (type.equals("balance_by_currency")) {
                // Build the response
                Map<String, Object> response = new HashMap<>();

                String username = (String) payload.get("username");
                String currency = (String) payload.get("currencyType");

                if (!Arrays.stream(CurrencyType.values())
                        .anyMatch(ct -> ct.name().equals(currency.toString().toUpperCase()))) {
                    response.put("status", "error");
                    response.put("message", "Invalid Currency provided.*");
                    response.put("details",
                            "Please provide Currency type. any of this list (USD, EUR, NGN,GBP, JPY,AUD,CAD, CHF, CNY, OR INR)");
                    sendMessage(session, response);
                    return;
                }

                CurrencyType currencyType = CurrencyType.valueOf(currency.toString().toUpperCase());
                // Fetch user details
                UserDTO user = userServiceClient.findByUsername(username, token);

                if (user == null) {
                    response.put("error", "User not found");
                    response.put("details", "User not found: No user data returned for username " + username);
                    sendMessage(session, response);
                    return;
                }

                Optional<Wallet> walletOpt = walletRepository.findWalletByUserIdAndCurrencyCode(user.getId(), currencyType.name());

                if (walletOpt.isEmpty()) {
                    response.put("error", "Wallet or currency not found for user ID.");
                    response.put("details", "Wallet or currency not found for user ID " + user.getId() + " and currency " + currency);
                    sendMessage(session, response);
                    return;
                }

                Wallet wallet = walletOpt.get();

                // Find the specific currency balance
                CurrencyBalance currencyBalance = wallet.getBalances()
                        .stream()
                        .filter(balance -> balance.getCurrencyCode().equals(currencyType.name()))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Currency not found in wallet"));

                BigDecimal balance = currencyBalance.getBalance().setScale(2, BigDecimal.ROUND_HALF_UP);

                // Format balance
                String formattedBalance = FormatBigDecimal(balance);
                // Build response
                Map<String, Object> walletBalance = new HashMap<>();
                walletBalance.put("id", user.getId());
                walletBalance.put("currency", currencyType);
                walletBalance.put("balance", balance);
                walletBalance.put("wallet", formattedBalance);
                walletBalance.put("symbol", currencyBalance.getCurrencySymbol());

                Map<String, Object> response_wallet = new HashMap<>();
                response_wallet.put("wallet_balance", walletBalance);

                sendMessage(session, response_wallet);
                return;
            }
            
            if (type.equals("get_balance")) {
                String username = (String) payload.get("username");
                // Fetch user details
                UserDTO user = userServiceClient.findByUsername(username, token);

                // Fetch main wallet balance
                Optional<Wallet> walletOptional = walletRepository.findByUserId(user.getId());

                // Build the response
                Map<String, Object> response = new HashMap<>();

                if (walletOptional.isPresent()) {
                    Wallet wallet = walletOptional.get();
                    // Prepare list of wallet balances with currency details
                    List<Map<String, Object>> balanceDetails = wallet.getBalances().stream()
                        .map(currencyBalance -> {
                            Map<String, Object> balanceInfo = new HashMap<>();
                                
                            balanceInfo.put("currency_code", currencyBalance.getCurrencyCode());
                            balanceInfo.put("symbol", currencyBalance.getCurrencySymbol());
                            balanceInfo.put("balance", FormatBigDecimal(currencyBalance.getBalance()));
                                return balanceInfo;
                            }).collect(Collectors.toList());
                    
                    // Fetch transaction history count
                    Long transactionCount = historyClient.getTransactionCount(user.getId(), token);

                    String transactionHistoryLabel = transactionCount > 1 ? transactionCount + " times" : transactionCount ==0 ? "No history" : "once";
                    
                    response.put("transaction_history_count", transactionHistoryLabel);
                    response.put("wallet_balances", balanceDetails);
                    sendMessage(session, response);
                    return;
                }

                response.put("message", "Wallet not found");
                response.put("details", "User wallet not found.");
                sendMessage(session, response);
                return;
            }

            if (type.equals("wallet-deduction")) {
                String userStringId = String.valueOf(payload.get("userId"));
                String walletIdInString = String.valueOf(payload.get("walletId"));
                String currency = (String) payload.get("currencyType");
                String recipientUser = (String) payload.get("recipientUser");

                Long userId = Long.parseLong(userStringId);
                Long walletId = Long.parseLong(walletIdInString);

                BigDecimal amount = new BigDecimal(((String) payload.get("amount")).replace(",", "."));
                BigDecimal finalDeduction = new BigDecimal(((String) payload.get("finalDeduction")).replace(",", "."));
                
                CurrencyType currencyType = CurrencyType.valueOf(currency.toUpperCase());
                
                UserDTO recipientUserDto = userServiceClient.findByUsername(recipientUser, token);
                if (recipientUserDto == null) {
                    sendErrorAndClose(session, "Recipient User not found.!",
                            "The username " + recipientUser + " Not found in our system, check and try again.!");
                    return;
                }
            
                Optional<Wallet> wallet = walletRepository.findById(walletId);
                if (!wallet.isPresent()) {
                    sendErrorAndClose(session, "Wallet not found",
                            "This wallet Id provided is not recognized in our system.");
                    return;
                }

        
                synchronized (("wallet-transfer-" + userId + "-" + recipientUserDto.getId()).intern()) {
                    Wallet senderUserWallet = walletRepository.findWalletByUserId(userId);
                    Wallet recipientUserWallet = walletRepository.findWalletByUserId(recipientUserDto.getId());
            
                    if (recipientUserWallet == null) {
                        sendErrorAndClose(session, "Recipient wallet not found",
                                "Recipient wallet not found in our system.");
                        return;
                    }

                    List<CurrencyBalance> senderBalances = senderUserWallet.getBalances();
                    CurrencyBalance senderCurrencyBalance = senderBalances.stream()
                            .filter(b -> b.getCurrencyCode().equalsIgnoreCase(currencyType.name()))
                            .findFirst()
                            .orElse(null);
                    if (senderCurrencyBalance == null) {
                        responseMessageObject.put("status", "error");
                        responseMessageObject.put("message",
                                "Currency " + currencyType + " not found in sender wallet.");
                        sendMessage(session, responseMessageObject);
                        return;
                    }

                    if (senderCurrencyBalance.getBalance().compareTo(amount) < 0) {
                        responseMessageObject.put("status", "error");
                        responseMessageObject.put("message", "Insufficient balance to deduct the requested amount.");
                        sendMessage(session, responseMessageObject);
                        return;
                    }

                    // Deduct finalDeduction from sender
                    senderCurrencyBalance.setBalance(senderCurrencyBalance.getBalance().subtract(finalDeduction));
                    senderUserWallet.setBalances(senderBalances);
                    walletRepository.save(senderUserWallet);

                    // Update Redis for sender
                    updateRedisWalletBalance(userId, currencyType, finalDeduction.negate());

                    // Credit recipient wallet
                    List<CurrencyBalance> recipientBalances = recipientUserWallet.getBalances();
                    CurrencyBalance recipientCurrencyBalance = recipientBalances.stream()
                            .filter(b -> b.getCurrencyCode().equalsIgnoreCase(currencyType.name()))
                            .findFirst()
                            .orElse(null);
                
                    recipientCurrencyBalance.setBalance(recipientCurrencyBalance.getBalance().add(amount));
                    recipientUserWallet.setBalances(recipientBalances);
                    walletRepository.save(recipientUserWallet);

                    // Update Redis for recipient
                    updateRedisWalletBalance(recipientUserDto.getId(), currencyType, amount);

                    responseMessageObject.put("status", "success");
                    responseMessageObject.put("message", "Amount transferred successfully.");
                    responseMessageObject.put("currencyType", currencyType.name());
                    responseMessageObject.put("newBalance",
                            senderCurrencyBalance.getBalance().setScale(2, RoundingMode.HALF_UP));
                    sendMessage(session, responseMessageObject);
                }
                
            }
        }catch(Exception e){
            sendErrorAndClose(session, "Error", e.getMessage());
            return;
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

    private void updateRedisWalletBalance(Long userId, CurrencyType currencyType, BigDecimal amountDelta)
            throws JsonProcessingException {
        String redisKey = "user_session:" + userId;
        String redisData = redisTemplate.opsForValue().get(redisKey);
        if (redisData != null) {
            UserSessionData sessionData = objectMapper.readValue(redisData, UserSessionData.class);
            WalletSection walletSection = sessionData.getWallet();
            List<WalletBalanceDTO> balances = walletSection.getWallet_balances();

            boolean found = false;
            for (WalletBalanceDTO dto : balances) {
                if (dto.getCurrency_code().equalsIgnoreCase(currencyType.name())) {
                    Object balanceObj = dto.getBalance();
                    BigDecimal current;

                    if (balanceObj instanceof String) {
                        current = new BigDecimal(((String) balanceObj).replace(",", ""));
                    } else if (balanceObj instanceof Number) {
                        current = BigDecimal.valueOf(((Number) balanceObj).doubleValue());
                    } else {
                        throw new IllegalArgumentException("Unsupported balance type: " + balanceObj.getClass());
                    }
                    BigDecimal updated = current.add(amountDelta);
                    dto.setBalance(updated.setScale(2, RoundingMode.HALF_UP).toPlainString());
                    found = true;
                    break;
                }
            }

            if (!found && amountDelta.compareTo(BigDecimal.ZERO) > 0) {
                WalletBalanceDTO newDto = new WalletBalanceDTO();
                newDto.setCurrency_code(currencyType.name());
                newDto.setBalance(amountDelta.setScale(2, RoundingMode.HALF_UP).toPlainString());
                balances.add(newDto);
            }
            walletSection.setWallet_balances(balances);
            sessionData.setWallet(walletSection);

            String updatedJson = objectMapper.writeValueAsString(sessionData);
            redisTemplate.opsForValue().set(redisKey, updatedJson);
        }
    }

    public static String FormatBigDecimal(BigDecimal amount) {
        String pattern = "#,##0.00";
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        DecimalFormat decimalFormat = new DecimalFormat(pattern, symbols);
        return decimalFormat.format(amount);
    }
    
    // private BigDecimal convertToBigDecimal(Object value) {
    //     if (value instanceof BigDecimal) {
    //         return (BigDecimal) value;
    //     } else if (value instanceof Number) {
    //         return BigDecimal.valueOf(((Number) value).doubleValue());
    //     } else if (value instanceof String) {
    //         return new BigDecimal(((String) value).replace(",", "."));
    //     } else {
    //         throw new IllegalArgumentException("Invalid value for BigDecimal: " + value);
    //     }
    // }

}

