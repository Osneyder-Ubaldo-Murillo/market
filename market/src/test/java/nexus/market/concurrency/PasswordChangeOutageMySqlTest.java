package nexus.market.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import nexus.market.adapters.in.rest.RestTestSupport;
import nexus.market.application.CredentialStorePort;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Indisponibilidad y recuperación de MySQL con la pila REAL (Hikari + driver + MySQL 8) para la rotación de contraseña.
 * La aplicación se conecta a través de un proxy TCP ({@link TcpProxy}) que se cierra y se reabre en el mismo puerto: para
 * el pool equivale a detener y volver a arrancar MySQL, sin reiniciar la aplicación. {@code connection-timeout} =
 * 10 000 ms, el valor de producción. Se omite sin {@code NEXUS_TEST_MYSQL_URL}.
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_MYSQL_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${nexus.test.proxy.url}",
        "spring.datasource.username=${NEXUS_TEST_MYSQL_USER}",
        "spring.datasource.password=${NEXUS_TEST_MYSQL_PASSWORD}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/schema",
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.connection-timeout=10000",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=10"
})
class PasswordChangeOutageMySqlTest extends RestTestSupport {

    private static final TcpProxy PROXY = startProxy();

    /** Apunta la URL de la prueba a un proxy local que reenvía al MySQL desechable indicado por el entorno. */
    private static TcpProxy startProxy() {
        String url = System.getenv("NEXUS_TEST_MYSQL_URL");
        if (url == null || url.isBlank()) {
            return null; // la clase se omite (EnabledIfEnvironmentVariable)
        }
        Matcher matcher = Pattern.compile("jdbc:mysql://([^:/]+):(\\d+)/(.*)").matcher(url);
        if (!matcher.matches()) {
            throw new IllegalStateException("NEXUS_TEST_MYSQL_URL no tiene el formato jdbc:mysql://host:puerto/base");
        }
        try {
            TcpProxy proxy = new TcpProxy(matcher.group(1), Integer.parseInt(matcher.group(2)));
            System.setProperty("nexus.test.proxy.url", "jdbc:mysql://127.0.0.1:" + proxy.port() + "/" + matcher.group(3));
            return proxy;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private CredentialStorePort credentials;
    @Autowired
    private PasswordServicePort passwords;

    @AfterEach
    void databaseBackUp() throws Exception {
        if (PROXY != null) {
            PROXY.start();
        }
    }

    private record Admin(String id, String password, String token) {
    }

    private static String strong() {
        return UUID.randomUUID().toString().replace("-", "") + "Qz#x";
    }

    private Admin newAdmin() throws Exception {
        String password = strong();
        User admin = users.save(User.create(FullName.of("Admin Caida"), Email.of("adm-" + uniq() + "@tienda.example"),
                DocumentId.of("ADM-" + uniq()), SystemRole.ADMIN));
        credentials.save(admin.getUserId(), passwords.hash(password));
        String logged = send(HttpMethod.POST, "/api/auth/login", null,
                body("email", admin.getEmail().value(), "password", password), 200);
        return new Admin(admin.getUserId().value(), password, JsonPath.read(logged, "$.token"));
    }

    private MvcResult change(String token, String current, String next) throws Exception {
        return perform(HttpMethod.POST, "/api/auth/password", token, body("currentPassword", current, "newPassword", next));
    }

    private void waitUntilProductsRespond200() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            if (status(HttpMethod.GET, "/api/products", null, null) == 200) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("La aplicación no se recuperó en 90 s tras restablecer MySQL");
    }

    @Test
    void rotationOnRealMySql_invalidatesTheOldJwt_andTheNewTokenWorks() throws Exception {
        Admin admin = newAdmin();
        String next = strong();
        assertThat(status(HttpMethod.GET, "/api/users/" + admin.id(), admin.token(), null)).isEqualTo(200);

        MvcResult result = change(admin.token(), admin.password(), next);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String newToken = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        assertThat(status(HttpMethod.GET, "/api/users/" + admin.id(), admin.token(), null)).as("token anterior").isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/users/" + admin.id(), newToken, null)).as("token nuevo").isEqualTo(200);
        assertThat(status(HttpMethod.POST, "/api/auth/login", null,
                body("email", users.findById(nexus.market.domain.valueobjects.UserId.of(admin.id())).orElseThrow()
                        .getEmail().value(), "password", next))).isEqualTo(200);
    }

    @Test
    void mysqlDown_is503_andAfterItReturnsTheSameApplicationRecoversWithoutRestart() throws Exception {
        Admin admin = newAdmin();
        assertThat(status(HttpMethod.GET, "/api/products", null, null)).isEqualTo(200);

        PROXY.stop(); // MySQL "detenido": conexiones cortadas y puerto cerrado

        long start = System.nanoTime();
        MvcResult duringOutage = change(admin.token(), admin.password(), strong());
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        assertThat(duringOutage.getResponse().getStatus()).isEqualTo(503);
        assertThat((String) JsonPath.read(duringOutage.getResponse().getContentAsString(), "$.code")).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(duringOutage.getResponse().getContentAsString()).doesNotContain("jdbc").doesNotContain("Hikari")
                .doesNotContain(admin.password());
        assertThat(seconds).as("acotado por el connection-timeout de 10 s").isLessThan(30);
        assertThat(status(HttpMethod.GET, "/api/products", null, null)).as("ruta pública con MySQL caído").isEqualTo(503);
        assertThat(status(HttpMethod.GET, "/api/api/products", null, null)).as("ruta sin base de datos").isEqualTo(401);

        PROXY.start(); // MySQL "vuelve"; la aplicación NO se reinicia
        waitUntilProductsRespond200();

        String next = strong();
        MvcResult afterRecovery = change(admin.token(), admin.password(), next);
        assertThat(afterRecovery.getResponse().getStatus()).as("el mismo token y la misma contraseña actual siguen valiendo").isEqualTo(200);
        String newToken = JsonPath.read(afterRecovery.getResponse().getContentAsString(), "$.token");
        assertThat(status(HttpMethod.GET, "/api/users/" + admin.id(), admin.token(), null)).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/users/" + admin.id(), newToken, null)).isEqualTo(200);
    }
}
