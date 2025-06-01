package pesco.withdrawal_service.broker.domain.logs;

import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class TransactionLogger {

    public void logTransaction(Object message) throws JsonProcessingException {
        // Logic to save to DB, file, or send to external service
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(message);
        System.out.println("Transaction logged: " + json);
    }
}
