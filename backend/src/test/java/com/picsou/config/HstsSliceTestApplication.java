package com.picsou.config;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * Minimal {@code @SpringBootConfiguration} for the web slice in this package.
 *
 * <p>{@code @WebMvcTest} searches upward from the test's package for a
 * {@code @SpringBootConfiguration} and would otherwise find {@link com.picsou.PicsouApplication},
 * which carries {@code @EnableJpaAuditing}. A web slice loads no entities, so JPA auditing fails
 * with "JPA metamodel must not be empty". Declaring this class in the same package as the test
 * makes it win the search, keeping the slice free of persistence concerns.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class
})
class HstsSliceTestApplication {
}
