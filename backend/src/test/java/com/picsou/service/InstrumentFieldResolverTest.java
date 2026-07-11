package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentFieldResolverTest {

    @Mock OpenFigiIsinConverter openFigiIsinConverter;
    @InjectMocks InstrumentFieldResolver resolver;

    @Test
    void blankInput_returnsNull() {
        assertThat(resolver.resolve(null, null, TransactionType.BUY)).isNull();
        assertThat(resolver.resolve("   ", "X", TransactionType.SELL)).isNull();
        verify(openFigiIsinConverter, never()).resolve(any());
    }

    @Test
    void isin_resolvesTickerNameAndDescription() {
        when(openFigiIsinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "iShares Core MSCI World UCITS ETF"));

        InstrumentFieldResolver.ResolvedInstrument r =
            resolver.resolve("IE00B4L5Y983", null, TransactionType.BUY);

        // ISIN normalized to the Yahoo ticker so positions merge and pricing works.
        assertThat(r.ticker()).isEqualTo("IWDA.AS");
        assertThat(r.name()).isEqualTo("iShares Core MSCI World UCITS ETF");
        // The raw ISIN never surfaces in the description.
        assertThat(r.description()).isEqualTo("iShares Core MSCI World UCITS ETF");
    }

    @Test
    void plainTicker_uppercasedNoResolveAndAchatDescription() {
        InstrumentFieldResolver.ResolvedInstrument r =
            resolver.resolve("iwda.as", null, TransactionType.BUY);

        assertThat(r.ticker()).isEqualTo("IWDA.AS");
        assertThat(r.name()).isNull();
        assertThat(r.description()).isEqualTo("Achat IWDA.AS");
        verify(openFigiIsinConverter, never()).resolve(any());
    }

    @Test
    void userName_winsOverResolvedName() {
        when(openFigiIsinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "resolved name"));

        InstrumentFieldResolver.ResolvedInstrument r =
            resolver.resolve("IE00B4L5Y983", "My World ETF", TransactionType.BUY);

        assertThat(r.ticker()).isEqualTo("IWDA.AS");
        assertThat(r.name()).isEqualTo("My World ETF");
        assertThat(r.description()).isEqualTo("My World ETF");
    }

    @Test
    void sellSide_buildsVenteDescriptionWhenNoName() {
        InstrumentFieldResolver.ResolvedInstrument r =
            resolver.resolve("aapl", null, TransactionType.SELL);

        assertThat(r.description()).isEqualTo("Vente AAPL");
    }
}
