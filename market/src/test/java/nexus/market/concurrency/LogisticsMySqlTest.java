package nexus.market.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nexus.market.adapters.in.rest.RestTestSupport;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Gestión logística contra MySQL 8 REAL (InnoDB), con el esquema de Flyway (incluida V5) y {@code ddl-auto=validate}: las
 * búsquedas con filtros del operador y del supervisor (subconsultas de recogidas y novedades), el código único del pedido y la
 * carrera de recogidas sobre el mismo pedido, que el bloqueo de fila serializa.
 *
 * <p>Solo se ejecutan con un MySQL <b>desechable</b> ({@code NEXUS_TEST_MYSQL_URL/USER/PASSWORD}); sin él se omiten.
 * Nunca apuntarlas a una base con datos: crean y modifican filas.</p>
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_MYSQL_URL", matches = ".+")
@TestPropertySource(properties = {
        "spring.datasource.url=${NEXUS_TEST_MYSQL_URL}",
        "spring.datasource.username=${NEXUS_TEST_MYSQL_USER}",
        "spring.datasource.password=${NEXUS_TEST_MYSQL_PASSWORD}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/schema",
        "spring.datasource.hikari.maximum-pool-size=30",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=15"
})
class LogisticsMySqlTest extends RestTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode get(String path, String token) throws Exception {
        return JSON.readTree(send(HttpMethod.GET, path, token, null, 200));
    }

    @Test
    void filtersCodesAndIncidents_workOnMySql() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        String supervisor = staffToken(SystemRole.SUPERVISOR);
        SellerCatalog a = seedSellerCatalog(admin, new BigDecimal("50000"));
        SellerCatalog b = seedSellerCatalog(admin, new BigDecimal("30000"));
        Account buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", a.productId(), "quantity", 2), 200);
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", b.productId(), "quantity", 3), 200);
        JsonNode order = JSON.readTree(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders",
                buyer.token(), body("shippingAddress", addressJson()), 201));
        String orderId = order.get("orderId").asText();
        String code = order.get("orderCode").asText();
        assertThat(code).matches("NM-PED-[0-9]{4}-[A-Z0-9]{6}");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);

        String operatorId = jwt.getUserId(operator).orElseThrow().value();
        send(HttpMethod.PUT, "/api/logistics/orders/" + orderId + "/operator", admin, body("operator", operatorId), 200);
        send(HttpMethod.POST, "/api/logistics/orders/" + code + "/pickups", operator,
                body("productId", a.productId(), "quantity", 2), 200);

        // Recogida pendiente (sin novedades todavía).
        assertThat(get("/api/supervision/orders?pickupsPending=true&withIncidents=false&q=" + code, supervisor)).hasSize(1);
        assertThat(get("/api/supervision/orders?pickupsPending=false&q=" + code, supervisor)).isEmpty();
        assertThat(get("/api/supervision/orders?unassigned=true&q=" + code, supervisor)).isEmpty();
        assertThat(get("/api/supervision/orders?operator=" + operatorId + "&status=PAID&from=2020-01-01&to=2099-12-31&q="
                + code, supervisor)).hasSize(1);
        assertThat(get("/api/logistics/orders?q=" + code, operator)).hasSize(1);

        String incidentId = JSON.readTree(send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/incidents",
                operator, body("type", "MISSING_PRODUCT", "description", "Falta el mouse"), 201)).get("incidentId").asText();
        assertThat(get("/api/supervision/orders?withIncidents=true&q=" + code, supervisor)).hasSize(1);
        assertThat(get("/api/supervision/incidents?status=OPEN", supervisor)).isNotEmpty();
        send(HttpMethod.POST, "/api/supervision/incidents/" + incidentId + "/resolve", supervisor,
                body("notes", "Resuelto"), 200);
        assertThat(get("/api/supervision/orders?withIncidents=true&q=" + code, supervisor)).isEmpty();

        send(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/pickups", operator,
                body("productId", b.productId(), "quantity", 3), 200);
        assertThat(get("/api/supervision/orders?pickupsPending=false&q=" + code, supervisor)).hasSize(1);
        assertThat(get("/api/supervision/orders/" + code, supervisor).get("timeline")).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void simultaneousPickups_neverExceedWhatWasRequested() throws Exception {
        String admin = staffToken(SystemRole.ADMIN);
        String operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        SellerCatalog item = seedSellerCatalog(admin, new BigDecimal("1000"));
        Account buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", item.productId(), "quantity", 5), 200);
        String orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);
        send(HttpMethod.PUT, "/api/logistics/orders/" + orderId + "/operator", admin,
                body("operator", jwt.getUserId(operator).orElseThrow().value()), 200);

        // Cuatro operarios intentan recoger 2 unidades a la vez de un pedido de 5: solo caben dos intentos.
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return status(HttpMethod.POST, "/api/logistics/orders/" + orderId + "/pickups", operator,
                            body("productId", item.productId(), "quantity", 2));
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<Integer> result : results) {
                int code = result.get(90, TimeUnit.SECONDS);
                assertThat(code).as("nunca un error de servidor").isIn(200, 409);
                if (code == 200) {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        JsonNode detail = get("/api/logistics/orders/" + orderId, operator);
        assertThat(detail.at("/lines/0/picked").asInt()).isEqualTo(4);
        assertThat(detail.at("/lines/0/pending").asInt()).isEqualTo(1);
    }
}
