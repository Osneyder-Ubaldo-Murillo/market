package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.JDBCConnectionException;
import org.hibernate.exception.SQLGrammarException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.exceptions.InsufficientInventoryException;

/**
 * Qué se considera "base de datos no disponible" (503) y qué NO: solo fallos de conectividad. Los errores de negocio,
 * de validación, de programación, de integridad y de autenticación contra MySQL no se clasifican como
 * indisponibilidad.
 */
class DatabaseUnavailabilityTest {

    private static boolean unavailable(Throwable error) {
        return DatabaseUnavailability.isUnavailable(error);
    }

    @Test
    void connectivityFailures_areUnavailability() {
        // Hikari agotó la espera por una conexión (MySQL detenido): la causa real del 500 tras ~31 s.
        assertThat(unavailable(new PersistenceException("Unable to acquire JDBC Connection",
                new JDBCConnectionException("Unable to acquire JDBC Connection",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 30001ms"))))).isTrue();
        assertThat(unavailable(new SQLTransientConnectionException("pool"))).isTrue();
        assertThat(unavailable(new SQLNonTransientConnectionException("closed"))).isTrue();
        assertThat(unavailable(new SQLRecoverableException("Communications link failure"))).isTrue();
        assertThat(unavailable(new CannotCreateTransactionException("tx",
                new SQLTransientConnectionException("pool")))).isTrue();
        assertThat(unavailable(new CannotGetJdbcConnectionException("jdbc", new SQLException("down", "08S01")))).isTrue();
        assertThat(unavailable(new RuntimeException("w", new SQLException("Too many connections", "08004", 1040)))).isTrue();
        assertThat(unavailable(new IllegalStateException("w", new SQLException("io", "HY000", new ConnectException("refused"))))).isTrue();
    }

    @Test
    void businessAndValidationErrors_areNotUnavailability() {
        assertThat(unavailable(new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."))).isFalse();
        assertThat(unavailable(new InsufficientInventoryException("sin stock"))).isFalse();
        assertThat(unavailable(new IllegalArgumentException("campo inválido"))).isFalse();
        assertThat(unavailable(new HttpMessageNotReadableException("json", new java.io.IOException("x"), null))).isFalse();
    }

    @Test
    void programmingErrors_areNotUnavailability() {
        assertThat(unavailable(new NullPointerException())).isFalse();
        assertThat(unavailable(new IllegalStateException("bug"))).isFalse();
        assertThat(unavailable(new ClassCastException())).isFalse();
        assertThat(unavailable(new PersistenceException("mapeo", new SQLGrammarException("sql", new SQLSyntaxErrorException("syntax", "42000", 1064))))).isFalse();
        assertThat(unavailable(new ArithmeticException("overflow"))).isFalse();
    }

    @Test
    void dataIntegrityLockConflictAndCredentialErrors_areNotUnavailability() {
        assertThat(unavailable(new PersistenceException("dup", new ConstraintViolationException("dup",
                new SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062), "uk")))).isFalse();
        assertThat(unavailable(new DataIntegrityViolationException("data too long",
                new SQLException("Data too long for column", "22001", 1406)))).isFalse();
        assertThat(unavailable(new PessimisticLockException("lock"))).isFalse();
        assertThat(unavailable(new SQLException("Deadlock found", "40001", 1213))).isFalse();
        assertThat(unavailable(new SQLException("Access denied for user", "28000", 1045))).isFalse();
        assertThat(unavailable(new SQLTimeoutException("statement timeout"))).isFalse();
    }

    @Test
    void aNetworkErrorWithoutADatabaseCause_isNotAttributedToTheDatabase() {
        // Una caída de otro servicio (pasarela, Mongo...) no es "MySQL no disponible".
        assertThat(unavailable(new RuntimeException("pasarela", new ConnectException("refused")))).isFalse();
    }

    @Test
    void theCauseChainDescription_containsClassNamesOnly_neverMessages() {
        String chain = DatabaseUnavailability.causeChain(new PersistenceException("jdbc:mysql://secreto-host:3306 usuario=nexus",
                new SQLTransientConnectionException("password=hunter2")));
        assertThat(chain).contains("PersistenceException").contains("SQLTransientConnectionException")
                .doesNotContain("secreto-host").doesNotContain("hunter2").doesNotContain("jdbc");
    }
}
