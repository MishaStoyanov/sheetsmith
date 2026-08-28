package com.ap0stole.sheetsmith.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint says who may call it, and this test is why that stays true.
 *
 * <p>The rules are {@code @PreAuthorize} on the handlers, which is the readable place for them —
 * beside the mapping, in the file somebody opens to see what the endpoint does. The weakness of
 * that arrangement is the one this application already lived through: an annotation is something a
 * person has to remember, and five endpoints had been added without one. Each was open to every
 * account on the instance, and nothing said so.
 *
 * <p>So the requirement is enforced rather than intended. This walks the handlers Spring actually
 * registered — not the source, not a list somebody maintains — and fails if one carries no rule.
 * The three ways in and the capability probe are named below as the only exceptions, with the
 * reason each has to answer before anybody is signed in.
 */
@SpringBootTest
@TestPropertySource(properties = "sheetsmith.auth.enabled=true")
@EnabledIf(value = "dockerAvailable", disabledReason = "Docker is not available")
class EndpointGuardTest {

    /**
     * The endpoints that must answer without a caller, and why.
     * <p>
     * Signing in has no token yet; refreshing runs precisely because the access token expired;
     * signing out must work when it has; and the capability probe is what the browser asks to find
     * out whether there is a login screen at all.
     */
    private static final Set<String> OPEN = Set.of(
            "/api/auth/login", "/api/auth/refresh", "/api/auth/logout", "/api/capabilities");

    // By name: actuator registers a second mapping of its own, and the application's endpoints are
    // the ones in this one.
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("no endpoint exists without a rule saying who may call it")
    void everyEndpointCarriesItsOwnRule() {
        Set<String> unguarded = new TreeSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            String path = pathOf(entry.getKey());
            if (!path.startsWith("/api/") || OPEN.contains(path)) {
                continue;
            }
            boolean guarded = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
            if (!guarded) {
                unguarded.add(path + "  (" + handler.getBeanType().getSimpleName()
                        + "." + handler.getMethod().getName() + ")");
            }
        }

        assertThat(unguarded)
                .as("""
                        These endpoints have no @PreAuthorize. Whatever they do, any signed-in
                        account can do it. Add the rule beside the mapping — and if the endpoint
                        really is open to everybody, say so with @authz.signedIn() rather than by
                        leaving it silent, so the next reader can tell the two apart.""")
                .isEmpty();
    }

    private String pathOf(RequestMappingInfo info) {
        List<String> patterns = info.getPathPatternsCondition() == null ? List.of()
                : info.getPathPatternsCondition().getPatternValues().stream().sorted().toList();
        return patterns.isEmpty() ? "" : patterns.getFirst();
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}
