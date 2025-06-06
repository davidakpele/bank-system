package pesco.revenue_service.serviceImplementations;

import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import lombok.AllArgsConstructor;
import pesco.revenue_service.bootstrap.RevenueHistorySection;
import pesco.revenue_service.bootstrap.TransactionRecord;
import pesco.revenue_service.enums.TransactionType;
import pesco.revenue_service.model.RevenueTransaction;
import pesco.revenue_service.repository.RevenueTransactionRepository;
import pesco.revenue_service.services.HistoryService;

@Service
@AllArgsConstructor
public class HistoryServiceImpl implements HistoryService{

    private final RevenueTransactionRepository revenueTransactionRepository;

    @Override
    public RevenueHistorySection buildUserHistory() {
        List<TransactionRecord> deposits = getTransactionsByType("DEPOSIT");
        List<TransactionRecord> withdrawals = getTransactionsByType("WITHDRAW");
        List<TransactionRecord> swaps = getTransactionsByType("SWAP");
        List<TransactionRecord> services = getTransactionsByType("SERVICE_ACTION");
        List<TransactionRecord> transfers = getTransactionsByType("TRANSFER");
        List<TransactionRecord> othRecords = getTransactionsByType("MAINTENANCE");

        RevenueHistorySection history = new RevenueHistorySection();
        history.setDebited(deposits != null ? deposits : List.of());
        history.setOthers(withdrawals != null ? withdrawals : List.of());
        history.setDebited(swaps != null ? swaps : List.of());
        history.setServices(services != null ? services : List.of());
        history.setTransfer(transfers != null ? transfers : List.of());
        history.setOthers(othRecords != null ? othRecords : List.of());
    
        return history;
    }

    private List<TransactionRecord> getTransactionsByType(String type) {
        TransactionType transactionType = TransactionType.valueOf(type.toUpperCase());

        List<RevenueTransaction> transactions = revenueTransactionRepository.findByTransactionType(transactionType);

        return transactions.stream()
            .map(tx -> TransactionRecord.builder()
                .id(tx.getId())
                .type(tx.getTransactionType().name())
                .amount(tx.getAmount())
                .currency(tx.getWallet().name())
                .status("SUCCESS")
                .timestamp(tx.getCreatedOn().toInstant(ZoneOffset.UTC))
                .description("Revenue Tx ID: " + tx.getId())
                .build())
            .toList();
    }

}
