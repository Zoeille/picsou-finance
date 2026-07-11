package com.picsou.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.picsou.model.Account;
import com.picsou.model.AppUser;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static com.picsou.export.AccountsExporter.writeBigDecimal;
import static com.picsou.export.ProfileExporter.nullSafe;
import static com.picsou.export.ProfileExporter.writeInstant;

@Component
class TransactionsExporter implements EntityExporter {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    TransactionsExporter(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String name() {
        return "transactions";
    }

    @Override
    public List<String> csvHeader() {
        return List.of(
            "id", "account_id", "date", "description", "amount", "type", "category",
            "native_currency", "is_manual", "tx_type", "ticker", "quantity",
            "price_per_unit", "fees", "created_at"
        );
    }

    @Override
    public void writeCsv(AppUser user, ExportContext ctx, CsvWriter csv) throws IOException {
        for (Transaction t : transactions(user)) {
            csv.writeRow(List.of(
                String.valueOf(t.getId()),
                String.valueOf(t.getAccount().getId()),
                t.getDate() == null ? "" : t.getDate().toString(),
                nullSafe(t.getDescription()),
                t.getAmount() == null ? "" : t.getAmount().toPlainString(),
                nullSafe(t.getType()),
                nullSafe(t.getCategory()),
                nullSafe(t.getNativeCurrency()),
                String.valueOf(t.isManual()),
                nullSafe(t.getTxType() == null ? null : t.getTxType().name()),
                nullSafe(t.getTicker()),
                t.getQuantity() == null ? "" : t.getQuantity().toPlainString(),
                t.getPricePerUnit() == null ? "" : t.getPricePerUnit().toPlainString(),
                t.getFees() == null ? "" : t.getFees().toPlainString(),
                t.getCreatedAt() == null ? "" : t.getCreatedAt().toString()
            ));
        }
    }

    @Override
    public void writeJson(AppUser user, ExportContext ctx, JsonGenerator json) throws IOException {
        json.writeStartArray();
        for (Transaction t : transactions(user)) {
            json.writeStartObject();
            json.writeNumberField("id", t.getId());
            json.writeNumberField("account_id", t.getAccount().getId());
            json.writeStringField("date", t.getDate() == null ? null : t.getDate().toString());
            json.writeStringField("description", t.getDescription());
            writeBigDecimal(json, "amount", t.getAmount());
            json.writeStringField("type", t.getType());
            json.writeStringField("category", t.getCategory());
            json.writeStringField("native_currency", t.getNativeCurrency());
            json.writeBooleanField("is_manual", t.isManual());
            json.writeStringField("tx_type", t.getTxType() == null ? null : t.getTxType().name());
            json.writeStringField("ticker", t.getTicker());
            writeBigDecimal(json, "quantity", t.getQuantity());
            writeBigDecimal(json, "price_per_unit", t.getPricePerUnit());
            writeBigDecimal(json, "fees", t.getFees());
            writeInstant(json, "created_at", t.getCreatedAt());
            json.writeEndObject();
        }
        json.writeEndArray();
    }

    private List<Transaction> transactions(AppUser user) {
        Long memberId = user.getMember().getId();
        return accountRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .map(Account::getId)
            .flatMap(id -> transactionRepository.findByAccountIdOrderByDateDesc(id).stream())
            .toList();
    }
}
