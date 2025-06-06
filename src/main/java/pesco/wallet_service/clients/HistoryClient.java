package pesco.wallet_service.clients;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import pesco.wallet_service.bootstrap.HistorySection;
import pesco.wallet_service.bootstrap.TransactionRecord;
import pesco.wallet_service.exceptions.UserClientNotFoundException;
import reactor.core.publisher.Mono;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;

@Service
public class HistoryClient {

    private final WebClient historyClient;

    @Autowired
    public HistoryClient(@Qualifier("historyServiceWebClient") WebClient historyClient) {
        this.historyClient = historyClient;
    }



    public Long getTransactionCount(Long userId, String token) {
        // Call the endpoint and return the transaction count
        return historyClient.get()
                .uri(uriBuilder -> uriBuilder
                .path("/api/v1/history/transactions/count")
                .queryParam("userId", userId)
                .build())
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorMessage -> {
                                    if (clientResponse.statusCode().is4xxClientError()) {
                                        String details = ExtractDetailsFromError(errorMessage);
                                        return Mono.error(
                                                new UserClientNotFoundException("User not found: " + details,
                                                        errorMessage));
                                    }
                                    return Mono.error(new RuntimeException("Server error: " + errorMessage));
                                }))
                .bodyToMono(Long.class)
                .block();
    }

    private String ExtractDetailsFromError(String errorMessage) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(errorMessage);
            return rootNode.path("message").asText();
        } catch (JsonProcessingException e) {
            return "No details available";
        }
    }

    public HistorySection buildUserHistory(Long userId, String token) {
        List<TransactionRecord> deposits = getTransactionsByType(userId, "DEPOSIT", token);
        List<TransactionRecord> withdrawals = getTransactionsByType(userId, "WITHDRAW", token);
        List<TransactionRecord> swaps = getTransactionsByType(userId, "SWAP", token);
        List<TransactionRecord> services = getTransactionsByType(userId, "SERVICE_ACTION", token);
        List<TransactionRecord> transfers = getTransactionsByType(userId, "TRANSFER", token);
        List<TransactionRecord> maintenances = getTransactionsByType(userId, "MAINTENANCE", token);

        HistorySection history = new HistorySection();
        history.setDeposit_container(deposits != null ? deposits : List.of());
        history.setWithdraws_container(withdrawals != null ? withdrawals : List.of());
        history.setSwap_container(swaps != null ? swaps : List.of());
        history.setServices_container(services != null ? services : List.of());
        history.setTransfer_container(transfers != null ? transfers : List.of());
        history.setMaintenances_container(maintenances != null ? maintenances : List.of());

        return history;
    }



    public List<TransactionRecord> getTransactionsByType(Long userId, String type, String token) {
        return historyClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/history/list")
                        .queryParam("userId", userId)
                        .queryParam("type", type)
                        .build())
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorMessage -> {
                                    if (clientResponse.statusCode().is4xxClientError()) {
                                        String details = ExtractDetailsFromError(errorMessage);
                                        return Mono.error(
                                                new UserClientNotFoundException("User not found: " + details, errorMessage));
                                    }
                                    return Mono.error(new RuntimeException("Server error: " + errorMessage));
                                }))
                .bodyToMono(new ParameterizedTypeReference<List<TransactionRecord>>() {})
                .block();
    }

}
