package pesco.revenue_service.broker;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import pesco.revenue_service.dto.RevenueDTO;
import pesco.revenue_service.exceptions.RevenueNotFoundException;
import pesco.revenue_service.model.Revenue;
import pesco.revenue_service.repository.RevenueRepository;
import pesco.revenue_service.services.RevenueService;
import pesco.revenue_service.utils.JwtTokenProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.web.socket.*;

@Component
public class RevenueBroker extends AbstractWebSocketHandler {
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RevenueRepository revenueRepository;


    @Autowired
    private RevenueService revenueService;

    private final Map<String, WebSocketSession> adminSessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        URI uri = session.getUri();
        if (uri == null) {
            session.close();
            return;
        }

        // Determine if this is admin or user endpoint
        boolean isAdminEndpoint = uri.getPath().contains("/admin/");

        Map<String, String> queryParams = extractQueryParams(uri.getQuery());
        String userId_In_string = queryParams.get("userId");
        long userId = Long.parseLong(userId_In_string);
        String token = extractTokenFromQuery(uri.getQuery());

        if (isAdminEndpoint) {
            handleAdminConnection(session, userId, token);
        } else {
            handleUserConnection(session, userId, token);
        }
    }

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
                sendErrorAndClose(session, "INVALID_TOKEN", "JWT token is missing", "Authentication required");
                return;
            }

            if (!jwtTokenProvider.validateToken(token)) {
                sendErrorAndClose(session, "INVALID_TOKEN", "Invalid token", "Invalid or expired token");
                return;
            }

            if (type.isEmpty() || type == null) {
                responseMessageObject.put("status", "error");
                responseMessageObject.put("type", "message");
                responseMessageObject.put("message", "Missing `Type` param..! Type parameter must be provided.");
                sendMessage(session, responseMessageObject);
                return;
            }
            
            
            if (type.equals("add_revenue")) {
                try {
                    // CreateRevenue request = new CreateRevenue(amount, currencyType);
                    Revenue revenue = revenueService.addRevenue(payload);

                    // Lazy collections are now initialized because we are inside a transaction
                    responseMessageObject.put("status", "success");
                    responseMessageObject.put("type", "revenue_updated");
                    responseMessageObject.put("message", "Revenue wallet updated successfully.");
                    sendMessage(session, responseMessageObject);

                } catch (IllegalArgumentException e) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "invalid_currency");
                    responseMessageObject.put("message", "Invalid currency type: " + payload.get("currencyType"));
                    sendMessage(session, responseMessageObject);
                } catch (Exception e) {
                    responseMessageObject.put("status", "error");
                    responseMessageObject.put("type", "server_error");
                    responseMessageObject.put("message", "Something went wrong while adding revenue.");
                    sendMessage(session, responseMessageObject);
                    e.printStackTrace(); // Log the real error
                }

                return;
            }                                                  
            
            if (type.equals("get_revenue")) {
                try {
                    RevenueDTO revenueDTO = revenueRepository.findFirstByOrderByIdAsc()
                            .map(RevenueDTO::new)
                            .orElseThrow(() -> new RevenueNotFoundException("Revenue data not found"));

                    Map<String, Object> response = new HashMap<>();
                    response.put("type", "get_revenue");
                    response.put("status", "success");
                    response.put("data", revenueDTO);

                    sendMessage(session, response);
                } catch (RevenueNotFoundException e) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("type", "get_revenue");
                    errorResponse.put("status", "error");
                    errorResponse.put("message", e.getMessage());

                    sendMessage(session, errorResponse);
                }
                return;
            } 
        } catch (Exception e) {
            sendErrorAndClose(session, "BAD_REQUEST", "Error", "Invalid message format");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        userSessions.remove(session.getId());
    }

    private void handleUserConnection(WebSocketSession session, Long userId, String token) throws IOException {
        // JWT validation
        if (token == null || token.isEmpty()) {
            sendErrorAndClose(session, "INVALID_TOKEN", "JWT token is missing", "Authentication required");
            return;
        }

        if (!jwtTokenProvider.validateToken(token)) {
            sendErrorAndClose(session, "INVALID_TOKEN", "Invalid or expired token", "Invalid or expired token");
            return;
        }

        String extractedUserId = jwtTokenProvider.getUserIdFromJWT(token);

        if (!String.valueOf(userId).equals(String.valueOf(extractedUserId))) {
            sendErrorAndClose(session, "AUTH_REQUIRED", "Unauthorized", "User ID mismatch");
            return;
        }
    }

    private void handleAdminConnection(WebSocketSession session, Long userId, String token) throws IOException {
        // JWT validation
        if (token == null || token.isEmpty()) {
            sendErrorAndClose(session, "AUTH_REQUIRED", "Authentication required", "JWT token is missing");
            return;
        }

        if (!jwtTokenProvider.validateToken(token)) {
            sendErrorAndClose(session, "INVALID_TOKEN", "Invalid or expired token", "Invalid or expired token");
            return;
        }

        // Check user ID
        String extractedUserId = jwtTokenProvider.getUserIdFromJWT(token);
        if (!String.valueOf(userId).equals(String.valueOf(extractedUserId))) {
            sendErrorAndClose(session, "USER_ID_MISMATCH", "User ID mismatch", "User ID mismatch");
            return;
        }

        // Check admin role
        String role = jwtTokenProvider.getRoleFromJWT(token);
        if (!"ADMIN".equals(role) || !"SUPER_ADMIN".equals(role) || !"SUPER_USER".equals(role)) {
            sendErrorAndClose(session, "ADMIN_REQUIRED","Forbidden", "Admin role required");
            return;
        }

        // Add to admin sessions
        adminSessions.put(session.getId(), session);
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

    private void sendErrorAndClose(WebSocketSession session, String errorType, String message, String details) {
        try {
            // Create a detailed error response
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("errorType", errorType);
            errorResponse.put("message", message);
            errorResponse.put("details", details);
            errorResponse.put("timestamp", Instant.now().toString());
            
            // Add HTTP-like status code to the payload
            int statusCode = determineStatusCode(errorType);
            errorResponse.put("statusCode", statusCode);
 
            // Convert to JSON
            String errorJson = objectMapper.writeValueAsString(errorResponse);
            
            // Send the error message
            session.sendMessage(new TextMessage(errorJson));

            // Determine appropriate WebSocket close status
            CloseStatus closeStatus = mapToCloseStatus(errorType);
            
            // Small delay to ensure message delivery before closing
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // Close the session with appropriate status
            if (session.isOpen()) {
                session.close(closeStatus);
            }
        } catch (Exception e) {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.SERVER_ERROR.withReason("Internal server error"));
                }
            } catch (IOException ignored) {
                // Last resort if closing fails
            }
        }
    }
        
    private int determineStatusCode(String errorType) {
        // Map your error types to HTTP-like status codes
        switch (errorType) {
            case "AUTH_REQUIRED":
                return 401;
            case "INVALID_TOKEN":
                return 403;
            case "USER_ID_MISMATCH":
                return 403;
            case "ADMIN_REQUIRED":
                return 403;
            case "VALIDATION_ERROR":
                return 400;
            case "BAD_REQUEST":
                return 400;
            default:
                return 500;
        }
    }
    
    private CloseStatus mapToCloseStatus(String errorType) {
        // Map your error types to WebSocket close status codes
        switch (errorType) {
            case "AUTH_REQUIRED":
                return CloseStatus.POLICY_VIOLATION.withReason("Authentication required");
            case "INVALID_TOKEN":
                return CloseStatus.POLICY_VIOLATION.withReason("Invalid token");
            case "USER_ID_MISMATCH":
                return CloseStatus.NOT_ACCEPTABLE.withReason("User ID mismatch");
            case "ADMIN_REQUIRED":
                return CloseStatus.POLICY_VIOLATION.withReason("Admin privileges required");
            case "VALIDATION_ERROR":
                return CloseStatus.BAD_DATA.withReason("Validation error");
            default:
                return CloseStatus.SERVER_ERROR.withReason("Internal server error");
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
