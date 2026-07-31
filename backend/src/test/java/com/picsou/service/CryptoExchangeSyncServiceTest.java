package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.CryptoExchangePosition;
import com.picsou.model.CryptoExchangeSession;
import com.picsou.model.ExchangeType;
import com.picsou.model.FamilyMember;
import com.picsou.port.CryptoExchangePort;
import com.picsou.port.CryptoExchangePort.ExchangePosition;
import com.picsou.port.CryptoExchangePort.Product;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CryptoExchangePositionRepository;
import com.picsou.repository.CryptoExchangeSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the credential contract — which exchange needs which credentials, and what gets
 * persisted — plus the guarantee that a failed sync leaves the account untouched.
 *
 * <p>The credential rule lives on the adapter ({@code CryptoExchangePort.requiresApiSecret()})
 * because it varies per exchange: Binance signs each request with an HMAC over the secret, Meria
 * authenticates with a single read-only key. Bean validation cannot express that, so this service
 * is the enforcement point and these tests are what make it real.
 */
@ExtendWith(MockitoExtension.class)
class CryptoExchangeSyncServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final String KEY = "api-key";
    private static final String SECRET = "api-secret";

    @Mock CryptoExchangeSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock AccountService accountService;
    @Mock PriceService priceService;
    @Mock CryptoEncryption encryption;
    @Mock CryptoExchangeStatusWriter statusWriter;
    @Mock CryptoExchangePositionRepository positionRepository;

    private final CryptoExchangeSession[] saved = new CryptoExchangeSession[1];

    private CryptoExchangeSyncService serviceWith(CryptoExchangePort... adapters) {
        return new CryptoExchangeSyncService(
            List.of(adapters), sessionRepository, accountRepository,
            familyMemberRepository, accountService, priceService, encryption, statusWriter,
            positionRepository);
    }

    // Both fixtures are lenient: which of the two traits a given test exercises depends on how far
    // down addExchange it gets, and neither is the thing under assertion.

    /** An adapter that behaves like Binance: key + secret. */
    private CryptoExchangePort twoCredentialAdapter() {
        CryptoExchangePort adapter = mock(CryptoExchangePort.class);
        lenient().when(adapter.exchangeName()).thenReturn(ExchangeType.BINANCE.name());
        lenient().when(adapter.requiresApiSecret()).thenReturn(true);
        return adapter;
    }

    /** An adapter that behaves like Meria: a single read-only key. */
    private CryptoExchangePort singleKeyAdapter() {
        CryptoExchangePort adapter = mock(CryptoExchangePort.class);
        lenient().when(adapter.exchangeName()).thenReturn(ExchangeType.MERIA.name());
        lenient().when(adapter.requiresApiSecret()).thenReturn(false);
        return adapter;
    }

    // ── Credential validation, before any network call ────────────────────────

    @Test
    void addExchange_rejectsABlankApiKey() {
        CryptoExchangePort adapter = singleKeyAdapter();

        assertThatThrownBy(() -> serviceWith(adapter).addExchange(ExchangeType.MERIA, "   ", null, MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("API key");

        verify(adapter, never()).testConnection(any(), any());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void addExchange_rejectsAMissingSecretForAnExchangeThatNeedsOne() {
        CryptoExchangePort adapter = twoCredentialAdapter();

        assertThatThrownBy(() -> serviceWith(adapter).addExchange(ExchangeType.BINANCE, KEY, "", MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("API secret");

        // Rejected before spending a round-trip on credentials that cannot work.
        verify(adapter, never()).testConnection(any(), any());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void addExchange_rejectsAStraySecretForASingleKeyExchange() {
        // Not pedantry: accepting it would encrypt and store a secret that can never be used.
        CryptoExchangePort adapter = singleKeyAdapter();

        assertThatThrownBy(() -> serviceWith(adapter).addExchange(ExchangeType.MERIA, KEY, SECRET, MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("single read-only API key");

        verify(adapter, never()).testConnection(any(), any());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void addExchange_treatsABlankSecretAsAbsentForASingleKeyExchange() {
        // The shared form always sends a string, so Meria arrives with "" rather than no field.
        CryptoExchangePort adapter = singleKeyAdapter();
        when(adapter.testConnection(KEY, null)).thenReturn(false);

        assertThatThrownBy(() -> serviceWith(adapter).addExchange(ExchangeType.MERIA, KEY, "  ", MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Could not connect");
    }

    @Test
    void addExchange_storesANullSecretForASingleKeyExchange() {
        CryptoExchangePort adapter = singleKeyAdapter();
        when(adapter.testConnection(KEY, null)).thenReturn(true);
        when(adapter.fetchPositions(KEY, null)).thenReturn(List.of());
        arrangeSuccessfulSync(ExchangeType.MERIA);

        serviceWith(adapter).addExchange(ExchangeType.MERIA, "  " + KEY + " ", "", MEMBER_ID);

        ArgumentCaptor<CryptoExchangeSession> session = ArgumentCaptor.forClass(CryptoExchangeSession.class);
        verify(sessionRepository, org.mockito.Mockito.atLeastOnce()).save(session.capture());
        assertThat(session.getValue().getApiSecret()).isNull();
        assertThat(session.getValue().getApiKey()).isEqualTo("enc:" + KEY);   // trimmed, then encrypted
    }

    @Test
    void addExchange_stillRequiresBothCredentialsForATwoCredentialExchange() {
        // Regression guard for existing Binance users: the default is unchanged.
        CryptoExchangePort adapter = twoCredentialAdapter();
        when(adapter.testConnection(KEY, SECRET)).thenReturn(true);
        when(adapter.fetchPositions(KEY, SECRET)).thenReturn(List.of());
        arrangeSuccessfulSync(ExchangeType.BINANCE);

        serviceWith(adapter).addExchange(ExchangeType.BINANCE, KEY, SECRET, MEMBER_ID);

        ArgumentCaptor<CryptoExchangeSession> session = ArgumentCaptor.forClass(CryptoExchangeSession.class);
        verify(sessionRepository, org.mockito.Mockito.atLeastOnce()).save(session.capture());
        assertThat(session.getValue().getApiSecret()).isEqualTo("enc:" + SECRET);
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    @Test
    void sync_passesANullSecretToASingleKeyAdapter() {
        CryptoExchangePort adapter = singleKeyAdapter();
        CryptoExchangeSession session = session(ExchangeType.MERIA, "enc:" + KEY, null);
        when(sessionRepository.findByIdAndMemberId(7L, MEMBER_ID)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc:" + KEY)).thenReturn(KEY);
        when(encryption.decrypt(null)).thenReturn(null);
        when(adapter.fetchPositions(KEY, null)).thenReturn(List.of());
        arrangeAccountResolution();

        serviceWith(adapter).sync(7L, MEMBER_ID);

        verify(adapter).fetchPositions(KEY, null);
    }

    @Test
    void sync_storesThePerProductBreakdownAndSumsItPerAssetForTheHolding() {
        // ETH sits in spot and staked at once: two position lines, one holding of 1.5.
        CryptoExchangePort adapter = singleKeyAdapter();
        CryptoExchangeSession session = session(ExchangeType.MERIA, "enc:" + KEY, null);
        when(sessionRepository.findByIdAndMemberId(7L, MEMBER_ID)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc:" + KEY)).thenReturn(KEY);
        when(encryption.decrypt(null)).thenReturn(null);
        when(adapter.fetchPositions(KEY, null)).thenReturn(List.of(
            ExchangePosition.spot("ETH", new BigDecimal("0.5")),
            new ExchangePosition(Product.STAKING, "ETH", BigDecimal.ONE, new BigDecimal("0.9"),
                new BigDecimal("0.1"))));
        arrangeAccountResolution();
        when(priceService.refreshCryptoPrices(any())).thenReturn(Map.of("ETH", new BigDecimal("100")));

        serviceWith(adapter).sync(7L, MEMBER_ID);

        // One holding, summed across products — that is what values the account.
        verify(accountService).upsertHolding(any(), eq(MEMBER_ID), eq("ETH"), eq("ETH"),
            eq(new BigDecimal("1.5")), eq(new BigDecimal("100")));
        // ...and the breakdown replaced wholesale, so an unstaked product cannot linger.
        verify(positionRepository).deleteAllForAccount(any());
        ArgumentCaptor<List<CryptoExchangePosition>> stored = ArgumentCaptor.forClass(List.class);
        verify(positionRepository).saveAll(stored.capture());
        assertThat(stored.getValue())
            .extracting(CryptoExchangePosition::getProduct, CryptoExchangePosition::getTicker,
                CryptoExchangePosition::getInterest)
            .containsExactly(
                org.assertj.core.api.Assertions.tuple(Product.SPOT, "ETH", null),
                org.assertj.core.api.Assertions.tuple(Product.STAKING, "ETH", new BigDecimal("0.1")));
    }

    @Test
    void sync_marksTheSessionErrorAndLeavesTheAccountUntouchedWhenTheAdapterFails() {
        // The all-or-nothing contract seen from the service: a failed read must not reach
        // upsertHolding or upsertSnapshot, or it would write a shrunken balance into history.
        CryptoExchangePort adapter = singleKeyAdapter();
        CryptoExchangeSession session = session(ExchangeType.MERIA, "enc:" + KEY, null);
        when(sessionRepository.findByIdAndMemberId(7L, MEMBER_ID)).thenReturn(Optional.of(session));
        when(encryption.decrypt("enc:" + KEY)).thenReturn(KEY);
        when(encryption.decrypt(null)).thenReturn(null);
        when(adapter.fetchPositions(KEY, null)).thenThrow(new SyncException("Meria rejected /wallets"));

        assertThatThrownBy(() -> serviceWith(adapter).sync(7L, MEMBER_ID))
            .isInstanceOf(SyncException.class);

        // Through the REQUIRES_NEW writer, never a plain save: this method rethrows out of a
        // @Transactional boundary, so a save here would be rolled back and the session would
        // keep reading CONNECTED on the manual path while the scheduler reports ERROR.
        verify(statusWriter).markError(7L);
        verify(sessionRepository, never()).save(any());
        verifyNoInteractions(accountService);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void addExchange_failsClearlyForAnExchangeWithNoAdapter() {
        // KRAKEN is in the enum but has no adapter — a known gap, pinned here so it stays a
        // readable 422 rather than a NullPointerException.
        assertThatThrownBy(() -> serviceWith(singleKeyAdapter())
            .addExchange(ExchangeType.KRAKEN, KEY, SECRET, MEMBER_ID))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("isn't supported yet");
    }

    @Test
    void addExchange_rejectsAMissingExchangeType() {
        assertThatCode(() -> serviceWith(singleKeyAdapter()).addExchange(null, KEY, null, MEMBER_ID))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static CryptoExchangeSession session(ExchangeType type, String apiKey, String apiSecret) {
        return CryptoExchangeSession.builder()
            .id(7L).exchangeType(type).apiKey(apiKey).apiSecret(apiSecret).status("CONNECTED").build();
    }

    /** Everything addExchange needs to run all the way through its immediate sync. */
    private void arrangeSuccessfulSync(ExchangeType type) {
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mock(FamilyMember.class)));
        when(sessionRepository.findByExchangeTypeAndMemberId(type, MEMBER_ID)).thenReturn(Optional.empty());
        when(encryption.encrypt(any())).thenAnswer(call -> {
            String value = call.getArgument(0);
            return value == null ? null : "enc:" + value;
        });
        when(encryption.decrypt(any())).thenAnswer(call -> {
            String value = call.getArgument(0);
            return value == null ? null : value.substring("enc:".length());
        });
        when(sessionRepository.save(any())).thenAnswer(call -> {
            CryptoExchangeSession session = call.getArgument(0);
            if (session.getId() == null) session.setId(7L);
            saved[0] = session;
            return session;
        });
        when(sessionRepository.findByIdAndMemberId(eq(7L), eq(MEMBER_ID)))
            .thenAnswer(call -> Optional.ofNullable(saved[0]));
        arrangeAccountResolution();
    }

    private void arrangeAccountResolution() {
        when(priceService.refreshCryptoPrices(any())).thenReturn(Map.of());
        Account account = mock(Account.class);
        when(accountRepository.findByExternalAccountIdAndMemberId(any(), eq(MEMBER_ID)))
            .thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);
        when(accountService.upsertSnapshot(eq(account), any(BigDecimal.class), any())).thenReturn(null);
        when(accountService.toResponse(account)).thenReturn(null);
    }
}
