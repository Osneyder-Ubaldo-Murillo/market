package nexus.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * La ubicación por defecto de Flyway ({@code classpath:db/migration}) contiene migraciones antiguas que no deben
 * ejecutarse nunca. Ningún perfil puede activar Flyway sin que {@code locations} apunte a {@code db/schema}, y la imagen
 * Docker no incluye esa carpeta. Las pruebas solo leen configuración: no abren bases de datos ni ejecutan migraciones.
 */
class MigrationTrapTest {

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (var in = new ClassPathResource(resource).getInputStream()) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    void everyProfile_pointsFlywayAtTheSchemaLocation_evenWhenFlywayIsEnabledWithoutMoreConfiguration() throws IOException {
        Properties base = load("application.properties");
        assertThat(base.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/schema");
        // Si un perfil sobrescribe la ubicación, solo puede ser con la misma carpeta.
        for (String resource : List.of("application-jpa.properties", "application-prod.properties")) {
            String locations = load(resource).getProperty("spring.flyway.locations");
            assertThat(locations).as(resource).isIn(null, "classpath:db/schema");
        }
    }

    @Test
    void flywayStaysDisabledOutsideProduction() throws IOException {
        assertThat(load("application.properties").getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(load("application-jpa.properties").getProperty("spring.flyway.enabled")).isNull();
    }

    @Test
    void dockerContext_excludesTheLegacyMigrations() throws IOException {
        Path dockerignore = Path.of("..", ".dockerignore");
        assertThat(dockerignore).exists();
        List<String> lines = Files.readAllLines(dockerignore, StandardCharsets.UTF_8).stream()
                .map(String::trim).toList();
        assertThat(lines).contains("market/src/main/resources/db/migration");
    }

    @Test
    void jar_excludesTheLegacyMigrations() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        assertThat(pom).contains("<exclude>db/migration/**</exclude>");
    }
}
