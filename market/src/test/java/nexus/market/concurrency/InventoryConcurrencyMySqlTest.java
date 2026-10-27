package nexus.market.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import nexus.market.adapters.in.rest.RestTestSupport;
import nexus.market.domain.models.Order;
import nexus.market.domain.ports.in.CartUseCases;
import nexus.market.domain.ports.in.InventoryUseCases;
import nexus.market.domain.ports.in.OrderUseCases;
import nexus.market.domain.ports.out.InventoryRepositoryPort;
import nexus.market.domain.ports.out.PaymentServicePort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * Carreras de inventario contra MySQL 8 REAL (InnoDB, REPEATABLE READ), con el esquema creado por
 * Flyway ({@code classpath:db/schema}) y {@code ddl-auto=validate}.
 *
 * <p><b>Solo se ejecutan si hay un MySQL efímero configurado</b> (variables de entorno
 * {@code NEXUS_TEST_MYSQL_URL}, {@code NEXUS_TEST_MYSQL_USER}, {@code NEXUS_TEST_MYSQL_PASSWORD}); sin
 * ellas se omiten. Nunca apuntar a una base real o compartida: estas pruebas crean y modifican datos.</p>
 *
 * <p>Las pruebas afirman el comportamiento CORRECTO (invariantes). Mientras la implementación no
 * serialice el acceso al inventario, fallan: el fallo es la demostración de la carrera. Para no
 * depender de temporizaciones, la coincidencia se fuerza con barreras ({@link CyclicBarrier}) colocadas
 * justo después de la lectura de inventario/cobro mediante un {@code spy}; si una corrección serializa
 * las operaciones, la barrera expira (5 s) y el flujo continúa de forma secuencial, de modo que las
 * mismas pruebas sirven para validar la corrección. No se modifica código de producción.</p>
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
        "spring.datasource.hikari.maximum-pool-size=60",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=15"
})
class InventoryConcurrencyMySqlTest extends RestTestSupport {

    private static final Address ADDRESS = Address.of("Calle 1", "12-34", "Piso 3", "Centro", "Bogota",
            "Cundinamarca", "110111", "CO");

    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private OrderUseCases orderUseCases;
    @Autowired
    private CartUseCases cartUseCases;
    @Autowired
    private InventoryUseCases inventoryUseCases;
    @MockitoSpyBean
    private InventoryRepositoryPort inventories;
    @MockitoSpyBean
    private PaymentServicePort payments;

    // Barreras de "coincidencia forzada" (null = sin barrera) y contadores.
    private final AtomicReference<CyclicBarrier> afterInventoryRead = new AtomicReference<>();
    private final AtomicReference<CyclicBarrier> afterCharge = new AtomicReference<>();
    private final AtomicInteger chargeCalls = new AtomicInteger();

