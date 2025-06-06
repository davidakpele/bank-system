package pesco.revenue_service.bootstrap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import pesco.revenue_service.dto.AdminUserDetailDTO;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueDataSection {
    private String session_date;
    private String sessionId;
    private AdminUserDetailDTO adminDetails;
}
