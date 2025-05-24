package pesco.authentication_service.clients;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import pesco.authentication_service.exceptions.UserClientNotFoundException;
import pesco.authentication_service.payloads.WalletRequest;
import reactor.core.publisher.Mono;

@Service
public class WalletServiceClient {

    private final WebClient walletServiceWebClient;

    @Autowired
    public WalletServiceClient(WebClient walletServiceWebClient) {
        this.walletServiceWebClient = walletServiceWebClient;
    }

    public Object createUserWallet(Long userId) {
        WalletRequest walletRequest = new WalletRequest(userId);

        return this.walletServiceWebClient.post()
                .uri("/api/v1/wallet/create")
                .bodyValue(walletRequest)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorMessage -> {
                                    if (clientResponse.statusCode().is4xxClientError()) {
                                        String details = extractDetailsFromError(errorMessage);
                                        return Mono.error(new UserClientNotFoundException(
                                                "Client error while creating wallet", details));
                                    }
                                    return Mono.error(new RuntimeException("Server error while creating wallet"));
                                }))
                .bodyToMono(Object.class)
                .block();
        
    }

    private String extractDetailsFromError(String errorMessage) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(errorMessage);
            return rootNode.path("message").asText();
        } catch (JsonProcessingException e) {
            return "No details available";
        }
    }

    
}
