package pesco.withdrawal_service.clients;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import pesco.withdrawal_service.dto.WalletDTO;
import pesco.withdrawal_service.enums.CurrencyType;
import pesco.withdrawal_service.payloads.CreateWalletRequest;
import pesco.withdrawal_service.utils.ApplicationSettings;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;


@Component
public class WalletServiceClient {

    private final WebClient walletServiceWebClient;
    @SuppressWarnings("unused")
    private final ApplicationSettings settings;
    private final ObjectMapper objectMapper = new ObjectMapper();
 
    private String walletSocketUrl;

    @Autowired
    public WalletServiceClient(WebClient walletServiceWebClient, ApplicationSettings applicationSettings) {
        this.walletServiceWebClient = walletServiceWebClient;
        this.settings = applicationSettings;
        this.walletSocketUrl = applicationSettings.getWalletWebSocketUrl();
    }

    public BigDecimal FetchUserBalance(Long userId, String currencyCode, String token) {
        String url = String.format("/api/v1/wallet/balance/userId/%d/currency/%s", userId, currencyCode);
        Map<String, Object> response = walletServiceWebClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .block();

        // Extract and return the balance
        if (response != null && response.containsKey("formatted_balance")) {
            try {
                String formattedBalance = response.get("formatted_balance").toString();
                return parseFormattedString(formattedBalance);
            } catch (ParseException e) {
                throw new RuntimeException("Failed to parse balance: " + e.getMessage(), e);
            }
        }

        throw new RuntimeException(
                "Failed to fetch balance for userId: " + userId + " and currencyCode: " + currencyCode);
    }

    public static BigDecimal parseFormattedString(String formattedValue) throws ParseException {
        String pattern = "#,##0.00";
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        DecimalFormat decimalFormat = new DecimalFormat(pattern, symbols);

        Number number = decimalFormat.parse(formattedValue);
        return new BigDecimal(number.toString());
    }

    public WalletDTO createUserWallet(Long id) {
        // Create the request body
        CreateWalletRequest request = new CreateWalletRequest(id);
        WalletDTO walletDTO = this.walletServiceWebClient.post()
                .uri("/api/v1/wallet/create")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(WalletDTO.class)
                .block();

        return walletDTO;
    }

    public CompletableFuture<String> DeductAmountFromSenderWallet(Long id, String username, String recipientUser,
    String amount, String finalDeduction, CurrencyType currencyType, Long walletId, String token) {
        Map<String, Object> message = Map.of(
                "type", "wallet-deduction",
                "senderUser", username,
                "recipientUser", recipientUser,
                "finalDeduction", finalDeduction,
                "amount", amount,  
                "currencyType", currencyType,
                "userId", id,
                "username", username,
                "walletId", walletId,
                "token", token);
        return sendMessageToWalletService(message, id, token);
    }

    public CompletableFuture<String> updateUserWallet(Long id, BigDecimal amount, CurrencyType currencyType,
            Long senderId, String token) {
        Map<String, Object> message = Map.of(
                "type", "update-wallet",
                "userId", id,
                "currencyType", currencyType,
                "amount", amount,
                "token", token);
        return sendMessageToWalletService(message, senderId, token);
    }

    @SuppressWarnings("removal")
    private CompletableFuture<String> sendMessageToWalletService(Map<String, Object> message, Long senderId,
            String token) {
        String url = walletSocketUrl + "?userId=" + senderId + "&token=" + token;
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        try {
            String json = objectMapper.writeValueAsString(message);

            StandardWebSocketClient client = new StandardWebSocketClient();

            // Create a copy of values to use safely inside inner class
            final String messagePayload = json;

            client.doHandshake(new TextWebSocketHandler() {

                @Override
                public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                    session.sendMessage(new TextMessage(messagePayload));
                }

                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                    responseFuture.complete(message.getPayload());
                }

                @Override
                public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                    responseFuture.completeExceptionally(exception);
                }

            }, url);

        } catch (Exception e) {
            System.out.println("❌ Exception while sending WebSocket message: " + e.getMessage());
            responseFuture.completeExceptionally(e);
        }

        return responseFuture;
    }

    public WalletDTO findByUserId(Long userId, String token) {
        WalletDTO walletDTO = this.walletServiceWebClient.get()
                .uri("/api/v1/wallet/userId/{userId}", userId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(WalletDTO.class)
                .block();
        return walletDTO;
    }



    
}

