package pesco.wallet_service.bootstrap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistorySection {
    private List<TransactionRecord> deposit_container;
    private List<TransactionRecord> withdraws_container;
    private List<TransactionRecord> swap_container;
    private List<TransactionRecord> services_container;
    private List<TransactionRecord> transfer_container;
    private List<TransactionRecord> maintenances_container;
}

