package com.ap0stole.sheetsmith.configs;

import com.ap0stole.sheetsmith.domain.dto.CapabilitiesDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The switch that gives every later endpoint two behaviours instead of one.
 * <p>
 * Its default is the load-bearing part: someone running this alone must keep exactly the app they
 * had, so "off unless asked for" is asserted rather than assumed. Getting that backwards would put
 * a login screen in front of every existing single-user instance on upgrade.
 */
class AuthConfigTest {

    @Configuration
    @EnableConfigurationProperties(AuthConfig.class)
    static class Slice {
    }

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(Slice.class);

    @Test
    @DisplayName("authentication is off unless it is asked for")
    void offByDefault() {
        contexts.run(context -> assertThat(context.getBean(AuthConfig.class).isEnabled())
                .as("an upgrade must not put a login screen in front of a solo instance")
                .isFalse());
    }

    @Test
    @DisplayName("one property turns it on")
    void theSwitchWorks() {
        contexts.withPropertyValues("sheetsmith.auth.enabled=true")
                .run(context -> assertThat(context.getBean(AuthConfig.class).isEnabled()).isTrue());
    }

    @Test
    @DisplayName("the UI is told, because it cannot guess whether to offer a login")
    void capabilitiesCarryTheFlag() {
        assertThat(CapabilitiesDto.of(true, true).authEnabled()).isTrue();
        assertThat(CapabilitiesDto.of(true, false).authEnabled()).isFalse();
    }

    @Test
    @DisplayName("turning authentication on does not touch what the chat may send")
    void theTwoSwitchesAreIndependent() {
        // Two separate promises: one about who may drive the instance, one about what leaves it.
        // A privacy-conscious deployment must be able to add a login without quietly re-enabling
        // the chat, and vice versa.
        CapabilitiesDto locked = CapabilitiesDto.of(false, true);

        assertThat(locked.authEnabled()).isTrue();
        assertThat(locked.chatEnabled()).isFalse();
        assertThat(locked.sendsOnlyStructure()).isTrue();
    }
}
