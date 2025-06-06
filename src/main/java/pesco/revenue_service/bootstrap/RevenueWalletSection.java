package pesco.revenue_service.bootstrap;

import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueWalletSection {
    private Long revenueId;
    private List<RevenueBalanceDTO> revenue_balances;
    private String currencyDisplay;
}
