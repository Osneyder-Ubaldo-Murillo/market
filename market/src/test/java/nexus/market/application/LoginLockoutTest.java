package nexus.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import nexus.market.domain.models.User;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;

/** Conteo de fallos consecutivos que dispara el bloqueo de la cuenta (3 por defecto) y el estado de verificación del correo. */
class LoginLockoutTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-30T10:00:00Z"));
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

    private static final String A = "10.0.0.1";
    private static final String B = "10.0.0.2";

    @Test
    void thirdConsecutiveFailureFromTwoOrigins_reachesTheLimit_andTheCountRestarts() {
        LoginLockout lockout = new LoginLockout(clock, 3, 2);
        assertThat(lockout.registerFailure("ana@example.com", A)).isFalse();
        assertThat(lockout.registerFailure("ana@example.com", A)).isFalse();
        assertThat(lockout.registerFailure("ana@example.com", B)).isTrue();
        // Tras bloquear, el conteo vuelve a cero (si un ADMIN la reactiva, tiene intentos nuevos).
        assertThat(lockout.registerFailure("ana@example.com", A)).isFalse();
    }

    @Test
    void aSingleOrigin_neverLocksAnAccount_howeverManyFailuresItAccumulates() {
        LoginLockout lockout = new LoginLockout(clock, 3, 2);
        for (int i = 0; i < 50; i++) {
            assertThat(lockout.registerFailure("ana@example.com", A)).as("fallo %d", i).isFalse();
        }
        // En cuanto falla otro origen, el conteo acumulado ya supera el tope: se bloquea.
        assertThat(lockout.registerFailure("ana@example.com", B)).isTrue();
    }

    @Test
    void theMinimumOfOriginsIsConfigurable() {
        LoginLockout oneOrigin = new LoginLockout(clock, 3, 1);
        oneOrigin.registerFailure("ana@example.com", A);
        oneOrigin.registerFailure("ana@example.com", A);
        assertThat(oneOrigin.registerFailure("ana@example.com", A)).isTrue();
    }

    @Test
    void successfulLogin_resetsTheCount_andAccountsAreIndependent() {
        LoginLockout lockout = new LoginLockout(clock, 3, 2);
        lockout.registerFailure("ana@example.com", A);
        lockout.registerFailure("ana@example.com", B);
        lockout.reset("ANA@example.com "); // misma cuenta: sin distinguir mayúsculas ni espacios
        assertThat(lockout.registerFailure("ana@example.com", A)).isFalse();
        assertThat(lockout.registerFailure("otra@example.com", A)).isFalse();
        assertThat(lockout.registerFailure("otra@example.com", B)).isFalse();
        assertThat(lockout.registerFailure("otra@example.com", A)).isTrue();
    }

    @Test
    void failuresFarApart_doNotAccumulate() {
        LoginLockout lockout = new LoginLockout(clock, 3, 2);
        lockout.registerFailure("ana@example.com", A);
        lockout.registerFailure("ana@example.com", B);
        now.set(now.get().plus(Duration.ofMinutes(LoginLockout.WINDOW_MINUTES)));
        assertThat(lockout.registerFailure("ana@example.com", A)).isFalse(); // ventana nueva
    }

    @Test
    void concurrentFailures_reachTheLimitExactlyOnce() throws Exception {
        LoginLockout lockout = new LoginLockout(clock, 3, 2);
        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reached = new AtomicInteger();
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String origin = "10.0.0." + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (lockout.registerFailure("ana@example.com", origin)) {
                        reached.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(reached.get()).isEqualTo(1);
    }

    @Test
    void invalidLimit_isRejected() {
        assertThatThrownBy(() -> new LoginLockout(clock, 0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginLockout(clock, 3, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registeredUsers_startUnverified_andVerifyingIsIdempotent() {
        User pending = User.createPendingVerification(FullName.of("Ana Pérez"), Email.of("ana@example.com"),
                DocumentId.of("CC-1"), SystemRole.BUYER);
        assertThat(pending.isEmailVerified()).isFalse();
        pending.verifyEmail();
        assertThat(pending.isEmailVerified()).isTrue();
        var firstDate = pending.getEmailVerifiedAt();
        pending.verifyEmail();
        assertThat(pending.getEmailVerifiedAt()).isEqualTo(firstDate);
        // El personal creado fuera del registro público nace verificado.
        assertThat(User.create(FullName.of("Op Uno"), Email.of("op@example.com"), DocumentId.of("OP-1"),
                SystemRole.LOGISTICS_OPERATOR).isEmailVerified()).isTrue();
    }
}
