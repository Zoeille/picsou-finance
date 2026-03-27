package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "crypto_exchange_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CryptoExchangeSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "exchange_type", nullable = false, length = 20)
    private ExchangeType exchangeType;

    @Column(name = "api_key", nullable = false, length = 200)
    private String apiKey;

    @Column(name = "api_secret", nullable = false, length = 500)
    private String apiSecret;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "CONNECTED";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
