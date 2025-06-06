package pesco.revenue_service.bootstrap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRecord {
    private Long id;
    private String type; 
    private BigDecimal amount;
    private String currency;
    private String status;
    private Instant timestamp;
    private String description;
}
