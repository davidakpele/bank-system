package pesco.wallet_service.bootstrap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import pesco.wallet_service.dtos.UserDTO;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSection {
    private String session_date;
    private String sessionId;
    private UserDTO userDetails;
}
