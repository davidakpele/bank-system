package pesco.wallet_service.dtos;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletSection {
    private List<WalletBalanceDTO> wallet_balances;
    private Long walletId;
    private boolean hasTransferPin;
}
