package pesco.revenue_service.serviceImplementations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.transaction.Transactional;
import pesco.revenue_service.enums.CurrencyType;
import pesco.revenue_service.enums.TransactionType;
import pesco.revenue_service.model.CurrencyBalance;
import pesco.revenue_service.model.Revenue;
import pesco.revenue_service.model.RevenueTransaction;
import pesco.revenue_service.repository.RevenueRepository;
import pesco.revenue_service.repository.RevenueTransactionRepository;
import pesco.revenue_service.services.RevenueService;

@Service
public class RevenueServiceImplementations implements RevenueService {

    private final RevenueRepository revenueRepository;
    private final RevenueTransactionRepository transactionRepository;
    
    @Autowired
    public RevenueServiceImplementations(RevenueRepository revenueRepository, RevenueTransactionRepository transactionRepository) {
        this.revenueRepository = revenueRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public Revenue addRevenue(Map<String, Object> payload) {
        BigDecimal amount = new BigDecimal(((String) payload.get("amount")).replace(",", "."));
        String cType = String.valueOf(payload.get("currencyType"));
        CurrencyType currencyType = CurrencyType.valueOf(cType.toUpperCase());

        Revenue revenue = revenueRepository.findFirstByOrderByIdAsc().orElse(null);
        if (revenue == null) {
            revenue = new Revenue();
            revenue.setBalances(new ArrayList<>());
        }

        List<CurrencyBalance> balances = revenue.getBalances();
        CurrencyBalance targetBalance = null;

        for (CurrencyBalance balance : balances) {
            if (balance.getCurrencyCode().equalsIgnoreCase(currencyType.name())) {
                targetBalance = balance;
                break;
            }
        }

        if (targetBalance != null) {
            targetBalance.setBalance(targetBalance.getBalance().add(amount));
        } else {
            CurrencyBalance newBalance = new CurrencyBalance();
            newBalance.setCurrencyCode(currencyType.name());
            newBalance.setCurrencySymbol(getCurrencySymbol(currencyType));
            newBalance.setBalance(amount);
            newBalance.setRevenue(revenue);
            balances.add(newBalance);
        }

       logTransaction(revenue, currencyType, amount, TransactionType.CREDITED);

        return revenueRepository.save(revenue); 
    }


    /**
     * Returns the currency symbol for a given currency type.
     */
    private String getCurrencySymbol(CurrencyType currency) {
        switch (currency) {
            case USD:
                return "$";
            case EUR:
                return "€";
            case NGN:
                return "₦";
            case GBP:
                return "£";
            case JPY:
                return "¥";
            case AUD:
                return "A$";
            case CAD:
                return "C$";
            case CHF:
                return "CHF";
            case CNY:
                return "¥";
            case INR:
                return "₹";
            default:
                throw new IllegalArgumentException("Unknown currency type: " + currency);
        }
    }


    private void logTransaction(Revenue revenue, CurrencyType currencyType, BigDecimal amount, TransactionType type) {
        RevenueTransaction transaction = new RevenueTransaction();
        transaction.setRevenue(revenue);
        transaction.setTransactionType(type);
        transaction.setAmount(amount);
        transaction.setWallet(currencyType);
        transactionRepository.save(transaction);
    }
}
