package pesco.wallet_service.bootstrap;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import pesco.wallet_service.dtos.MessageDTO;
import pesco.wallet_service.dtos.WalletSection;
import pesco.wallet_service.encryptions.EncryptedSignature;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserSessionData {
    private DataSection data;
    private HistorySection history;
    private WalletSection wallet;
    private Map<String, MessageDTO> messages;
    private EncryptedSignature encrypted_signature;
}
