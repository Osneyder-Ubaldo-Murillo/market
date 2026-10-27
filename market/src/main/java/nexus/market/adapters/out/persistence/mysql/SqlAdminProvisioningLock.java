package nexus.market.adapters.out.persistence.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.application.AdminProvisioningLockPort;
import nexus.market.application.InitialAdminException;

/**
 * Candado de aprovisionamiento de administradores.
 *
 * <p><b>MySQL/MariaDB</b>: {@code GET_LOCK} en una conexión dedicada tomada del pool ANTES de que el
 * llamador abra su transacción (que usa otra conexión). Se libera siempre en {@code finally}
 * ({@code RELEASE_LOCK}; si falla, {@code RELEASE_ALL_LOCKS}; si tampoco, la conexión se expulsa del pool
 * para que no devuelva a este un candado retenido). El candado es de la conexión, no de la transacción.</p>
 *
 * <p><b>Otros motores (H2 en pruebas)</b>: un candado de JVM; no protege entre procesos.</p>
 */
@Component
@Profile("jpa")
public class SqlAdminProvisioningLock implements AdminProvisioningLockPort {

    private static final Logger log = LoggerFactory.getLogger(SqlAdminProvisioningLock.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final ReentrantLock JVM_LOCK = new ReentrantLock(true);

    private final DataSource dataSource;
    private final int timeoutSeconds;

    @Autowired
    public SqlAdminProvisioningLock(DataSource dataSource) {
        this(dataSource, DEFAULT_TIMEOUT_SECONDS);
    }

    public SqlAdminProvisioningLock(DataSource dataSource, int timeoutSeconds) {
        this.dataSource = dataSource;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public <T> T withLock(Supplier<T> action) {
        // Antes de tomar NINGUNA conexión: el candado retiene una y la transacción necesita otra.
        PoolCapacity.requireEnoughConnections(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            if (!supportsNamedLocks(connection)) {
                return withJvmLock(action);
            }
            // Desde que se envía GET_LOCK, el candado PUEDE estar tomado: se libera siempre en el finally,
            // incluso si la lectura de la respuesta o la acción fallan (una conexión del pool no puede volver
            // a él reteniendo el candado).
            boolean mayHoldLock = true;
            try {
                if (!acquire(connection)) {
                    mayHoldLock = false;
                    throw new InitialAdminException("No se obtuvo el candado de aprovisionamiento del administrador en "
                            + timeoutSeconds + " s (otra instancia lo mantiene); no se crea nada.");
                }
                return action.get();
            } finally {
                if (mayHoldLock) {
                    release(connection);
                }
            }
        } catch (SQLException failure) {
            throw new InitialAdminException("No se pudo tomar el candado de aprovisionamiento del administrador.",
                    failure);
        }
    }

    /** @return {@code true} si se obtuvo; {@code false} si venció la espera */
    private boolean acquire(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, timeoutSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                // MySQL devuelve un BIGINT (Long): se lee como número, no se castea.
                return rows.next() && rows.getLong(1) == 1L && !rows.wasNull();
            }
        }
    }

    private void release(Connection connection) {
        boolean released = false;
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet rows = statement.executeQuery()) {
                released = rows.next() && rows.getLong(1) == 1L && !rows.wasNull();
            }
        } catch (SQLException failure) {
            log.warn("No se pudo liberar el candado con RELEASE_LOCK: {}", failure.getMessage());
        }
        if (released) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_ALL_LOCKS()")) {
            statement.executeQuery().close();
            return;
        } catch (SQLException failure) {
            log.error("No se pudo liberar el candado con RELEASE_ALL_LOCKS: {}", failure.getMessage());
        }
        // Último recurso: que la conexión no vuelva al pool con el candado retenido.
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.evictConnection(connection);
        }
    }

    private static boolean supportsNamedLocks(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (product == null) {
            return false;
        }
        String lower = product.toLowerCase();
        return lower.contains("mysql") || lower.contains("mariadb");
    }

    private <T> T withJvmLock(Supplier<T> action) {
        try {
            if (!JVM_LOCK.tryLock(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new InitialAdminException("No se obtuvo el candado de aprovisionamiento del administrador en "
                        + timeoutSeconds + " s; no se crea nada.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InitialAdminException("Interrumpido al esperar el candado de aprovisionamiento.", interrupted);
        }
        try {
            return action.get();
        } finally {
            JVM_LOCK.unlock();
        }
    }
}
