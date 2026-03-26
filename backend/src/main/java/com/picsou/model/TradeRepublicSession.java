package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "trade_republic_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradeRepublicSession extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_token", nullable = false, length = 1000)
    private String sessionToken;

    /** Refresh token — valid ~2h, used to obtain a new session token without 2FA. */
    @Column(name = "refresh_token", length = 2000)
    private String refreshToken;

    /** Set to now + 2h based on observed refresh token expiry. */
    @Column(name = "expires_at")
    private Instant expiresAt;
}
