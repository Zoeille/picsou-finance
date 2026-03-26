package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "account_type")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.NAMED_ENUM)
    private AccountType type;

    @Column(length = 100)
    private String provider;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "EUR";

    @Column(name = "current_balance", nullable = false, precision = 20, scale = 8)
    @Builder.Default
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "gocardless_account_id", length = 100)
    private String gocardlessAccountId;

    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private boolean isManual = true;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String color = "#6366f1";

    /** Ticker symbol for live price lookup, e.g. "BTC", "IWDA.AS" */
    @Column(length = 20)
    private String ticker;
}
