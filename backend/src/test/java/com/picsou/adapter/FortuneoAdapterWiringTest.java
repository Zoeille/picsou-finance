package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FortuneoAdapterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues("app.fortuneo-auth.url=http://fortuneo-auth:8001")
        .withBean(ObjectMapper.class)
        .withBean(FortuneoAdapter.class);

    @Test
    void springSelectsTheProductionConstructor() {
        contextRunner.run(context -> assertThat(context)
            .hasNotFailed()
            .hasSingleBean(FortuneoAdapter.class));
    }
}
