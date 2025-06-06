package pesco.withdrawal_service.clients;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import pesco.withdrawal_service.enums.CurrencyType;
import pesco.withdrawal_service.utils.ApplicationSettings;

@Service
public class RevenueServiceClient {

    @SuppressWarnings("unused")
    private final WebClient revenueServiceWebClient;
    @SuppressWarnings("unused")
    private final ApplicationSettings settings;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String revenueSocketUrl;

    @Autowired
    public RevenueServiceClient(WebClient revenueServiceWebClient, ApplicationSettings applicationSettings) {
        this.revenueServiceWebClient = revenueServiceWebClient;
        this.settings = applicationSettings;
        this.revenueSocketUrl = applicationSettings.getRevenueWebSocketUrl();
    }

    public CompletableFuture<String> addToPlatformRevenue(Long userId, String feeAmount, CurrencyType currencyType, String token) {
        Map<String, Object> message = Map.of(
                "type", "add_revenue",
                "amount", feeAmount,
                "currencyType", currencyType,
                "token", token);
        return sendMessageToRevenueService(message, userId, token);
    }

    @SuppressWarnings("removal")
    private CompletableFuture<String> sendMessageToRevenueService(Map<String, Object> message, Long userId, String token) {
        String url = revenueSocketUrl + "/user/?userId=" + userId + "&token=" + token;
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        try {
            String json = objectMapper.writeValueAsString(message);

            StandardWebSocketClient client = new StandardWebSocketClient();

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
            responseFuture.completeExceptionally(e);
        }

        return responseFuture;
    }
}
