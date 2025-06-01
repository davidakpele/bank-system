package pesco.withdrawal_service.utils;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class ApplicationSettings {

    @Value("${auth-service.base-url}")
    private String authServiceBaseUrl;

    @Value("${revenue-service.base-url}")
    private String revenueServiceBaseUrl;

    @Value("${history-service.base-url}")
    private String historyServiceBaseUrl;

    @Value("${wallet-service.base-url}")
    private String walletServiceBaseUrl;

    @Value("${wallet-websocket.url}")
    private String walletWebSocketUrl;

    @Value("${notification-service.base-url}")
    private String notificationServiceBaseUrl;

    @Value("${banklist-service.base-url}")
    private String banklistServiceBaseUrl;

    @Value("${blacklist-service.base-url}")
    private String blacklistServiceUrl;

    @Value("${revenue-websocket.url}")
    private String revenueWebSocketUrl;

    // Public getter methods
    public String getAuthServiceBaseUrl() {
        return authServiceBaseUrl;
    }

    public String getRevenueServiceBaseUrl() {
        return revenueServiceBaseUrl;
    }

    public String getHistoryServiceBaseUrl() {
        return historyServiceBaseUrl;
    }

    public String getWalletServiceBaseUrl() {
        return walletServiceBaseUrl;
    }

    public String getWalletWebSocketUrl() {
        return walletWebSocketUrl;
    }
    
    public String getRevenueWebSocketUrl() {
        return revenueWebSocketUrl;
    }

    public String getNotificationBaseUrl() {
        return notificationServiceBaseUrl;
    }

    public String banklistBaseUrl() {
        return banklistServiceBaseUrl;
    }

    public String blackListBaseUrl() {
        return blacklistServiceUrl;
    }
}
