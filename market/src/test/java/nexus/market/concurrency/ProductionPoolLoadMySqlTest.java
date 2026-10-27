package nexus.market.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import nexus.market.domain.ports.in.CartUseCases;
import nexus.market.domain.ports.in.InventoryUseCases;
import nexus.market.domain.ports.in.OrderUseCases;
import nexus.market.domain.ports.out.InventoryRepositoryPort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.SystemRole;

/**
 * ¿El {@code connection-timeout} de producción provoca fallos en peticiones LEGÍTIMAS bajo carga? MySQL 8 real, con
 * el pool y la espera de bloqueo de {@code application-prod.properties} (pool 20, {@code connection-timeout} 10 000 ms,
 * {@code innodb_lock_wait_timeout}=10). Los valores se pueden sustituir con {@code NEXUS_TEST_POOL} y
 * {@code NEXUS_TEST_CONN_TIMEOUT} para medir la sensibilidad; solo con los valores de producción se EXIGE ausencia de
 * fallos de pool, con otros solo se informa.
 *
 * <p>Se omite sin {@code NEXUS_TEST_MYSQL_URL}. Nunca contra una base real o compartida.</p>
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
        "spring.datasource.hikari.maximum-pool-size=${NEXUS_TEST_POOL:20}",
        "spring.datasource.hikari.connection-timeout=${NEXUS_TEST_CONN_TIMEOUT:10000}",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=10"
})
class ProductionPoolLoadMySqlTest extends RestTestSupport {

    private static final Address ADDRESS = Address.of("Calle 1", "12-34", "Piso 3", "Centro", "Bogota",
            "Cundinamarca", "110111", "CO");
    private static final boolean PRODUCTION_VALUES = System.getenv("NEXUS_TEST_POOL") == null
            && System.getenv("NEXUS_TEST_CONN_TIMEOUT") == null;

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

    private final AtomicReference<CyclicBarrier> afterInventoryRead = new AtomicReference<>();

    @BeforeEach
    void seam() {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            CyclicBarrier barrier = afterInventoryRead.get();
            if (barrier != null) {
                try {
                    barrier.await(5, TimeUnit.SECONDS); // conserva la conexión y los bloqueos hasta 5 s
                } catch (Exception expired) {
                    // la barrera nunca se llena a propósito (más participantes que conexiones): expira
                }
            }
            return result;
        }).when(inventories).findByProductId(any());
    }

    @AfterEach
    void clear() {
        afterInventoryRead.set(null);
        reset(inventories);
    }

    private record Timed(boolean ok, Throwable error, long millis) {
    }

    private List<Timed> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Timed>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                long start = System.nanoTime();
                try {
                    task.call();
                    return new Timed(true, null, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                } catch (Throwable error) {
                    return new Timed(false, error, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                }
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<Timed> results = new ArrayList<>();
        for (Future<Timed> future : futures) {
            results.add(future.get(180, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    private static boolean poolTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
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

    private List<BuyerId> buyersWithCart(SellerCatalog catalog, int buyers) throws Exception {
        List<BuyerId> result = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            BuyerId buyer = BuyerId.of(registerBuyer().profileId());
            cartUseCases.addItem(buyer, ProductId.of(catalog.productId()), Quantity.of(1));
            result.add(buyer);
        }
        return result;
    }

    private SellerCatalog catalogWithStock(int stock) throws Exception {
        SellerCatalog catalog = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000.00")); // 10
        if (stock > 10) {
            inventoryUseCases.addStock(InventoryId.of(catalog.inventoryId()), Quantity.of(stock - 10));
        }
        return catalog;
    }

    private void report(String scenario, List<Timed> results) {
        long poolTimeouts = results.stream().filter(r -> !r.ok() && poolTimeout(r.error())).count();
        long otherErrors = results.stream().filter(r -> !r.ok() && !poolTimeout(r.error())).count();
        long max = results.stream().mapToLong(Timed::millis).max().orElse(0);
        String otherTypes = results.stream().filter(r -> !r.ok() && !poolTimeout(r.error()))
                .map(r -> r.error().getClass().getSimpleName()).distinct().toList().toString();
        long ok = results.stream().filter(Timed::ok).count();
        String config = "pool=" + System.getenv().getOrDefault("NEXUS_TEST_POOL", "20") + " connection-timeout="
                + System.getenv().getOrDefault("NEXUS_TEST_CONN_TIMEOUT", "10000");
        System.out.println("[POOL] " + scenario + " | " + config + " | peticiones=" + results.size() + " correctas=" + ok
                + " fallos de pool (503)=" + poolTimeouts + " otros errores=" + otherErrors + " " + otherTypes + " | latencia máxima=" + max + " ms");
    }

    // ------------------------------------------------------------------
    // 1. Carga normal con contención de bloqueos (sin retención artificial)
    // ------------------------------------------------------------------

    @Test
    void sixtyConcurrentOrdersOverTheSameStock_noLegitimateRequestFailsOnThePool() throws Exception {
        SellerCatalog catalog = catalogWithStock(30);
        List<BuyerId> buyers = buyersWithCart(catalog, 60);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (BuyerId buyer : buyers) {
            tasks.add(() -> orderUseCases.createOrder(buyer, ADDRESS));
        }

        List<Timed> results = runConcurrently(tasks);

        report("60 pedidos simultáneos, stock 30", results);
        assertThat(results.stream().filter(Timed::ok).count()).as("exactamente 30 caben").isEqualTo(30);
        if (PRODUCTION_VALUES) {
            assertThat(results.stream().filter(r -> !r.ok() && poolTimeout(r.error())).count())
                    .as("ninguna petición legítima falla por espera de conexión").isZero();
        }
        assertThat(scalar("select reserved_quantity from inventories where inventory_id = ?1", catalog.inventoryId()))
                .isEqualTo(30);
    }

    // ------------------------------------------------------------------
    // 2. Peor caso: las conexiones y los bloqueos se retienen ~5 s (lecturas coincidentes forzadas)
    // ------------------------------------------------------------------

    @Test
    void whenConnectionsAreHeldForFiveSeconds_thirtyOrdersOnAPoolOfTwenty_stillCompleteWithTheProductionTimeout() throws Exception {
        SellerCatalog catalog = catalogWithStock(30);
        List<BuyerId> buyers = buyersWithCart(catalog, 30);
        afterInventoryRead.set(new CyclicBarrier(30)); // nunca se llena (hay 20 conexiones): cada transacción espera 5 s
        List<Callable<Object>> tasks = new ArrayList<>();
        for (BuyerId buyer : buyers) {
            tasks.add(() -> orderUseCases.createOrder(buyer, ADDRESS));
        }

        List<Timed> results = runConcurrently(tasks);

        report("30 pedidos con retención de 5 s (pool 20)", results);
        if (PRODUCTION_VALUES) {
            assertThat(results.stream().filter(r -> !r.ok() && poolTimeout(r.error())).count())
                    .as("con 10 s ninguna espera por conexión llega al timeout").isZero();
            assertThat(results.stream().filter(Timed::ok).count()).as("caben los 30 (stock 30)").isEqualTo(30);
        }
    }
}
