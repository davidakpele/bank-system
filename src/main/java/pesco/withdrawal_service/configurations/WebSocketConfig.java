package pesco.withdrawal_service.configurations;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import pesco.withdrawal_service.broker.domain.WithdrawalBroker;


@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WithdrawalBroker wallBroker;

    public WebSocketConfig(WithdrawalBroker wallBroker) {
        this.wallBroker = wallBroker;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wallBroker, "/ws/withdraw/**")
                .setAllowedOrigins("*");
    }
}
