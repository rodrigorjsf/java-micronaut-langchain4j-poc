package io.github.rodrigorjsf.agenticchat.infra;

import io.github.rodrigorjsf.agenticchat.infra.config.AwsProperties;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AwsPropertiesTest {

    @Test
    void bindsDefaultsWhenNothingIsConfigured() {
        try (var ctx = ApplicationContext.run()) {
            var props = ctx.getBean(AwsProperties.class);
            assertThat(props.endpoint()).isEqualTo("http://localhost:4566");
            assertThat(props.region()).isEqualTo("us-east-1");
        }
    }

    @Test
    void environmentOverridesDefaults() {
        try (var ctx = ApplicationContext.run(Map.of(
                "agentic.aws.endpoint", "http://floci:4566",
                "agentic.aws.region", "sa-east-1"))) {
            var props = ctx.getBean(AwsProperties.class);
            assertThat(props.endpoint()).isEqualTo("http://floci:4566");
            assertThat(props.region()).isEqualTo("sa-east-1");
        }
    }
}
