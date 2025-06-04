package pesco.revenue_service.configurations;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import pesco.revenue_service.broker.RevenueBroker;


@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RevenueBroker revenueBroker;

    public WebSocketConfig(RevenueBroker revenueBroker) {
        this.revenueBroker = revenueBroker;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(revenueBroker, "/ws/revenue/user/**")
            .setAllowedOrigins("*");
                
        registry.addHandler(revenueBroker, "/ws/admin/**")
            .setAllowedOrigins("*");
    }
}
