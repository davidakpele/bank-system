package pesco.revenue_service.bootstrap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueBalanceDTO {
    private String currency_code;
    private String symbol;
    private String balance;
}
