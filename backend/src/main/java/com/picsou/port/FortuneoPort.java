package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface FortuneoPort {
    InitiateResult initiateAuth(String login, String password);
    String completeAuth(String processId, String code);
    List<AccountData> fetchAccounts(String sessionState);

    record InitiateResult(String processId, boolean mfaRequired, String mfaType, String sessionState) {}
    record Position(String isin, String symbol, String label, BigDecimal quantity,
                    BigDecimal buyingPriceEur, BigDecimal currentPrice, String quoteCurrency,
                    BigDecimal currentValueEur, BigDecimal pnlEur) {}
    record Transaction(LocalDate date, String label, BigDecimal amount, String category) {}
    record AccountData(String externalId, String name, AccountType type, BigDecimal balanceEur,
                       BigDecimal cashBalance, List<Position> positions, List<Transaction> transactions,
                       boolean snapshotComplete) {}
}
