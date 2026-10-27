package nexus.market.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Producción exige credenciales de base de datos explícitas: sin valores por defecto en la
 * configuración y sin contraseñas de ejemplo, vengan de donde vengan.
 */
class ProductionDatabaseCredentialsGuardTest {

    private static final String URL = "jdbc:mysql://db.internal:3306/nexusmarket";

    @Test
    void productionProperties_haveNoDefaultsForDatabaseCredentials() throws IOException {
        Properties prod = new Properties();
        try (var in = new ClassPathResource("application-prod.properties").getInputStream()) {
            prod.load(in);
        }
        assertThat(prod.getProperty("spring.datasource.url")).isEqualTo("${SPRING_DATASOURCE_URL}");
        assertThat(prod.getProperty("spring.datasource.username")).isEqualTo("${SPRING_DATASOURCE_USERNAME}");
        assertThat(prod.getProperty("spring.datasource.password")).isEqualTo("${SPRING_DATASOURCE_PASSWORD}");
        assertThat(prod.getProperty("spring.mongodb.uri")).doesNotContain("localhost");
    }

    @Test
    void missingOrBlankValues_areRejected_namingTheVariableButNeverAValue() {
        assertThatThrownBy(() -> ProductionDatabaseCredentialsGuard.validate(null, "app", "x".repeat(20)))
                .hasMessageContaining("SPRING_DATASOURCE_URL");
        assertThatThrownBy(() -> ProductionDatabaseCredentialsGuard.validate(URL, " ", "x".repeat(20)))
                .hasMessageContaining("SPRING_DATASOURCE_USERNAME");
        assertThatThrownBy(() -> ProductionDatabaseCredentialsGuard.validate(URL, "app", ""))
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD");
    }

    @Test
    void unresolvedPlaceholders_areRejected() {
        assertThatThrownBy(() -> ProductionDatabaseCredentialsGuard.validate("${SPRING_DATASOURCE_URL}", "app", "x".repeat(20)))
                .hasMessageContaining("SPRING_DATASOURCE_URL");
    }

    @Test
    void exampleOrDevPasswords_areRejected_withoutEchoingThem() {
        for (String weak : new String[] { "root", "nexus", "Password", "admin", "changeme" }) {
            assertThatThrownBy(() -> ProductionDatabaseCredentialsGuard.validate(URL, "app", weak))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(weak.toLowerCase());
        }
        assertThatThrownBy(() -> ProductionDatabaseCredentialsGuard.validate(URL, "service-account", "Service-Account"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void explicitRandomCredentials_areAccepted_andGuardReadsTheEnvironment() {
        String password = UUID.randomUUID().toString();
        assertThatCode(() -> ProductionDatabaseCredentialsGuard.validate(URL, "app", password))
                .doesNotThrowAnyException();

        MockEnvironment devLeak = new MockEnvironment() // p. ej. "prod,jpa": gana el perfil jpa
                .withProperty("spring.datasource.url", URL)
                .withProperty("spring.datasource.username", "root")
                .withProperty("spring.datasource.password", "root");
        assertThatThrownBy(() -> ProductionDatabaseCredentialsGuard.validate(devLeak))
                .isInstanceOf(IllegalStateException.class);
        MockEnvironment ok = new MockEnvironment()
                .withProperty("spring.datasource.url", URL)
                .withProperty("spring.datasource.username", "app")
                .withProperty("spring.datasource.password", password);
        assertThatCode(() -> ProductionDatabaseCredentialsGuard.validate(ok)).doesNotThrowAnyException();
    }
}
