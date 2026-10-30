package nexus.market.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import nexus.market.adapters.in.rest.RestTestSupport;
import nexus.market.domain.ports.out.PaymentServicePort;
import nexus.market.domain.ports.out.RefundRepositoryPort;
import nexus.market.domain.ports.out.ReturnRepositoryPort;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.RefundId;
import nexus.market.domain.valueobjects.RefundStatus;
import nexus.market.domain.valueobjects.ReturnId;
import nexus.market.domain.valueobjects.ReturnStatus;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Carreras de devoluciones y reembolsos contra MySQL 8 REAL (InnoDB), con el esquema de Flyway y
 * {@code ddl-auto=validate}. Comprueban que las operaciones sobre la misma devolución o el mismo reembolso se serializan
 * por el bloqueo de fila: un solo reembolso, una sola llamada a la pasarela, un solo resultado ganador, nunca un 500.
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
class ReturnRefundConcurrencyMySqlTest extends RestTestSupport {

    private static final int THREADS = 4;

    @MockitoSpyBean
    private PaymentServicePort payments;

    @Autowired
    private RefundRepositoryPort refunds;

    @Autowired
    private ReturnRepositoryPort returns;

    private String admin;
    private String operator;
    private SellerCatalog catalog;
    private String orderId;
    private String returnId;

    @AfterEach
    void resetSpies() {
        reset(payments);
    }

    /** Pedido pagado y entregado con una devolución en estado REQUESTED. */
    private void requestedReturn() throws Exception {
        admin = staffToken(SystemRole.ADMIN);
        operator = staffToken(SystemRole.LOGISTICS_OPERATOR);
        catalog = seedSellerCatalog(admin, new BigDecimal("100.00"));
        Account buyer = registerBuyer();
        send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/cart/items", buyer.token(),
                body("productId", catalog.productId(), "quantity", 1), 200);
        orderId = read(send(HttpMethod.POST, "/api/buyers/" + buyer.profileId() + "/orders", buyer.token(),
                body("shippingAddress", addressJson()), 201), "$.orderId");
        send(HttpMethod.POST, "/api/orders/" + orderId + "/payment", buyer.token(), body("paymentMethod", "CARD"), 200);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/dispatch", operator,
                body("warehouseId", catalog.warehouseId()), 200);
        send(HttpMethod.POST, "/api/orders/" + orderId + "/deliver", operator, "{}", 200);
        String json = "{\"items\":[{\"productId\":\"" + catalog.productId() + "\",\"quantity\":1,\"reason\":\"DAMAGED\"}]}";
        returnId = read(send(HttpMethod.POST, "/api/orders/" + orderId + "/returns", buyer.token(), json, 201),
                "$.returnId");
        reset(payments);
    }

    /** Ejecuta las acciones a la vez (compuerta común) y devuelve el estado HTTP de cada una. */
    private List<Integer> concurrently(List<Supplier<Integer>> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (Supplier<Integer> action : actions) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return action.get();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(90, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Supplier<Integer> post(String path, String token) {
        return () -> {
            try {
                return status(HttpMethod.POST, path, token, "{}");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static long count(List<Integer> statuses, int value) {
        return statuses.stream().filter(s -> s == value).count();
    }

    @Test
    void simultaneousApprovals_createExactlyOneRefund() throws Exception {
        requestedReturn();
        List<Supplier<Integer>> actions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actions.add(post("/api/returns/" + returnId + "/approve", admin));
        }

        List<Integer> statuses = concurrently(actions);

        assertThat(count(statuses, 200)).as("estados: %s", statuses).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409, "solo 200 o 409, nunca 500/503: " + statuses);
        assertThat(refunds.findByOrderId(OrderId.of(orderId))).hasSize(1);
        assertThat(returns.findById(ReturnId.of(returnId)).orElseThrow().getStatus()).isEqualTo(ReturnStatus.APPROVED);
    }

    @Test
    void approveAndRejectAtTheSameTime_haveASingleWinner_andAConsistentState() throws Exception {
        requestedReturn();
        List<Supplier<Integer>> actions = List.of(
                post("/api/returns/" + returnId + "/approve", admin),
                post("/api/returns/" + returnId + "/reject", admin),
                post("/api/returns/" + returnId + "/approve", admin),
                post("/api/returns/" + returnId + "/reject", admin));

        List<Integer> statuses = concurrently(actions);

        assertThat(count(statuses, 200)).as("estados: %s", statuses).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409, "solo 200 o 409: " + statuses);
        ReturnStatus finalStatus = returns.findById(ReturnId.of(returnId)).orElseThrow().getStatus();
        int refundCount = refunds.findByOrderId(OrderId.of(orderId)).size();
        if (finalStatus == ReturnStatus.APPROVED) {
            assertThat(refundCount).isEqualTo(1); // aprobada: hay exactamente un reembolso
        } else {
            assertThat(finalStatus).isEqualTo(ReturnStatus.REJECTED);
            assertThat(refundCount).isZero(); // rechazada: no debe existir ningún reembolso
        }
    }

    @Test
    void simultaneousRefundProcessing_callsTheGatewayOnce() throws Exception {
        requestedReturn();
        String refundId = read(send(HttpMethod.POST, "/api/returns/" + returnId + "/approve", admin, "{}", 200),
                "$.refundId");
        reset(payments);
        List<Supplier<Integer>> actions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actions.add(post("/api/refunds/" + refundId + "/process", admin));
        }

        List<Integer> statuses = concurrently(actions);

        assertThat(count(statuses, 200)).as("estados: %s", statuses).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409, "solo 200 o 409: " + statuses);
        verify(payments, times(1)).refund(any(), any(), any());
        assertThat(refunds.findById(RefundId.of(refundId)).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSED);
    }

    @Test
    void gatewayFailure_persistsFailedInMySql_andTheRefundCanBeRetried() throws Exception {
        requestedReturn();
        String refundId = read(send(HttpMethod.POST, "/api/returns/" + returnId + "/approve", admin, "{}", 200),
                "$.refundId");
        reset(payments);
        doThrow(new IllegalStateException("pasarela caída")).when(payments).refund(any(), any(), any());

        send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 400);

        assertThat(refunds.findById(RefundId.of(refundId)).orElseThrow().getStatus()).isEqualTo(RefundStatus.FAILED);
        reset(payments);
        send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 200);
        assertThat(refunds.findById(RefundId.of(refundId)).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSED);
        send(HttpMethod.POST, "/api/refunds/" + refundId + "/process", admin, "{}", 409);
    }

    @Test
    void simultaneousReturnProcessing_restocksInventoryOnlyOnce() throws Exception {
        requestedReturn();
        send(HttpMethod.POST, "/api/returns/" + returnId + "/approve", admin, "{}", 200);
        int before = JsonPath.read(send(HttpMethod.GET, "/api/inventory/" + catalog.inventoryId(),
                catalog.seller().token(), null, 200), "$.quantity");
        List<Supplier<Integer>> actions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actions.add(post("/api/returns/" + returnId + "/process", operator));
        }

        List<Integer> statuses = concurrently(actions);

        assertThat(count(statuses, 200)).as("estados: %s", statuses).isEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 200 || s == 409, "solo 200 o 409: " + statuses);
        int after = JsonPath.read(send(HttpMethod.GET, "/api/inventory/" + catalog.inventoryId(),
                catalog.seller().token(), null, 200), "$.quantity");
        assertThat(after).as("la reposición de una unidad devuelta ocurre una sola vez").isEqualTo(before + 1);
    }
}