    @BeforeEach
    void installSeams() {
        chargeCalls.set(0);
        OVERLAPS.set(0);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            await(afterInventoryRead.get());
            return result;
        }).when(inventories).findByProductId(any());
        doAnswer(invocation -> {
            chargeCalls.incrementAndGet();
            Object result = invocation.callRealMethod();
            await(afterCharge.get());
            return result;
        }).when(payments).charge(any(), any(), any());
    }

    @AfterEach
    void removeSeams() {
        afterInventoryRead.set(null);
        afterCharge.set(null);
        reset(inventories, payments);
    }

    /** Cada hilo espera a los demás como máximo 5 s, una sola vez por barrera. */
    private static final ThreadLocal<Set<CyclicBarrier>> PASSED = ThreadLocal.withInitial(HashSet::new);

    /** Hilos que llegaron al punto de coincidencia junto con todos los demás (evidencia del solapamiento). */
    private static final AtomicInteger OVERLAPS = new AtomicInteger();

    private static void await(CyclicBarrier barrier) {
        if (barrier == null || !PASSED.get().add(barrier)) {
            return;
        }
        try {
            barrier.await(5, TimeUnit.SECONDS);
            OVERLAPS.incrementAndGet(); // todos los participantes coincidieron en este punto
        } catch (Exception timeoutOrBroken) {
            // Expiró: la operación está serializada (o falló un participante); se continúa.
        }
    }

    // ------------------------------------------------------------------
    // Infraestructura de pruebas
    // ------------------------------------------------------------------

    private record Outcome<T>(T value, Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    private <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return new Outcome<>(task.call(), null);
                } catch (Throwable error) {
                    return new Outcome<>(null, error);
                }
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<Outcome<T>> outcomes = new ArrayList<>();
        for (Future<Outcome<T>> future : futures) {
            outcomes.add(future.get(90, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return outcomes;
    }

    private long scalar(String sql, Object... params) {
        try (EntityManager em = emf.createEntityManager()) {
            var query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            return ((Number) query.getSingleResult()).longValue();
        }
    }

    private long quantity(String inventoryId) {
        return scalar("select quantity from inventories where inventory_id = ?1", inventoryId);
    }

    private long reserved(String inventoryId) {
        return scalar("select reserved_quantity from inventories where inventory_id = ?1", inventoryId);
    }

    private SellerCatalog newCatalog() throws Exception {
        return seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000.00")); // 10 unidades
    }

    private BuyerId buyerWithCart(SellerCatalog catalog, int quantity) throws Exception {
        Account buyer = registerBuyer();
        BuyerId buyerId = BuyerId.of(buyer.profileId());
        cartUseCases.addItem(buyerId, ProductId.of(catalog.productId()), Quantity.of(quantity));
        return buyerId;
    }

    private static String describe(List<? extends Outcome<?>> outcomes) {
        StringBuilder text = new StringBuilder();
        for (Outcome<?> outcome : outcomes) {
            text.append(outcome.ok() ? "OK " : "ERR(" + rootCause(outcome.error()) + ") ");
        }
        return text.toString().trim();
    }

    private static String rootCause(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = String.valueOf(root.getMessage());
        return root.getClass().getSimpleName() + ": " + message.substring(0, Math.min(70, message.length()));
    }

    /** Errores cuya causa es la base de datos (SQLException): un conflicto de negocio no debe filtrarse así. */
    private static long databaseErrors(List<? extends Outcome<?>> outcomes) {
        return outcomes.stream().filter(o -> !o.ok() && hasSqlCause(o.error())).count();
    }

    private static boolean hasSqlCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException) {
                return true;
            }
        }
        return false;
    }

    private void report(String scenario, String detail) {
        System.out.println("[CONC] " + scenario + " | solapamientos=" + OVERLAPS.get() + " | " + detail);
    }

    private void assertInventoryHealthy(String inventoryId) {
        assertThat(quantity(inventoryId)).as("stock nunca negativo").isGreaterThanOrEqualTo(0);
        assertThat(reserved(inventoryId)).as("reservado nunca negativo").isGreaterThanOrEqualTo(0);
        assertThat(reserved(inventoryId)).as("reservado <= stock").isLessThanOrEqualTo(quantity(inventoryId));
    }

    // ------------------------------------------------------------------
    // 1. Dos pedidos de 8 unidades con stock 10
    // ------------------------------------------------------------------

    @Test
    void twoOrdersOfEight_withStockOfTen_doNotSellMoreThanAvailable() throws Exception {
        SellerCatalog catalog = newCatalog();
        BuyerId first = buyerWithCart(catalog, 8);
        BuyerId second = buyerWithCart(catalog, 8);
        afterInventoryRead.set(new CyclicBarrier(2)); // ambos leen "10 disponibles" antes de que alguno escriba

        List<Outcome<Order>> outcomes = runConcurrently(List.of(
                () -> orderUseCases.createOrder(first, ADDRESS),
                () -> orderUseCases.createOrder(second, ADDRESS)));

        long accepted = outcomes.stream().filter(Outcome::ok).count();
        long orderRows = scalar("select count(*) from orders where buyer_id in (?1, ?2)", first.value(), second.value());
        String detail = describe(outcomes) + " | reservado=" + reserved(catalog.inventoryId())
                + " de stock=" + quantity(catalog.inventoryId()) + " | pedidos en BD=" + orderRows;
        report("2x8 con stock 10", detail);
        assertThat(accepted * 8).as("unidades aceptadas <= 10. " + detail).isLessThanOrEqualTo(10);
        assertThat(orderRows).as("pedidos persistidos == aceptados. " + detail).isEqualTo(accepted);
        assertThat(reserved(catalog.inventoryId())).as("reservado == aceptado. " + detail).isEqualTo(accepted * 8);
        assertInventoryHealthy(catalog.inventoryId());
        assertThat(accepted).as("exactamente un pedido cabe. " + detail).isEqualTo(1);
        assertThat(databaseErrors(outcomes)).as("el rechazo debe ser de negocio, no un error de BD. " + detail).isZero();
    }

    // ------------------------------------------------------------------
    // 2. Veinte pedidos de 1 unidad con stock 10
    // ------------------------------------------------------------------

    @Test
    void twentyOrdersOfOne_withStockOfTen_acceptExactlyTen_forcedOverlap() throws Exception {
        assertTwentyOrders(true);
    }

    @Test
    void twentyOrdersOfOne_withStockOfTen_acceptExactlyTen_naturalRace() throws Exception {
        assertTwentyOrders(false);
    }

    private void assertTwentyOrders(boolean forceOverlap) throws Exception {
        SellerCatalog catalog = newCatalog();
        List<BuyerId> buyers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            buyers.add(buyerWithCart(catalog, 1));
        }
        if (forceOverlap) {
            afterInventoryRead.set(new CyclicBarrier(20));
        }
        List<Callable<Order>> tasks = new ArrayList<>();
        for (BuyerId buyer : buyers) {
            tasks.add(() -> orderUseCases.createOrder(buyer, ADDRESS));
        }

        List<Outcome<Order>> outcomes = runConcurrently(tasks);

        long accepted = outcomes.stream().filter(Outcome::ok).count();
        String ids = String.join(",", buyers.stream().map(b -> "'" + b.value() + "'").toList());
        long orderRows = scalar("select count(*) from orders where buyer_id in (" + ids + ")");
        String detail = "aceptados=" + accepted + " rechazados=" + (20 - accepted)
                + " | reservado=" + reserved(catalog.inventoryId()) + " de stock=" + quantity(catalog.inventoryId())
                + " | pedidos en BD=" + orderRows;
        report("20x1 con stock 10 (solapamiento forzado=" + forceOverlap + ")", detail + " | " + describe(outcomes));
        assertThat(accepted).as("no se aceptan más de 10. " + detail).isLessThanOrEqualTo(10);
        assertThat(orderRows).as("pedidos persistidos == aceptados. " + detail).isEqualTo(accepted);
        assertThat(reserved(catalog.inventoryId())).as("reservado == aceptados. " + detail).isEqualTo(accepted);
        assertInventoryHealthy(catalog.inventoryId());
        assertThat(accepted).as("con 10 unidades y 20 pedidos de 1 deben aceptarse exactamente 10. " + detail)
                .isEqualTo(10);
        assertThat(databaseErrors(outcomes)).as("los rechazos deben ser de negocio, no errores de BD. " + detail)
                .isZero();
    }

    // ------------------------------------------------------------------
    // 3. El mismo pago procesado dos veces a la vez
    // ------------------------------------------------------------------

    @Test
    void samePaymentConfirmedTwiceAtOnce_isChargedAndAppliedOnlyOnce() throws Exception {
        SellerCatalog catalog = newCatalog();
        BuyerId buyer = buyerWithCart(catalog, 2);
        OrderId orderId = orderUseCases.createOrder(buyer, ADDRESS).getOrderId();
        assertThat(reserved(catalog.inventoryId())).isEqualTo(2);
        chargeCalls.set(0);
        afterCharge.set(new CyclicBarrier(2)); // ambos pasan la comprobación "pedido modificable" y cobran

        List<Outcome<Object>> outcomes = runConcurrently(List.of(
                () -> orderUseCases.confirmPayment(orderId, "CARD"),
                () -> orderUseCases.confirmPayment(orderId, "CARD")));

        long invoices = scalar("select count(*) from invoices where order_id = ?1", orderId.value());
        String detail = describe(outcomes) + " | cobros=" + chargeCalls.get() + " facturas=" + invoices
                + " | stock=" + quantity(catalog.inventoryId()) + " reservado=" + reserved(catalog.inventoryId());
        report("doble pago simultáneo", detail);
        assertThat(outcomes.stream().filter(Outcome::ok).count()).as("un solo pago exitoso. " + detail).isEqualTo(1);
        assertThat(chargeCalls.get()).as("un solo cobro a la pasarela. " + detail).isEqualTo(1);
        assertThat(invoices).as("una sola factura. " + detail).isEqualTo(1);
        assertThat(databaseErrors(outcomes)).as("el rechazo debe ser de negocio, no un error de BD. " + detail).isZero();
        assertThat(quantity(catalog.inventoryId())).as("la venta descuenta 2 una sola vez. " + detail).isEqualTo(8);
        assertThat(reserved(catalog.inventoryId())).as("la reserva se consume una sola vez. " + detail).isEqualTo(0);
        assertInventoryHealthy(catalog.inventoryId());
    }

    // ------------------------------------------------------------------
    // 4. Reserva y liberación concurrentes
    // ------------------------------------------------------------------

    @Test
    void concurrentReserveAndRelease_keepTheCountersConsistent() throws Exception {
        SellerCatalog catalog = newCatalog();
        InventoryId inventoryId = InventoryId.of(catalog.inventoryId());
        inventoryUseCases.reserve(inventoryId, Quantity.of(5));
        long movementsBefore = scalar("select count(*) from inventory_movements where inventory_id = ?1",
                catalog.inventoryId());
        int mismatches = 0;
        long totalOk = 0;
        long totalOps = 0;
        StringBuilder log = new StringBuilder();

        for (int round = 1; round <= 15; round++) {
            long reservedBefore = reserved(catalog.inventoryId());
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tasks.add(() -> {
                    inventoryUseCases.reserve(inventoryId, Quantity.of(1));
                    return "R";
                });
                tasks.add(() -> {
                    inventoryUseCases.release(inventoryId, Quantity.of(1));
                    return "L";
                });
            }
            List<Outcome<String>> outcomes = runConcurrently(tasks);
            long reserves = outcomes.stream().filter(o -> o.ok() && "R".equals(o.value())).count();
            long releases = outcomes.stream().filter(o -> o.ok() && "L".equals(o.value())).count();
            totalOk += reserves + releases;
            totalOps += outcomes.size();
            long expected = reservedBefore + reserves - releases;
            long actual = reserved(catalog.inventoryId());
            if (expected != actual) {
                mismatches++;
                log.append("ronda ").append(round).append(": esperado=").append(expected).append(" real=")
                        .append(actual).append(" (").append(describe(outcomes)).append(") ");
            }
            assertInventoryHealthy(catalog.inventoryId());
        }
        long movementsAfter = scalar("select count(*) from inventory_movements where inventory_id = ?1",
                catalog.inventoryId());
        report("reserva/liberación 15 rondas", "operaciones exitosas=" + totalOk + " de " + totalOps
                + " | rondas inconsistentes=" + mismatches + " " + log);
        assertThat(totalOk).as("todas las reservas/liberaciones válidas deben aceptarse (" + totalOk + "/"
                + totalOps + ")").isEqualTo(totalOps);
        assertThat(mismatches).as("rondas con contador inconsistente. " + log).isZero();
        assertThat(movementsAfter).as("movimientos registrados >= iniciales").isGreaterThanOrEqualTo(movementsBefore);
    }

    // ------------------------------------------------------------------
    // 5. markDamaged / markActive frente a reservas (cambios de estado sin movimiento)
    // ------------------------------------------------------------------

    private String status(String inventoryId) {
        try (EntityManager em = emf.createEntityManager()) {
            return (String) em.createNativeQuery("select status from inventories where inventory_id = ?1")
                    .setParameter(1, inventoryId).getSingleResult();
        }
    }

    private long movements(String inventoryId) {
        return scalar("select count(*) from inventory_movements where inventory_id = ?1", inventoryId);
    }

    @Test
    void markDamagedAndMarkActive_concurrentWithReservations_neverLoseOrOverwriteUpdates() throws Exception {
        SellerCatalog catalog = newCatalog();
        InventoryId inventoryId = InventoryId.of(catalog.inventoryId());
        StringBuilder problems = new StringBuilder();
        long totalOps = 0;
        long totalOk = 0;

        for (int round = 1; round <= 8; round++) {
            for (boolean toDamaged : new boolean[] { true, false }) {
                long reservedBefore = reserved(catalog.inventoryId());
                long movementsBefore = movements(catalog.inventoryId());
                List<Callable<String>> tasks = new ArrayList<>();
                tasks.add(() -> {
                    if (toDamaged) {
                        inventoryUseCases.markDamaged(inventoryId);
                    } else {
                        inventoryUseCases.markActive(inventoryId);
                    }
                    return "S";
                });
                for (int i = 0; i < 3; i++) {
                    tasks.add(() -> {
                        inventoryUseCases.reserve(inventoryId, Quantity.of(1));
                        return "R";
                    });
                }
                List<Outcome<String>> outcomes = runConcurrently(tasks);
                long reserves = outcomes.stream().filter(o -> o.ok() && "R".equals(o.value())).count();
                long stateChanges = outcomes.stream().filter(o -> o.ok() && "S".equals(o.value())).count();
                totalOps += outcomes.size();
                totalOk += outcomes.stream().filter(Outcome::ok).count();
                String expectedStatus = toDamaged ? "DAMAGED" : "ACTIVE";
                // Cada reserva exitosa deja exactamente 1 contador y 1 movimiento; el cambio de estado no se pierde.
                if (reserved(catalog.inventoryId()) != reservedBefore + reserves) {
                    problems.append("ronda ").append(round).append(toDamaged ? " D" : " A")
                            .append(": reservado=").append(reserved(catalog.inventoryId()))
                            .append(" esperado=").append(reservedBefore + reserves).append("; ");
                }
                if (movements(catalog.inventoryId()) != movementsBefore + reserves) {
                    problems.append("ronda ").append(round).append(toDamaged ? " D" : " A")
                            .append(": movimientos=").append(movements(catalog.inventoryId()))
                            .append(" esperado=").append(movementsBefore + reserves).append("; ");
                }
                if (stateChanges == 1 && !expectedStatus.equals(status(catalog.inventoryId()))) {
                    problems.append("ronda ").append(round).append(": el estado ").append(expectedStatus)
                            .append(" se perdió (actual=").append(status(catalog.inventoryId())).append("); ");
                }
                // Reiniciar: reactivar y liberar lo reservado para la siguiente ronda.
                inventoryUseCases.markActive(inventoryId);
                long toRelease = reserved(catalog.inventoryId());
                if (toRelease > 0) {
                    inventoryUseCases.release(inventoryId, Quantity.of((int) toRelease));
                }
                assertInventoryHealthy(catalog.inventoryId());
            }
        }
        report("markDamaged/markActive vs reservas (16 rondas)", "operaciones exitosas=" + totalOk + " de " + totalOps
                + " | problemas: " + (problems.length() == 0 ? "ninguno" : problems));
        assertThat(problems.toString()).as("actualizaciones perdidas o sobrescritas").isEmpty();
    }

    @Test
    void concurrentMarkDamagedTwice_isHandledWithoutDatabaseErrors() throws Exception {
        SellerCatalog catalog = newCatalog();
        InventoryId inventoryId = InventoryId.of(catalog.inventoryId());
        List<Outcome<String>> outcomes = runConcurrently(List.of(
                () -> {
                    inventoryUseCases.markDamaged(inventoryId);
                    return "D";
                },
                () -> {
                    inventoryUseCases.markDamaged(inventoryId);
                    return "D";
                },
                () -> {
                    inventoryUseCases.markActive(inventoryId);
                    return "A";
                }));
        report("markDamaged x2 + markActive", describe(outcomes) + " | estado final=" + status(catalog.inventoryId()));
        assertThat(databaseErrors(outcomes)).as("sin errores de BD. " + describe(outcomes)).isZero();
        assertThat(status(catalog.inventoryId())).isIn("ACTIVE", "DAMAGED");
        assertInventoryHealthy(catalog.inventoryId());
    }

    // ------------------------------------------------------------------
    // 6. Orden de bloqueo: pedidos con los mismos productos en orden cruzado
    // ------------------------------------------------------------------

    @Test
    void ordersWithTheSameProductsInCrossedOrder_doNotDeadlock() throws Exception {
        SellerCatalog a = newCatalog();
        SellerCatalog b = newCatalog();
        List<Callable<Order>> tasks = new ArrayList<>();
        List<BuyerId> buyers = new ArrayList<>();
        for (int i = 0; i < 6; i++) { // mitad A->B, mitad B->A en el carrito
            Account account = registerBuyer();
            BuyerId buyer = BuyerId.of(account.profileId());
            SellerCatalog first = i % 2 == 0 ? a : b;
            SellerCatalog second = i % 2 == 0 ? b : a;
            cartUseCases.addItem(buyer, ProductId.of(first.productId()), Quantity.of(1));
            cartUseCases.addItem(buyer, ProductId.of(second.productId()), Quantity.of(1));
            buyers.add(buyer);
            tasks.add(() -> orderUseCases.createOrder(buyer, ADDRESS));
        }
        afterInventoryRead.set(new CyclicBarrier(6));

        List<Outcome<Order>> outcomes = runConcurrently(tasks);

        String detail = describe(outcomes) + " | A reservado=" + reserved(a.inventoryId())
                + " B reservado=" + reserved(b.inventoryId());
        report("pedidos A-B / B-A cruzados", detail);
        assertThat(outcomes.stream().filter(Outcome::ok).count()).as("los 6 caben (stock 10). " + detail).isEqualTo(6);
        assertThat(reserved(a.inventoryId())).as(detail).isEqualTo(6);
        assertThat(reserved(b.inventoryId())).as(detail).isEqualTo(6);
        assertInventoryHealthy(a.inventoryId());
        assertInventoryHealthy(b.inventoryId());
    }

    // ------------------------------------------------------------------
    // 7. Cancelar y pagar el mismo pedido a la vez
    // ------------------------------------------------------------------

    @Test
    void cancelAndPaymentOfTheSameOrderAtOnce_areSerialized() throws Exception {
        SellerCatalog catalog = newCatalog();
        BuyerId buyer = buyerWithCart(catalog, 3);
        OrderId orderId = orderUseCases.createOrder(buyer, ADDRESS).getOrderId();
        chargeCalls.set(0);

        List<Outcome<Object>> outcomes = runConcurrently(List.of(
                () -> orderUseCases.confirmPayment(orderId, "CARD"),
                () -> {
                    orderUseCases.cancel(orderId);
                    return "cancelado";
                }));

        long invoices = scalar("select count(*) from invoices where order_id = ?1", orderId.value());
        String finalStatus;
        try (EntityManager em = emf.createEntityManager()) {
            finalStatus = (String) em.createNativeQuery("select status from orders where order_id = ?1")
                    .setParameter(1, orderId.value()).getSingleResult();
        }
        String detail = describe(outcomes) + " | estado=" + finalStatus + " cobros=" + chargeCalls.get()
                + " facturas=" + invoices + " stock=" + quantity(catalog.inventoryId())
                + " reservado=" + reserved(catalog.inventoryId());
        report("pagar y cancelar a la vez", detail);
        assertThat(databaseErrors(outcomes)).as("sin errores de BD. " + detail).isZero();
        assertThat(chargeCalls.get()).as("a lo sumo un cobro. " + detail).isLessThanOrEqualTo(1);
        assertThat(invoices).as("a lo sumo una factura. " + detail).isLessThanOrEqualTo(1);
        assertThat(finalStatus).as(detail).isIn("PAID", "CANCELLED");
        assertInventoryHealthy(catalog.inventoryId());
    }
}
