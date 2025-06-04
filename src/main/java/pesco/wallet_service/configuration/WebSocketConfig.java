package pesco.wallet_service.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import pesco.wallet_service.broker.WalletServiceSocketBroker;


@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WalletServiceSocketBroker walletServiceSocketBroker;

    public WebSocketConfig(WalletServiceSocketBroker walletServiceSocketBroker) {
        this.walletServiceSocketBroker = walletServiceSocketBroker;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(walletServiceSocketBroker, "/ws/wallet/**")
                .setAllowedOrigins("*");
    }
}
