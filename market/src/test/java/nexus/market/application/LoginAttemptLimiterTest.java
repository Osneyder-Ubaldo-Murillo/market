package nexus.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Límite de login por email y por cliente: ventana de 15 minutos, 429 con Retry-After y reinicio con login correcto. */
class LoginAttemptLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-29T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private LoginAttemptLimiter limiter(int perEmail, int perClient) {
        return new LoginAttemptLimiter(clock, perEmail, perClient, Duration.ofMinutes(15));
    }

    @Test
    void emailExceedingItsQuota_isRejectedWithRetryAfter_evenFromAnotherClient() {
        LoginAttemptLimiter limiter = limiter(3, 100);
        for (int i = 0; i < 3; i++) {
            limiter.acquire("10.0.0.1", "ana@example.com");
        }
        assertThatThrownBy(() -> limiter.acquire("10.0.0.2", "ana@example.com"))
                .isInstanceOf(TooManyAttemptsException.class)
                .satisfies(e -> assertThat(((TooManyAttemptsException) e).retryAfterSeconds()).isBetween(1L, 901L));
    }

    private LoginAttemptLimiter pairLimiter(int perClientAndEmail) {
        return new LoginAttemptLimiter(clock, 100, 100, perClientAndEmail, Duration.ofMinutes(15));
    }

    @Test
    void oneOrigin_cannotExceedItsQuotaForAnAccount_andDoesNotBurnTheEmailQuota() {
        LoginAttemptLimiter limiter = pairLimiter(3);
        for (int i = 0; i < 3; i++) {
            limiter.acquire("10.0.0.1", "victima@example.com");
        }
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> limiter.acquire("10.0.0.1", "victima@example.com"))
                    .isInstanceOf(TooManyAttemptsException.class);
        }
        // Otro origen sigue teniendo su propio cupo para esa cuenta, y para otras cuentas desde el mismo origen.
        assertThatCode(() -> limiter.acquire("10.0.0.2", "victima@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire("10.0.0.1", "otra@example.com")).doesNotThrowAnyException();
    }

    @Test
    void successfulLogin_resetsThePairQuota() {
        LoginAttemptLimiter limiter = pairLimiter(2);
        limiter.acquire("10.0.0.1", "ana@example.com");
        limiter.acquire("10.0.0.1", "ana@example.com");
        limiter.reset("10.0.0.1", "ana@example.com");
        assertThatCode(() -> limiter.acquire("10.0.0.1", "ana@example.com")).doesNotThrowAnyException();
    }

    @Test
    void emailKey_ignoresCaseAndSurroundingSpaces() {
        LoginAttemptLimiter limiter = limiter(2, 100);
        limiter.acquire("10.0.0.1", "Ana@Example.com");
        limiter.acquire("10.0.0.1", " ana@example.com ");
        assertThatThrownBy(() -> limiter.acquire("10.0.0.1", "ANA@EXAMPLE.COM"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void clientExceedingItsQuota_isRejected_whateverTheEmail() {
        LoginAttemptLimiter limiter = limiter(100, 3);
        for (int i = 0; i < 3; i++) {
            limiter.acquire("10.0.0.1", "user" + i + "@example.com");
        }
        assertThatThrownBy(() -> limiter.acquire("10.0.0.1", "otro@example.com"))
                .isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> limiter.acquire("10.0.0.9", "otro@example.com")).doesNotThrowAnyException();
    }

    @Test
    void blockedClient_doesNotConsumeTheEmailQuota() {
        LoginAttemptLimiter limiter = limiter(2, 1);
        limiter.acquire("10.0.0.1", "victima@example.com");
        // Un cliente ya bloqueado no puede agotar el cupo de la víctima insistiendo.
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> limiter.acquire("10.0.0.1", "victima@example.com"))
                    .isInstanceOf(TooManyAttemptsException.class);
        }
        assertThatCode(() -> limiter.acquire("10.0.0.2", "victima@example.com")).doesNotThrowAnyException();
    }

    @Test
    void quotaIsRestoredWhenTheWindowEnds() {
        LoginAttemptLimiter limiter = limiter(1, 100);
        limiter.acquire("10.0.0.1", "ana@example.com");
        assertThatThrownBy(() -> limiter.acquire("10.0.0.1", "ana@example.com"))
                .isInstanceOf(TooManyAttemptsException.class);
        now.set(now.get().plus(Duration.ofMinutes(15)));
        assertThatCode(() -> limiter.acquire("10.0.0.1", "ana@example.com")).doesNotThrowAnyException();
    }

    @Test
    void successfulLogin_resetsTheEmailCounter_butNotTheClientCounter() {
        LoginAttemptLimiter limiter = limiter(2, 3);
        limiter.acquire("10.0.0.1", "ana@example.com");
        limiter.acquire("10.0.0.1", "ana@example.com");
        limiter.reset("ana@example.com");
        // El email vuelve a tener cupo, pero el cliente ya gastó 2 de 3 y este es el tercero.
        limiter.acquire("10.0.0.1", "ana@example.com");
        assertThatThrownBy(() -> limiter.acquire("10.0.0.1", "ana@example.com"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void unknownClientAddress_isGroupedUnderOneKey_insteadOfFailing() {
        LoginAttemptLimiter limiter = limiter(100, 1);
        limiter.acquire(null, "a@example.com");
        assertThatThrownBy(() -> limiter.acquire(" ", "b@example.com")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void invalidConfiguration_isRejected() {
        assertThatThrownBy(() -> new LoginAttemptLimiter(clock, 0, 10, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter(clock, 10, 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
