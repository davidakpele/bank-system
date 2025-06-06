package pesco.revenue_service.bootstrap;

import pesco.revenue_service.dto.MessageDTO;
import lombok.*;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueSessionData {
    private RevenueDataSection data;
    private RevenueHistorySection history;
    private RevenueWalletSection wallet;
    private Map<String, MessageDTO> messages;
}
