package nexus.market.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import nexus.market.adapters.in.rest.responses.UserResponses.ErrorResponse;

/**
 * Un tiempo de espera de bloqueo o un deadlock se responde 409 reintentable (formato de la API), nunca como
 * 500; el resto de excepciones inesperadas siguen siendo 500 sin detalles.
 */
class LockConflictMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ResponseEntity<ErrorResponse> handle(Exception error) {
        return handler.handleUnexpected(error);
    }

    @Test
    void lockTimeoutAndDeadlock_are409_inApiFormat() {
        Exception[] conflicts = {
                new PessimisticLockException("lock"),
                new LockTimeoutException("timeout"),
                new PersistenceException("wrapper", new SQLException("Deadlock found", "40001", 1213)),
                new IllegalStateException("x", new SQLException("Lock wait timeout exceeded", "HY000", 1205))
        };
        for (Exception conflict : conflicts) {
            ResponseEntity<ErrorResponse> response = handle(conflict);
            assertThat(response.getStatusCode()).as(conflict.toString()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().code()).isEqualTo("CONCURRENT_MODIFICATION");
            assertThat(response.getBody().message()).doesNotContain("Deadlock").doesNotContain("SQL");
        }
    }

    @Test
    void otherUnexpectedErrors_stayInternalErrors() {
        ResponseEntity<ErrorResponse> response =
                handle(new PersistenceException("otro", new SQLException("Duplicate entry", "23000", 1062)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
