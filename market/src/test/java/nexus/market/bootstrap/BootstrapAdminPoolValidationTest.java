package nexus.market.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import nexus.market.adapters.out.persistence.mysql.PoolCapacity;
import nexus.market.adapters.out.persistence.mysql.SqlAdminProvisioningLock;
import nexus.market.application.InitialAdminException;
import nexus.market.application.InitialAdminService;
import nexus.market.infrastructure.bootstrap.BootstrapAdminConfiguration;
import nexus.market.infrastructure.bootstrap.BootstrapAdminConfigurationException;
import nexus.market.infrastructure.bootstrap.BootstrapAdminSettings;

/**
 * El aprovisionamiento necesita DOS conexiones simultáneas (candado dedicado + transacción). Se valida la
 * configuración efectiva del pool pronto, sin abrir conexiones, y solo cuando el mecanismo está habilitado.
 */
class BootstrapAdminPoolValidationTest {

    private static final String POOL = BootstrapAdminSettings.POOL_SIZE_PROPERTY;

    // ------------------------------------------------------------------
    // Capa 1: configuración efectiva (antes de crear ningún bean)
    // ------------------------------------------------------------------

    @Test
    void absentProperty_meansHikariDefaultOfTen_andPasses() {
        assertThatCode(() -> BootstrapAdminSettings.validatePoolConfiguration(new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    @Test
    void poolOfOne_isRejected_withAClearRequirementMessage() {
        MockEnvironment env = new MockEnvironment().withProperty(POOL, "1");
        assertThatThrownBy(() -> BootstrapAdminSettings.validatePoolConfiguration(env))
                .isInstanceOf(BootstrapAdminConfigurationException.class)
                .hasMessageContaining("al menos 2 conexiones simultáneas")
                .hasMessageContaining(POOL)
                .hasMessageContaining(BootstrapAdminSettings.ENABLED);
    }

    @Test
    void poolOfTwoOrMore_passes() {
        for (String size : new String[] { "2", "3", "20" }) {
            assertThatCode(() -> BootstrapAdminSettings.validatePoolConfiguration(
                    new MockEnvironment().withProperty(POOL, size))).as(size).doesNotThrowAnyException();
        }
    }

    @Test
    void zeroNegativeOrNonNumericValues_areExplicitlyRejected_notSilentlyReplaced() {
        for (String bad : new String[] { "0", "-1" }) {
            assertThatThrownBy(() -> BootstrapAdminSettings.validatePoolConfiguration(
                    new MockEnvironment().withProperty(POOL, bad)))
                    .as(bad).isInstanceOf(BootstrapAdminConfigurationException.class)
                    .hasMessageContaining("al menos 2");
        }
        assertThatThrownBy(() -> BootstrapAdminSettings.validatePoolConfiguration(
                new MockEnvironment().withProperty(POOL, "muchas")))
                .isInstanceOf(BootstrapAdminConfigurationException.class)
                .hasMessageContaining(POOL).hasMessageContaining("número entero");
    }

    @Test
    void theEffectiveConfigurationIsResolved_notJustTheCanonicalKey() {
        // Otra grafía de la misma propiedad (relaxed binding).
        MockEnvironment camel = new MockEnvironment().withProperty("spring.datasource.hikari.maximumPoolSize", "1");
        assertThatThrownBy(() -> BootstrapAdminSettings.validatePoolConfiguration(camel))
                .isInstanceOf(BootstrapAdminConfigurationException.class);

        // Variable de entorno SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE (como en Docker/Compose).
        MockEnvironment fromEnvironmentVariable = new MockEnvironment();
        fromEnvironmentVariable.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "1")));
        assertThatThrownBy(() -> BootstrapAdminSettings.validatePoolConfiguration(fromEnvironmentVariable))
                .isInstanceOf(BootstrapAdminConfigurationException.class)
                .hasMessageContaining("vale 1");

        // Un valor con marcador resuelto desde otra propiedad.
        MockEnvironment placeholder = new MockEnvironment()
                .withProperty(POOL, "${tamano.pool}").withProperty("tamano.pool", "1");
        assertThatThrownBy(() -> BootstrapAdminSettings.validatePoolConfiguration(placeholder))
                .isInstanceOf(BootstrapAdminConfigurationException.class);
    }

    @Test
    void anUnresolvablePlaceholder_isAnExplicitFailure_notAnAssumption() {
        MockEnvironment env = new MockEnvironment().withProperty(POOL, "${no.existe}");
        assertThatThrownBy(() -> BootstrapAdminSettings.validatePoolConfiguration(env))
                .isInstanceOf(BootstrapAdminConfigurationException.class)
                .hasMessageContaining(POOL);
    }

