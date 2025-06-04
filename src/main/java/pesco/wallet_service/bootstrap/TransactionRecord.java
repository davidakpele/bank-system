package pesco.wallet_service.bootstrap;

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
  private String type; // DEPOSIT, WITHDRAW, SWAP, etc.
  private BigDecimal amount;
  private String currency; // NGN, USD, etc.
  private String status; // PENDING, SUCCESS, FAILED
  private Instant timestamp;
  private String description;
}
