package nexus.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.Resource;

/**
 * La ubicación activa de Flyway en producción contiene solo el esquema derivado de las
 * entidades: sin semillas ni usuarios, sin las migraciones antiguas y sin baseline automático.
 */
class MigrationLocationTest {

    private static Properties prod() throws IOException {
        Properties p = new Properties();
        try (var in = new ClassPathResource("application-prod.properties").getInputStream()) {
            p.load(in);
        }
        return p;
    }

    @Test
    void productionUsesOnlyTheSchemaLocation_withoutAutomaticBaseline() throws IOException {
        Properties p = prod();
        assertThat(p.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(p.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/schema");
        assertThat(p.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
        assertThat(p.getProperty("spring.flyway.baseline-version")).isNull();
        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void activeLocation_hasNoSeedsOrUsers() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/schema/*.sql");
        assertThat(scripts).extracting(Resource::getFilename).containsExactlyInAnyOrder("V1__initial_schema.sql", "V2__user_email_verification.sql",
                "V3__order_payment_transaction.sql");
        for (Resource script : scripts) {
            String sql = new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
            assertThat(sql).doesNotContain("INSERT ").doesNotContain("$2A$").doesNotContain("PASSWORD123");
            assertThat(sql).doesNotContain("DROP ").doesNotContain("DELETE ").doesNotContain("TRUNCATE");
        }
        String v1 = new String(new ClassPathResource("db/schema/V1__initial_schema.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).toUpperCase();
        assertThat(v1).contains("CREATE TABLE `USER_CREDENTIALS`").contains("CREATE TABLE `INVENTORIES`");
    }

    @Test
    void activeMigrations_createNoAdministratorAndNoCredentials() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/schema/*");
        assertThat(scripts).hasSize(3);
        for (Resource script : scripts) {
            String sql = new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
            // Solo definiciones de tablas/columnas (y el UPDATE de V2 sobre fechas): ni filas, ni el rol ADMIN como dato,
            // ni hashes ni contraseñas.
            assertThat(sql).doesNotContain("INSERT").doesNotContain("'ADMIN'").doesNotContain("ADMIN@")
                    .doesNotContain("BCRYPT").doesNotContain("$2");
        }
    }
}
