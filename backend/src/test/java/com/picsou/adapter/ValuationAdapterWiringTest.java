package com.picsou.adapter;

import com.picsou.port.GeocodingPort;
import com.picsou.port.HousingPriceIndexPort;
import com.picsou.port.PropertyValuationPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that Spring can actually construct the valuation adapters.
 *
 * <p><b>Why this exists.</b> Each adapter declares two constructors — a public one taking a
 * {@code @Value} base URL, and a package-private one taking a {@code WebClient} so tests can
 * inject a stubbed exchange function. Unit tests call a constructor directly, so they pass
 * either way; the container cannot choose between two candidates and fails with <em>"No
 * default constructor found"</em>. That shipped once and crash-looped the application, with
 * every test green.
 *
 * <p>A real (if tiny) Spring context is the only thing that reproduces it. This one registers
 * just the adapters, so it runs in milliseconds and needs no database — unlike the full
 * application context, which cannot currently be booted in a test because
 * {@code HstsSliceTestApplication} sits in a component-scanned package and globally excludes
 * {@code DataSourceAutoConfiguration}.
 */
class ValuationAdapterWiringTest {

    @Test
    void springCanInstantiateEveryValuationAdapter() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                CeremaDv3fValuationProvider.class,
                GeoplateformeGeocoder.class,
                InseeBdmIndexProvider.class);
            // No property source registered on purpose: each @Value carries a default, so a
            // stripped configuration must still boot. A missing default would fail here.
            context.refresh();

            assertThat(context.getBean(PropertyValuationPort.class)).isNotNull();
            assertThat(context.getBean(GeocodingPort.class)).isNotNull();
            assertThat(context.getBean(HousingPriceIndexPort.class)).isNotNull();
        }
    }

    @Test
    void adaptersExposeExactlyOneInjectableConstructor() {
        // The failure mode is silent at compile time and only appears when a container tries
        // to build the bean, so it is asserted structurally as well.
        for (Class<?> adapter : new Class<?>[]{
            CeremaDv3fValuationProvider.class, GeoplateformeGeocoder.class, InseeBdmIndexProvider.class}) {

            long injectable = java.util.Arrays.stream(adapter.getDeclaredConstructors())
                .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                .count();

            assertThat(injectable)
                .as("%s must mark exactly one constructor @Autowired; it declares %d constructors",
                    adapter.getSimpleName(), adapter.getDeclaredConstructors().length)
                .isEqualTo(1);
        }
    }
}
