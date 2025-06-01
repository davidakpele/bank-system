package pesco.withdrawal_service.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

@Service
public class TransferFeeCalculator {
    
    public BigDecimal calculateFee(BigDecimal amount) {
        BigDecimal rate;

        if (amount.compareTo(new BigDecimal("1000000")) >= 0) {
            rate = new BigDecimal("0.0020"); // 0.20%
        } else if (amount.compareTo(new BigDecimal("500000")) >= 0) {
            rate = new BigDecimal("0.0015"); // 0.15%
        } else if (amount.compareTo(new BigDecimal("100000")) >= 0) {
            rate = new BigDecimal("0.0010"); // 0.10%
        } else if (amount.compareTo(new BigDecimal("50000")) >= 0) {
            rate = new BigDecimal("0.008"); // 0.8%
        } else if (amount.compareTo(new BigDecimal("10000")) >= 0) {
            rate = new BigDecimal("0.006"); // 0.6%
        } else {
            rate = new BigDecimal("0.005"); // 0.5%
        }

        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
    
}