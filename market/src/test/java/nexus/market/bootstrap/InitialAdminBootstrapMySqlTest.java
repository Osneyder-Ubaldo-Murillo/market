package nexus.market.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import nexus.market.MarketApplication;
import nexus.market.adapters.in.rest.RestTestSupport;
import nexus.market.adapters.out.persistence.mysql.SqlAdminProvisioningLock;
import nexus.market.application.AdminProvisioningLockPort;
import nexus.market.application.InitialAdminException;
import nexus.market.application.InitialAdminService;
import nexus.market.application.InitialAdminStorePort;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.in.AuthUseCases;
import nexus.market.domain.ports.in.CartUseCases;
import nexus.market.domain.ports.in.InventoryUseCases;
import nexus.market.domain.ports.in.OrderUseCases;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserStatus;
import nexus.market.infrastructure.bootstrap.BootstrapAdminSettings;

/**
 * Administrador inicial contra MySQL 8 REAL (InnoDB): concurrencia entre instancias, candado {@code GET_LOCK},
 * rollback y coexistencia con los casos de uso en {@code READ_COMMITTED}.
 *
 * <p>Solo se ejecuta con un MySQL <b>efímero</b> ({@code NEXUS_TEST_MYSQL_URL}, {@code NEXUS_TEST_MYSQL_USER},
 * {@code NEXUS_TEST_MYSQL_PASSWORD}); jamás contra una base real o compartida: estas pruebas crean y BORRAN filas
 * con rol ADMIN.</p>
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
        "spring.datasource.hikari.maximum-pool-size=80",
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=15"
})
class InitialAdminBootstrapMySqlTest extends RestTestSupport {

    private static final String PASSWORD = "Rk7#pQzLm2!vXw9T";
    private static final Address ADDRESS = Address.of("Calle 1", "12-34", "Piso 3", "Centro", "Bogota",
            "Cundinamarca", "110111", "CO");
    private static final String LOCK = AdminProvisioningLockPort.LOCK_NAME;

    @TempDir
    Path tmp;

    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private InitialAdminStorePort store;
    @Autowired
    private AdminProvisioningLockPort lock;
    @Autowired
    private PasswordServicePort passwords;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OrderUseCases orderUseCases;
    @Autowired
    private CartUseCases cartUseCases;
    @Autowired
    private InventoryUseCases inventoryUseCases;
    @Autowired
    private AuthUseCases authUseCases;

    // ------------------------------------------------------------------
    // Utilidades
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
            outcomes.add(future.get(120, TimeUnit.SECONDS));
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

    private void execute(String sql) {
        try (EntityManager em = emf.createEntityManager()) {
            em.getTransaction().begin();
            em.createNativeQuery(sql).executeUpdate();
            em.getTransaction().commit();
        }
    }

    /** Borra TODO usuario ADMIN y sus credenciales (solo en la base efímera de la prueba). */
    @BeforeEach
    void cleanAdministrators() {
        execute("delete from user_credentials where user_id in (select id from users where role = 'ADMIN')");
        execute("delete from users where role = 'ADMIN'");
    }

    private long admins() {
        return scalar("select count(*) from users where role = 'ADMIN'");
    }

    private InitialAdminService service() {
        return new InitialAdminService(store, lock, passwords, transactionManager);
    }

    private InitialAdminService.Result provision(InitialAdminService service, int n) {
        return service.provision(FullName.of("Administradora " + n), Email.of("admin" + n + "@tienda.example"),
                DocumentId.of("ADM-" + (1000 + n)), () -> PASSWORD);
    }

    private static String describe(List<? extends Outcome<?>> outcomes) {
        StringBuilder text = new StringBuilder();
        for (Outcome<?> outcome : outcomes) {
            text.append(outcome.ok() ? outcome.value() + " " : "ERR(" + outcome.error().getClass().getSimpleName() + ") ");
        }
        return text.toString().trim();
    }

    private void report(String scenario, String detail) {
        System.out.println("[ADMIN] " + scenario + " | " + detail);
    }

    private boolean lockIsFree() {
        return scalar("select is_free_lock('" + LOCK + "')") == 1;
    }

    // ------------------------------------------------------------------
    // 8. Ejecuciones simultáneas con la MISMA configuración
    // ------------------------------------------------------------------

    @Test
    void concurrentInstancesWithTheSameConfiguration_createExactlyOneAdministrator() throws Exception {
        InitialAdminService service = service();
        StringBuilder rounds = new StringBuilder();
        for (int round = 1; round <= 10; round++) {
            cleanAdministrators();
            List<Callable<InitialAdminService.Result>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> provision(service, 1)); // misma identidad en las 8 "instancias"
            }
            List<Outcome<InitialAdminService.Result>> outcomes = runConcurrently(tasks);
            long created = outcomes.stream().filter(o -> o.ok() && o.value() == InitialAdminService.Result.CREATED).count();
            rounds.append(created).append('/').append(admins()).append(' ');
            assertThat(outcomes.stream().filter(o -> !o.ok()).count())
                    .as("ninguna falla por la carrera. " + describe(outcomes)).isZero();
            assertThat(created).as("exactamente una creó. " + describe(outcomes)).isEqualTo(1);
            assertThat(admins()).isEqualTo(1);
            assertThat(scalar("select count(*) from user_credentials where user_id = ?1",
                    InitialAdminService.INITIAL_ADMIN_ID.value())).isEqualTo(1);
            assertThat(lockIsFree()).as("candado liberado").isTrue();
        }
        report("misma configuración, 8 hilos x 10 rondas", "creadas/administradores por ronda: " + rounds);
    }

    // ------------------------------------------------------------------
    // 9. Configuraciones DISTINTAS (otro correo/documento/contraseña por instancia)
    // ------------------------------------------------------------------

    @Test
    void concurrentInstancesWithDifferentConfigurations_stillCreateExactlyOne() throws Exception {
        InitialAdminService service = service();
        for (int round = 1; round <= 5; round++) {
            cleanAdministrators();
            List<Callable<InitialAdminService.Result>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int n = i + 1;
                tasks.add(() -> provision(service, n));
            }
            List<Outcome<InitialAdminService.Result>> outcomes = runConcurrently(tasks);
            long created = outcomes.stream().filter(o -> o.ok() && o.value() == InitialAdminService.Result.CREATED).count();
            assertThat(outcomes.stream().filter(o -> !o.ok()).count()).as(describe(outcomes)).isZero();
            assertThat(created).as(describe(outcomes)).isEqualTo(1);
            assertThat(admins()).isEqualTo(1);
        }
    }

    /**
     * El protocolo (candado + transacción + sentencia atómica) SIN el id determinista: cada hilo usa un id distinto,
     * como lo haría otro procedimiento que siguiera el mismo protocolo. Demuestra que la exclusión no descansa en la
     * clave primaria.
     */
    private long runProtocolWithRandomIds(int isolation, boolean useLock) throws Exception {
        cleanAdministrators();
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int n = i + 1;
            tasks.add(() -> {
                Supplier<String> work = () -> {
                    LocalDateTime now = LocalDateTime.now();
                    User admin = new User(nexus.market.domain.valueobjects.UserId.generate(),
                            FullName.of("Admin " + n), Email.of("rnd" + n + "@tienda.example"),
                            DocumentId.of("RND-" + (1000 + n)), SystemRole.ADMIN, UserStatus.ACTIVE, now, now);
                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    tx.setIsolationLevel(isolation);
                    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    return tx.execute(status -> store.insertIfNoAdmin(admin, "$2a$10$" + "x".repeat(53)).name());
                };
                return useLock ? lock.withLock(work) : work.get();
            });
        }
        List<Outcome<String>> outcomes = runConcurrently(tasks);
        report("protocolo con ids aleatorios, aislamiento=" + isolation + ", candado=" + useLock,
                "administradores=" + admins() + " | " + describe(outcomes));
        return admins();
    }

    @Test
    void theProtocolWithTheLock_isSafeWithDistinctIds_underRepeatableReadAndReadCommitted() throws Exception {
        for (int round = 0; round < 5; round++) {
            assertThat(runProtocolWithRandomIds(TransactionDefinition.ISOLATION_REPEATABLE_READ, true)).isEqualTo(1);
            assertThat(runProtocolWithRandomIds(TransactionDefinition.ISOLATION_READ_COMMITTED, true)).isEqualTo(1);
        }
    }

    @Test
    void theAtomicStatementAloneUnderRepeatableRead_neverCreatesTwo_butItIsNotRelyingOnTheLock() throws Exception {
        for (int round = 0; round < 5; round++) {
            assertThat(runProtocolWithRandomIds(TransactionDefinition.ISOLATION_REPEATABLE_READ, false))
                    .as("REPEATABLE READ: nunca dos administradores (los perdedores abortan por deadlock)").isLessThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // 10. Procedimiento externo con INSERT simple (límite documentado)
    // ------------------------------------------------------------------

    @Test
    void anExternalPlainInsert_isNotExcluded_butTheBootstrapNeverCreatesMoreThanOne() throws Exception {
        InitialAdminService service = service();
        int bothExist = 0;
        for (int round = 1; round <= 10; round++) {
            cleanAdministrators();
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> provision(service, 1).name());
            }
            tasks.add(() -> {
                // Procedimiento externo que NO usa el servicio ni el candado: INSERT simple con id distinto.
                execute("insert into users (id, created_at, document_id, email, full_name, role, status, updated_at) "
                        + "values ('" + UUID.randomUUID() + "', now(), 'EXT-0001', 'externo@otra.example', "
                        + "'Externo', 'ADMIN', 'ACTIVE', now())");
                return "EXTERNAL";
            });
            runConcurrently(tasks);
            long bootstrapRows = scalar("select count(*) from users where id = ?1",
                    InitialAdminService.INITIAL_ADMIN_ID.value());
            assertThat(bootstrapRows).as("el bootstrap nunca crea más de uno").isLessThanOrEqualTo(1);
            assertThat(admins()).isBetween(1L, 2L);
            if (admins() == 2) {
                bothExist++;
            }
        }
        report("bootstrap + INSERT simple externo, 10 rondas", "rondas con 2 administradores (límite documentado): " + bothExist);
    }

    // ------------------------------------------------------------------
    // Candado: liberación ante errores y espera agotada
    // ------------------------------------------------------------------

    @Test
    void theLockIsReleasedEvenWhenTheProtectedActionFails() {
        assertThat(lockIsFree()).isTrue();
        assertThatThrownBy(() -> lock.withLock(() -> {
            throw new IllegalStateException("fallo dentro del candado");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(lockIsFree()).as("liberado tras la excepción").isTrue();
        assertThat(lock.withLock(() -> "ok")).isEqualTo("ok");
        assertThat(lockIsFree()).isTrue();
    }

    @Test
    void ifTheLockCannotBeObtained_nothingIsCreated_andTheHolderKeepsIt() throws Exception {
        SqlAdminProvisioningLock impatient = new SqlAdminProvisioningLock(dataSource, 1);
        String url = System.getenv("NEXUS_TEST_MYSQL_URL");
        try (Connection holder = DriverManager.getConnection(url, System.getenv("NEXUS_TEST_MYSQL_USER"),
                System.getenv("NEXUS_TEST_MYSQL_PASSWORD"));
             PreparedStatement take = holder.prepareStatement("select get_lock(?, 5)")) {
            take.setString(1, LOCK);
            try (ResultSet rows = take.executeQuery()) {
                assertThat(rows.next() && rows.getInt(1) == 1).isTrue();
            }
            boolean[] ran = { false };
            assertThatThrownBy(() -> impatient.withLock(() -> {
                ran[0] = true;
                return "no debería ejecutarse";
            })).isInstanceOf(InitialAdminException.class).hasMessageContaining("candado");
            assertThat(ran[0]).isFalse();
            assertThat(admins()).isZero();
            // El que esperaba NO liberó el candado del otro.
            assertThat(scalar("select is_free_lock('" + LOCK + "')")).isZero();
            try (PreparedStatement free = holder.prepareStatement("select release_lock(?)")) {
                free.setString(1, LOCK);
                free.executeQuery().close();
            }
        }
        assertThat(impatient.withLock(() -> "ok")).isEqualTo("ok");
    }

    // ------------------------------------------------------------------
    // 11. Rollback en MySQL real
    // ------------------------------------------------------------------

    @Test
    void failureWhileSavingCredentials_rollsBackTheUserInInnoDb_andReleasesTheLock() {
        PasswordServicePort oversized = new PasswordServicePort() {
            @Override
            public String hash(String rawPassword) {
                return "x".repeat(300); // excede user_credentials.password_hash varchar(100)
            }

            @Override
            public boolean verify(String rawPassword, String hashedPassword) {
                return false;
            }
        };
        InitialAdminService broken = new InitialAdminService(store, lock, oversized, transactionManager);
        assertThatThrownBy(() -> provision(broken, 1)).isInstanceOf(RuntimeException.class);
        assertThat(admins()).as("el usuario se revirtió").isZero();
        assertThat(scalar("select count(*) from user_credentials where user_id = ?1",
                InitialAdminService.INITIAL_ADMIN_ID.value())).isZero();
        assertThat(lockIsFree()).isTrue();
        // Y un intento posterior correcto funciona.
        assertThat(provision(service(), 1)).isEqualTo(InitialAdminService.Result.CREATED);
        assertThat(admins()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 8 (bis). Dos instancias COMPLETAS de la aplicación arrancando a la vez
    // ------------------------------------------------------------------

    @Test
    void twoFullApplicationInstancesStartingAtOnce_createExactlyOneAdministrator() throws Exception {
        Path file = tmp.resolve("pw");
        Files.writeString(file, PASSWORD + "\n", StandardCharsets.UTF_8);
        String[] args = {
                "--spring.datasource.url=" + System.getenv("NEXUS_TEST_MYSQL_URL"),
                "--spring.datasource.username=" + System.getenv("NEXUS_TEST_MYSQL_USER"),
                "--spring.datasource.password=" + System.getenv("NEXUS_TEST_MYSQL_PASSWORD"),
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.flyway.enabled=true", "--spring.flyway.locations=classpath:db/schema",
                "--spring.data.mongodb.uri=", "--spring.data.mongodb.database=test_audit",
                "--spring.main.banner-mode=off", "--server.port=0",
                "--" + BootstrapAdminSettings.ENABLED + "=true",
                "--" + BootstrapAdminSettings.EMAIL + "=primer.admin@tienda.example",
                "--" + BootstrapAdminSettings.FULL_NAME + "=Administradora Inicial",
                "--" + BootstrapAdminSettings.DOCUMENT_ID + "=ADM-2026-01",
                "--" + BootstrapAdminSettings.PASSWORD_FILE + "=" + file };
        cleanAdministrators();
        List<Callable<ConfigurableApplicationContext>> launches = List.of(
                () -> new SpringApplicationBuilder(MarketApplication.class).profiles("jpa")
                        .web(WebApplicationType.SERVLET).run(args),
                () -> new SpringApplicationBuilder(MarketApplication.class).profiles("jpa")
                        .web(WebApplicationType.SERVLET).run(args));
        List<Outcome<ConfigurableApplicationContext>> outcomes = runConcurrently(launches);
        try {
            assertThat(outcomes.stream().filter(o -> !o.ok()).count()).as("ambas instancias arrancan: " + describe(outcomes)).isZero();
            assertThat(admins()).isEqualTo(1);
            assertThat(scalar("select count(*) from user_credentials where user_id = ?1",
                    InitialAdminService.INITIAL_ADMIN_ID.value())).isEqualTo(1);
            assertThat(lockIsFree()).isTrue();
            report("dos instancias completas a la vez", "administradores=" + admins());
        } finally {
            outcomes.stream().filter(Outcome::ok).forEach(o -> o.value().close());
        }
    }

    // ------------------------------------------------------------------
    // 9. Coexistencia con los casos de uso en READ_COMMITTED
    // ------------------------------------------------------------------

    @Test
    void bootstrapCoexistsWithOrderInventoryAndRegistrationUseCases_withoutDeadlocksOrErrors() throws Exception {
        SellerCatalog ordersCatalog = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000.00"));
        SellerCatalog stockCatalog = seedSellerCatalog(staffToken(SystemRole.ADMIN), new BigDecimal("1000.00"));
        List<BuyerId> buyers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            BuyerId buyer = BuyerId.of(registerBuyer().profileId());
            cartUseCases.addItem(buyer, ProductId.of(ordersCatalog.productId()), Quantity.of(1));
            buyers.add(buyer);
        }
        InventoryId inventory = InventoryId.of(stockCatalog.inventoryId());
        inventoryUseCases.reserve(inventory, Quantity.of(5));
        cleanAdministrators(); // el seed creó administradores de prueba con staffToken: se parte sin ninguno
        InitialAdminService service = service();

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> "BOOT:" + provision(service, 1).name());
        }
        for (BuyerId buyer : buyers) { // READ_COMMITTED + FOR UPDATE
            tasks.add(() -> {
                orderUseCases.createOrder(buyer, ADDRESS);
                return "ORDER";
            });
        }
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> {
                inventoryUseCases.reserve(inventory, Quantity.of(1));
                return "RESERVE";
            });
            tasks.add(() -> {
                inventoryUseCases.release(inventory, Quantity.of(1));
                return "RELEASE";
            });
        }
        for (int i = 0; i < 6; i++) { // altas de usuarios: escriben en la misma tabla users que lee el bootstrap
            int n = i;
            tasks.add(() -> {
                String suffix = UUID.randomUUID().toString().substring(0, 8);
                authUseCases.registerBuyer(FullName.of("Comprador " + n), Email.of("co-" + suffix + "@mail.test"),
                        DocumentId.of("CC-" + suffix), ADDRESS, "Secreta123!");
                return "REGISTER";
            });
        }

        List<Outcome<String>> outcomes = runConcurrently(tasks);

        long bootstrapOk = outcomes.stream().filter(o -> o.ok() && o.value().startsWith("BOOT:")).count();
        long created = outcomes.stream().filter(o -> o.ok() && "BOOT:CREATED".equals(o.value())).count();
        long orders = outcomes.stream().filter(o -> o.ok() && "ORDER".equals(o.value())).count();
        long registrations = outcomes.stream().filter(o -> o.ok() && "REGISTER".equals(o.value())).count();
        long failures = outcomes.stream().filter(o -> !o.ok()).count();
        String errors = outcomes.stream().filter(o -> !o.ok())
                .map(o -> o.error().getClass().getSimpleName()).distinct().toList().toString();
        report("bootstrap + pedidos(RC) + inventario(RC) + altas", "bootstrap ok=" + bootstrapOk + " creados=" + created
                + " | pedidos aceptados=" + orders + "/20 | altas=" + registrations + "/6 | fallos=" + failures + " " + errors
                + " | administradores=" + admins());
        assertThat(created).isEqualTo(1);
        assertThat(bootstrapOk).as("los 8 aprovisionamientos terminan sin error").isEqualTo(8);
        assertThat(admins()).isEqualTo(1);
        assertThat(registrations).as("las altas simultáneas no se ven afectadas").isEqualTo(6);
        assertThat(orders).as("pedidos con stock 10 de 20: exactamente 10").isEqualTo(10);
        // Los únicos fallos permitidos son los pedidos sin stock (error de negocio), nunca un error de base de datos.
        assertThat(outcomes.stream().filter(o -> !o.ok() && hasSqlCause(o.error())).count())
                .as("sin errores de base de datos ni deadlocks: " + errors).isZero();
        assertThat(lockIsFree()).isTrue();
    }

    private static boolean hasSqlCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException) {
                return true;
            }
        }
        return false;
    }
}
