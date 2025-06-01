package pesco.history_service.payloads;
 
import java.math.BigDecimal;
import pesco.history_service.enums.CurrencyType;
import pesco.history_service.enums.TransactionType;

public class CreateWalletHistory {
    private Long userId;
    private String senderUsername;
    private String recipientUsername;
    private BigDecimal amount;
    private CurrencyType currencyType;
    private String description;
    private Long walletId;
    private TransactionType type;

    public CreateWalletHistory() {
    }
    

    public CreateWalletHistory(Long userId, String senderUsername, String recipientUsername, BigDecimal amount,
            CurrencyType currencyType, String description, String ip_address, String message, Long walletId, TransactionType type) {
        this.userId = userId;
        this.senderUsername = senderUsername;
        this.recipientUsername = recipientUsername;
        this.amount = amount;
        this.currencyType = currencyType;
        this.description = description;
        this.walletId = walletId;
        this.type = type;
    }


    public Long getUserId() {
        return this.userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSenderUsername() {
        return this.senderUsername;
    }

    public void setSenderUsername(String senderUsername) {
        this.senderUsername = senderUsername;
    }

    public String getRecipientUsername() {
        return this.recipientUsername;
    }

    public void setRecipientUsername(String recipientUsername) {
        this.recipientUsername = recipientUsername;
    }

    public BigDecimal getAmount() {
        return this.amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public CurrencyType getCurrencyType() {
        return this.currencyType;
    }

    public void setCurrencyType(CurrencyType currencyType) {
        this.currencyType = currencyType;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getWalletId() {
        return this.walletId;
    }

    public void setWalletId(Long walletId) {
        this.walletId = walletId;
    }

    public TransactionType getType() {
        return this.type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "CreateWalletHistory{" +
                "userId=" + userId +
                ", senderUsername='" + senderUsername + '\'' +
                ", recipientUsername='" + recipientUsername + '\'' +
                ", amount=" + amount +
                ", currencyType=" + currencyType +
                ", description='" + description + '\'' +
                ", walletId=" + walletId +
                ", type=" + type +
                '}';
    }

}