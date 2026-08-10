package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account")
@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

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

    /** Cash held inside an investment envelope (PEA/CTO); null for other providers. */
    @Column(name = "cash_balance", precision = 20, scale = 8)
    private BigDecimal cashBalance;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "external_account_id", length = 100)
    private String externalAccountId;

    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private boolean isManual = true;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String color = "#6366f1";

    /** Ticker symbol for live price lookup, e.g. "BTC", "IWDA.AS" */
    @Column(length = 20)
    private String ticker;

    /** Bank logo URL, captured from Enable Banking institution search. Null falls back to {@link #color}. */
    @Column(name = "logo_url", columnDefinition = "TEXT")
    private String logoUrl;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
