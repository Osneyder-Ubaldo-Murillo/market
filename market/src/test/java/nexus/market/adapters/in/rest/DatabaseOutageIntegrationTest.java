package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;

import jakarta.persistence.PersistenceException;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.ports.out.ProductRepositoryPort;

/**
 * Con la pila HTTP completa (seguridad real, controlador, manejador global): un fallo de disponibilidad de la base de
 * datos es 503 en el formato de la API; una regla de negocio, una entrada inválida y un error inesperado conservan su
 * código; y al recuperarse la base de datos vuelven las respuestas normales.
 */
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:nexusmarket_outage;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class DatabaseOutageIntegrationTest extends RestTestSupport {

    @MockitoSpyBean
    private ProductRepositoryPort products;

    @AfterEach
    void restore() {
        reset(products);
    }

    /** Un fallo de conexión tal como lo ve un caso de uso (el repositorio no declara excepciones comprobadas). */
    private static RuntimeException connectionDown() {
        return new org.springframework.dao.DataAccessResourceFailureException("pool",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out"));
    }

    private MvcResult get(String path, String token) throws Exception {
        return perform(HttpMethod.GET, path, token, null);
    }

    private static String code(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    @Test
    void connectionFailure_is503_inApiFormat_withoutInternalDetails() throws Exception {
        doThrow(new PersistenceException("Unable to acquire JDBC Connection [jdbc:mysql://db-interna:3306/nexusmarket user=nexus]",
                new JDBCConnectionException("pool",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 30001ms"))))
                .when(products).findPublished();

        MvcResult result = get("/api/products", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(code(result)).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("5");
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("timestamp").doesNotContain("jdbc").doesNotContain("db-interna")
                .doesNotContain("Hikari").doesNotContain("nexus ").doesNotContain("PersistenceException");
    }

    @Test
    void recovery_restoresNormalResponses() throws Exception {
        doThrow(connectionDown()).when(products).findPublished();
        assertThat(get("/api/products", null).getResponse().getStatus()).isEqualTo(503);

        reset(products); // la base de datos "vuelve"
        assertThat(get("/api/products", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(get("/api/products", null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void businessErrors_keepTheirOwnStatus_notUnavailability() throws Exception {
        doThrow(new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente.")).when(products).findPublished();
        MvcResult result = get("/api/products", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(result)).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void validationErrors_stay400_evenIfTheDatabaseIsDown() throws Exception {
        doThrow(connectionDown()).when(products).findPublished();
        // Una entrada inválida se rechaza antes de tocar la base de datos.
        assertThat(status(HttpMethod.POST, "/api/auth/login", null, "{}")).isEqualTo(400);
        assertThat(status(HttpMethod.POST, "/api/auth/login", null, "{\"email\": ")).isEqualTo(400);
    }

    @Test
    void unexpectedProgrammingErrors_stay500_withAGenericBody() throws Exception {
        doThrow(new NullPointerException("detalle interno")).when(products).findPublished();
        MvcResult result = get("/api/products", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(code(result)).isEqualTo("INTERNAL_ERROR");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("detalle interno").doesNotContain("NullPointer");
    }

    @Test
    void dataIntegrityErrors_areNot503() throws Exception {
        doThrow(new PersistenceException("dup", new ConstraintViolationException("dup",
                new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062), "uk")))
                .when(products).findPublished();
        MvcResult result = get("/api/products", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(code(result)).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void theJwtFilterKeepsItsOwnBehavior_invalidTokenIs401_validTokenWorks_andAPublicRouteIgnoresABadToken() throws Exception {
        Account buyer = registerBuyer();
        // El filtro JWT no consulta la base de datos con un token inválido: sigue siendo 401 en una ruta protegida.
        assertThat(get("/api/users/" + buyer.userId(), "no-es-un-jwt").getResponse().getStatus()).isEqualTo(401);
        assertThat(get("/api/users/" + buyer.userId(), buyer.token()).getResponse().getStatus()).isEqualTo(200);
        // En una ruta pública un token basura se ignora y, con la base de datos caída, la ruta responde 503.
        doThrow(connectionDown()).when(products).findPublished();
        assertThat(get("/api/products", "no-es-un-jwt").getResponse().getStatus()).isEqualTo(503);
    }
}
