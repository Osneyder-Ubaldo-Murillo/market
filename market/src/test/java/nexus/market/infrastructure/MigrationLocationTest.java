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
        assertThat(scripts).extracting(Resource::getFilename).containsExactly("V1__initial_schema.sql");
        for (Resource script : scripts) {
            String sql = new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
            assertThat(sql).doesNotContain("INSERT ").doesNotContain("$2A$").doesNotContain("PASSWORD123");
            assertThat(sql).contains("CREATE TABLE `USER_CREDENTIALS`").contains("CREATE TABLE `INVENTORIES`");
        }
    }

    @Test
    void activeMigrations_createNoAdministratorAndNoCredentials() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/schema/*");
        assertThat(scripts).hasSize(1);
        String sql = new String(scripts[0].getInputStream().readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
        // Solo definiciones de tablas: ni filas, ni el rol ADMIN como dato, ni hashes ni contraseñas.
        assertThat(sql).doesNotContain("INSERT").doesNotContain("'ADMIN'").doesNotContain("ADMIN@")
                .doesNotContain("BCRYPT").doesNotContain("$2");
    }
}
