package pesco.wallet_service.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRecordDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String gender;
    private String country;
    private String city;
    private boolean transferPinSet;
    private boolean locked;
    private LocalDateTime lockedAt;
    private String referralCode;
    private boolean blocked;
    private Long blockedDuration;
    private String blockedUntil;
    private String blockedReason;
    private String totalReferers;
    private String referralLink;
    private String photo;
}