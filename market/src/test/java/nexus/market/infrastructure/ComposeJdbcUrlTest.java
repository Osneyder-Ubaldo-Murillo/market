package nexus.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.mysql.cj.conf.ConnectionUrl;

/**
 * La URL JDBC que Docker Compose entrega a la aplicación. Con MySQL 8 (usuario con caching_sha2_password) la URL
 * antigua {@code useSSL=false} impedía arrancar ("Public Key Retrieval is not allowed"). Se exige TLS con
 * {@code sslMode=REQUIRED} (cifra; NO valida la identidad del certificado) y no se depende de
 * {@code allowPublicKeyRetrieval=true}.
 */
class ComposeJdbcUrlTest {

    /** Raíz del repositorio: el proyecto Maven vive en market/ dentro de ella. */
    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();

    private static String read(String relative) throws IOException {
        Path file = REPOSITORY_ROOT.resolve(relative);
        assertThat(file).as("archivo del repositorio: " + file).exists();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Valor de SPRING_DATASOURCE_URL en el servicio app (primera línea no comentada). */
    private static String composeDatasourceUrl() throws IOException {
        Matcher matcher = Pattern.compile("(?m)^\\s*SPRING_DATASOURCE_URL:\\s*(\\S+)\\s*$").matcher(read("docker-compose.yml"));
        assertThat(matcher.find()).as("SPRING_DATASOURCE_URL en docker-compose.yml").isTrue();
        return matcher.group(1);
    }

    @Test
    void composeUrl_requiresTls_withoutPublicKeyRetrievalOrPlaintext() throws IOException {
        String raw = composeDatasourceUrl();
        // Resolución de la interpolación de Compose: ${MYSQL_DATABASE:-nexusmarket} -> nexusmarket.
        String resolved = raw.replaceAll("\\$\\{MYSQL_DATABASE:-([A-Za-z0-9_]+)}", "$1");

        assertThat(resolved).matches("^jdbc:mysql://mysql:3306/[A-Za-z0-9_]+\\?sslMode=REQUIRED&serverTimezone=UTC$");
        assertThat(resolved.toLowerCase()).doesNotContain("usessl").doesNotContain("allowpublickeyretrieval");
    }

    @Test
    void theConnectorParsesTheUrl_asTlsRequired_withoutAnyOfTheForbiddenOptions() throws IOException {
        String resolved = composeDatasourceUrl().replaceAll("\\$\\{MYSQL_DATABASE:-([A-Za-z0-9_]+)}", "$1");
        ConnectionUrl url = ConnectionUrl.getConnectionUrlInstance(resolved, new Properties());

        assertThat(url.getType()).isEqualTo(ConnectionUrl.Type.SINGLE_CONNECTION);
        assertThat(url.getMainHost().getHost()).isEqualTo("mysql");
        assertThat(url.getMainHost().getPort()).isEqualTo(3306);
        assertThat(url.getMainHost().getDatabase()).isEqualTo("nexusmarket");
        assertThat(url.getOriginalProperties()).containsEntry("sslMode", "REQUIRED").containsEntry("serverTimezone", "UTC")
                .doesNotContainKeys("useSSL", "allowPublicKeyRetrieval");
    }

    @Test
    void noDeploymentFile_reintroducesPlaintextOrPublicKeyRetrieval() throws IOException {
        // Archivos de despliegue/producción (el perfil de desarrollo local "jpa" queda fuera de este control).
        for (String file : List.of("docker-compose.yml", "Dockerfile", ".env.example",
                "market/src/main/resources/application-prod.properties")) {
            String content = read(file).toLowerCase();
            assertThat(content).as(file).doesNotContain("usessl=false").doesNotContain("allowpublickeyretrieval=true");
        }
    }
}
