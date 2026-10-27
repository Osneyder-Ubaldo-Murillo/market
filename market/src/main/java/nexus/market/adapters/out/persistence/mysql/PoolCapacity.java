package nexus.market.adapters.out.persistence.mysql;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import nexus.market.application.InitialAdminException;

/**
 * Capacidad del pool de conexiones EFECTIVO, leída del propio {@code DataSource} sin abrir ninguna conexión.
 *
 * <p>El aprovisionamiento del administrador necesita dos conexiones a la vez: una dedicada al candado
 * ({@code GET_LOCK}, por conexión) y otra para la transacción de creación. Con un pool de una conexión, la
 * transacción esperaría {@code connectionTimeout} (30 s) y el arranque fallaría tarde y con un mensaje de Hikari.</p>
 *
 * <p>Limitación: solo se sabe leer el tamaño de un pool Hikari ({@code spring.datasource.hikari.*}, el que
 * configura Spring Boot aquí). Con cualquier otro {@code DataSource} no se puede demostrar la capacidad y el
 * aprovisionamiento se rechaza (falla cerrado) en lugar de suponerla.</p>
 */
public final class PoolCapacity {

    /** Conexiones simultáneas que necesita el aprovisionamiento. */
    public static final int REQUIRED_CONNECTIONS = 2;

    private PoolCapacity() {
    }

    /**
     * @throws InitialAdminException si el pool efectivo permite menos de {@link #REQUIRED_CONNECTIONS} conexiones o
     *                               si su tamaño no se puede determinar
     */
    public static void requireEnoughConnections(DataSource dataSource) {
        HikariDataSource hikari = asHikari(dataSource);
        if (hikari == null) {
            throw new InitialAdminException("No se puede verificar el tamaño del pool de conexiones (solo se admite "
                    + "HikariCP) y el administrador inicial necesita al menos " + REQUIRED_CONNECTIONS
                    + " conexiones simultáneas; no se crea nada.");
        }
        int maximum = hikari.getMaximumPoolSize(); // getter de configuración: no inicializa el pool ni conecta
        if (maximum < REQUIRED_CONNECTIONS) {
            throw new InitialAdminException(message(maximum));
        }
    }

    /** Mensaje común (sin secretos): el requisito y cómo cumplirlo. */
    public static String message(int effectiveMaximum) {
        return "El administrador inicial necesita al menos " + REQUIRED_CONNECTIONS
                + " conexiones simultáneas del pool (una dedicada al candado GET_LOCK y otra para la transacción), "
                + "pero el pool efectivo permite " + effectiveMaximum + ". Configure "
                + "spring.datasource.hikari.maximum-pool-size >= " + REQUIRED_CONNECTIONS
                + " o desactive NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED.";
    }

    private static HikariDataSource asHikari(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari;
        }
        try {
            if (dataSource != null && dataSource.isWrapperFor(HikariDataSource.class)) {
                return dataSource.unwrap(HikariDataSource.class);
            }
        } catch (java.sql.SQLException | RuntimeException cannotUnwrap) {
            // no se puede determinar: se trata como no verificable
        }
        return null;
    }
}
