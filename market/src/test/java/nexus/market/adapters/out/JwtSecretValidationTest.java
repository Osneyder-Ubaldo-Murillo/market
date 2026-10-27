package nexus.market.adapters.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import nexus.market.adapters.out.security.JwtJwtServicePort;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * El secreto JWT es obligatorio y no tiene respaldo en producción: se rechazan
 * ausente, vacío, corto, de ejemplo o predecible; un secreto aleatorio válido se acepta.
 */
class JwtSecretValidationTest {

    private static final String DEV_SECRET = "nexusmarket-dev-secret-change-me-in-production-0123456789abcdef";

    private static String randomSecret() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static JwtJwtServicePort inProduction(String secret) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("jpa", "prod");
        return new JwtJwtServicePort(secret, "nexusmarket", 60_000L, env);
    }

    @Test
    void production_rejectsMissingEmptyShortAndExampleSecrets() {
        for (String weak : new String[] { null, "", "   ", "corto-123", "a".repeat(40),
                "change-me-in-production-min-16-chars", DEV_SECRET,
                "tu_secreto_muy_seguro_minimo_16_caracteres_0123", "MiPassword-Largo-Pero-Predecible-12345" }) {
            assertThatThrownBy(() -> inProduction(weak)).as("secreto: %s", weak)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(DEV_SECRET);
        }
    }

    @Test
    void production_acceptsRandomSecret_andSignsVerifiableTokens() {
        JwtJwtServicePort service = inProduction(randomSecret());
        UserId id = UserId.generate();
        String token = service.issue(id, SystemRole.BUYER);
        assertThat(service.isValid(token)).isTrue();
        assertThat(service.getUserId(token)).contains(id);
    }

    @Test
    void secretLength_isMeasuredInUtf8Bytes() {
        // 31 bytes: rechazado incluso fuera de producción.
        assertThatThrownBy(() -> new JwtJwtServicePort("a".repeat(31), "nexusmarket", 1000L))
                .isInstanceOf(IllegalStateException.class);
        // 16 caracteres de 2 bytes = 32 bytes: aceptado.
        assertThat(new JwtJwtServicePort("ñ".repeat(16), "nexusmarket", 1000L)).isNotNull();
    }

    @Test
    void development_keepsItsOwnSecret() {
        assertThat(new JwtJwtServicePort(DEV_SECRET, "nexusmarket", 1000L)).isNotNull();
    }

    @Configuration
    static class JwtOnly {
        @Bean
        JwtServicePort jwt(org.springframework.core.env.Environment env) {
            return new JwtJwtServicePort(env.getRequiredProperty("jwt.secret"), "nexusmarket", 60_000L, env);
        }
    }

    private ApplicationContextRunner prodContext() {
        // Carga application.properties + application-prod.properties reales.
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(JwtOnly.class)
                .withPropertyValues("spring.profiles.active=prod");
    }

    @Test
    void productionContext_doesNotStartWithoutJwtSecret() {
        prodContext().run(context -> {
            assertThat(context).hasFailed();
            // No debe haber caído al secreto de desarrollo ni a un valor de respaldo.
            assertThat(context.getStartupFailure()).hasStackTraceContaining("JWT_SECRET");
        });
    }

    @Test
    void productionContext_doesNotStartWithWeakOrExampleSecret() {
        prodContext().withPropertyValues("JWT_SECRET=change-me-in-production-min-16-chars")
                .run(context -> assertThat(context).hasFailed());
        prodContext().withPropertyValues("JWT_SECRET=").run(context -> assertThat(context).hasFailed());
        prodContext().withPropertyValues("JWT_SECRET=corto").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionContext_startsWithValidSecret() {
        prodContext().withPropertyValues("JWT_SECRET=" + randomSecret())
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JwtServicePort.class));
    }
}