    @Test
    void aNonHikariPoolType_cannotBeJudgedFromConfiguration_andIsLeftToTheRealDataSourceCheck() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.type", "org.apache.commons.dbcp2.BasicDataSource")
                .withProperty(POOL, "1");
        assertThatCode(() -> BootstrapAdminSettings.validatePoolConfiguration(env)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Activación: solo con el mecanismo habilitado, y antes de crear beans
    // ------------------------------------------------------------------

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BootstrapAdminConfiguration.class)
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("jpa"));

    private static String rootMessages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        return all.toString();
    }

    private static String[] validBootstrapVariables(String... extra) {
        String[] base = {
                BootstrapAdminSettings.ENABLED + "=true",
                BootstrapAdminSettings.EMAIL + "=primer.admin@tienda.example",
                BootstrapAdminSettings.FULL_NAME + "=Administradora Inicial",
                BootstrapAdminSettings.DOCUMENT_ID + "=ADM-2026-01",
                BootstrapAdminSettings.PASSWORD + "=Rk7#pQzLm2!vXw9T" };
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    void disabled_withAPoolOfOne_doesNotActivateAnyBootstrapValidation() {
        runner.withPropertyValues(POOL + "=1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(InitialAdminService.class);
        });
        runner.withPropertyValues(BootstrapAdminSettings.ENABLED + "=false", POOL + "=1").run(context ->
                assertThat(context).hasNotFailed());
    }

    @Test
    void enabled_withAPoolOfOne_failsEarly_withAComprehensibleMessage_andNoSecrets() {
        runner.withPropertyValues(validBootstrapVariables(POOL + "=1")).run(context -> {
            assertThat(context).hasFailed();
            String message = rootMessages(context.getStartupFailure());
            assertThat(message).contains("al menos 2 conexiones simultáneas").contains(POOL)
                    .doesNotContain("Rk7#pQzLm2!vXw9T").doesNotContain("primer.admin@tienda.example");
        });
    }

    @Test
    void enabled_withAPoolOfTwo_theCapacityValidationPasses() {
        // El contexto de este test no tiene los puertos de persistencia y por eso acaba fallando por otra razón;
        // lo que se comprueba es que NO falla por la capacidad del pool.
        runner.withPropertyValues(validBootstrapVariables(POOL + "=2")).run(context -> {
            if (context.getStartupFailure() != null) {
                assertThat(rootMessages(context.getStartupFailure())).doesNotContain("conexiones simultáneas");
            }
        });
    }

    @Test
    void thePoolProblemIsReportedTogetherWithTheOtherConfigurationProblems() {
        runner.withPropertyValues(BootstrapAdminSettings.ENABLED + "=true", POOL + "=1").run(context -> {
            String message = rootMessages(context.getStartupFailure());
            assertThat(message).contains("al menos 2 conexiones").contains(BootstrapAdminSettings.EMAIL);
        });
    }

    // ------------------------------------------------------------------
    // Capa 2: el DataSource real, sin abrir conexiones
    // ------------------------------------------------------------------

    private static HikariDataSource lazyPool(int maximum) {
        HikariDataSource pool = new HikariDataSource(); // constructor sin configuración: el pool NO arranca
        pool.setJdbcUrl("jdbc:h2:mem:pool_capacity_" + maximum + ";DB_CLOSE_DELAY=-1");
        pool.setMaximumPoolSize(maximum);
        return pool;
    }

    @Test
    void theRealPoolIsInspectedWithoutOpeningAnyConnection() {
        try (HikariDataSource one = lazyPool(1); HikariDataSource two = lazyPool(2)) {
            assertThatThrownBy(() -> PoolCapacity.requireEnoughConnections(one))
                    .isInstanceOf(InitialAdminException.class)
                    .hasMessageContaining("al menos 2 conexiones simultáneas")
                    .hasMessageContaining("pool efectivo permite 1");
            assertThatCode(() -> PoolCapacity.requireEnoughConnections(two)).doesNotThrowAnyException();
            assertThat(one.isRunning()).as("no se abrió ninguna conexión").isFalse();
            assertThat(two.isRunning()).isFalse();
        }
    }

    @Test
    void aDataSourceThatIsNotHikari_cannotBeVerified_andIsRejected() {
        DriverManagerDataSource plain = new DriverManagerDataSource("jdbc:h2:mem:no_hikari");
        assertThatThrownBy(() -> PoolCapacity.requireEnoughConnections(plain))
                .isInstanceOf(InitialAdminException.class)
                .hasMessageContaining("No se puede verificar").hasMessageContaining("HikariCP");
    }

    @Test
    void theLock_refusesToWorkWithAPoolOfOne_beforeTakingAnyConnection_andRunsNothing() {
        try (HikariDataSource one = lazyPool(1)) {
            AtomicBoolean ran = new AtomicBoolean(false);
            assertThatThrownBy(() -> new SqlAdminProvisioningLock(one, 1).withLock(() -> {
                ran.set(true);
                return "no debería ejecutarse";
            })).isInstanceOf(InitialAdminException.class).hasMessageContaining("al menos 2 conexiones");
            assertThat(ran).isFalse();
            assertThat(one.isRunning()).as("ni siquiera se inicializó el pool").isFalse();
        }
    }
}
