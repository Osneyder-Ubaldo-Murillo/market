package nexus.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * El pool de producción tal como lo RESUELVE Spring Boot (application.properties + application-prod.properties reales
 * + {@code DataSourceAutoConfiguration}), no solo lo escrito en el archivo. {@code HikariDataSource} se crea sin
 * arrancar el pool (no se abre ninguna conexión).
 */
class ProductionPoolConfigurationTest {

    private ApplicationContextRunner prodDataSource() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues("spring.profiles.active=prod",
                        "SPRING_DATASOURCE_URL=jdbc:h2:mem:prod_pool_resolution",
                        "SPRING_DATASOURCE_USERNAME=sa",
                        "SPRING_DATASOURCE_PASSWORD=no-se-usa");
    }

    @Test
    void connectionTimeout_resolvesToTenSeconds_inTheRealDataSource() {
        prodDataSource().run(context -> {
            assertThat(context).hasNotFailed();
            HikariDataSource pool = context.getBean(HikariDataSource.class);
            assertThat(pool.getConnectionTimeout()).as("connection-timeout efectivo").isEqualTo(10_000L);
            assertThat(pool.isRunning()).as("no se abrió ninguna conexión").isFalse();
        });
    }

    @Test
    void theRestOfThePoolSettingsAreUnchanged_andConsistentWithTheTimeout() {
        prodDataSource().run(context -> {
            HikariDataSource pool = context.getBean(HikariDataSource.class);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(20);
            assertThat(pool.getMinimumIdle()).isEqualTo(5);
            assertThat(pool.getIdleTimeout()).isEqualTo(600_000L);
            assertThat(pool.getMaxLifetime()).isEqualTo(1_800_000L);
            assertThat(pool.getConnectionInitSql()).isEqualTo("SET SESSION innodb_lock_wait_timeout=10");
            // La espera por una conexión no es menor que una espera de bloqueo completa (10 s).
            assertThat(pool.getConnectionTimeout()).isGreaterThanOrEqualTo(10_000L);
            // Hikari exige validationTimeout <= connectionTimeout.
            assertThat(pool.getValidationTimeout()).isLessThanOrEqualTo(pool.getConnectionTimeout());
        });
    }

    @Test
    void anExplicitOverrideStillWins_usingTheCorrectPropertyName() {
        prodDataSource().withPropertyValues("spring.datasource.hikari.connection-timeout=4000").run(context ->
                assertThat(context.getBean(HikariDataSource.class).getConnectionTimeout()).isEqualTo(4_000L));
    }
}
