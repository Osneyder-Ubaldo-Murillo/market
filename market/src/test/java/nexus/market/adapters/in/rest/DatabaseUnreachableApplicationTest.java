package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * Pila real, sin dobles: controlador + JPA + Hikari + controlador MySQL contra un puerto donde NO hay base de datos
 * (equivale a MySQL detenido). Hikari agota su tiempo de espera y la API debe responder 503 (no 500), de forma acotada
 * por {@code connection-timeout} también con peticiones concurrentes.
 */
@ActiveProfiles("jpa")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/nexusmarket?sslMode=REQUIRED&serverTimezone=UTC",
        "spring.datasource.username=nexus_test",
        "spring.datasource.password=no-es-un-secreto-real",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.datasource.hikari.connection-timeout=1500",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
        "spring.data.mongodb.uri=",
        "spring.data.mongodb.database=test_audit"
})
class DatabaseUnreachableApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    private MvcResult getProducts() throws Exception {
        return mockMvc.perform(get("/api/products")).andReturn();
    }

    @Test
    void withNoDatabaseReachable_aPublicRouteIs503_notAnInternalError() throws Exception {
        long start = System.nanoTime();
        MvcResult result = getProducts();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.code")).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("5");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("jdbc").doesNotContain("127.0.0.1")
                .doesNotContain("nexus_test").doesNotContain("no-es-un-secreto-real").doesNotContain("Hikari");
        assertThat(millis).as("espera acotada por connection-timeout (1,5 s), no 30 s").isLessThan(15_000);
    }

    @Test
    void concurrentRequests_allGet503_boundedByTheConnectionTimeout_notSerialized() throws Exception {
        int requests = 12; // más que el pool (5): las demás esperan su turno como máximo connection-timeout
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        long start = System.nanoTime();
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            Callable<Integer> call = () -> getProducts().getResponse().getStatus();
            futures.add(pool.submit(call));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        assertThat(statuses).allMatch(status -> status == 503);
        assertThat(seconds).as("12 peticiones simultáneas terminan en una espera, no en 12 esperas seriadas").isLessThan(20);
    }

    @Test
    void routesThatDoNotNeedTheDatabase_keepWorking() throws Exception {
        assertThat(mockMvc.perform(get("/api/api/products")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/no-existe")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
