package com.moxu.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationBindingTest {
    @Test
    void bindsCanonicalMoxuPropertiesWithoutStartupFailure() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(MoxuConfig.class, RateLimitProperties.class, BindingsProperties.class)
                .withPropertyValues("moxu.data-dir=/tmp/moxu-binding-test", "moxu.rate-limit.requests-per-minute=42")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MoxuConfig.class).getDataDir()).isEqualTo("/tmp/moxu-binding-test");
                    assertThat(context.getBean(RateLimitProperties.class).getRequestsPerMinute()).isEqualTo(42);
                    assertThat(context).hasSingleBean(BindingsProperties.class);
                });
    }
}
