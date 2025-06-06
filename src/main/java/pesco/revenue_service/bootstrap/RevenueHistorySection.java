package pesco.revenue_service.bootstrap;

import java.util.List;
import lombok.*;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueHistorySection {
    private List<TransactionRecord> credited;
    private List<TransactionRecord> debited;
    private List<TransactionRecord> services;
    private List<TransactionRecord> transfer;
    private List<TransactionRecord> others;
}
