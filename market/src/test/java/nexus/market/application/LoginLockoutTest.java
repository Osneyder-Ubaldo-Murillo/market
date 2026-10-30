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

    @Test
    void thirdConsecutiveFailure_reachesTheLimit_andTheCountRestarts() {
        LoginLockout lockout = new LoginLockout(clock, 3);
        assertThat(lockout.registerFailure("ana@example.com")).isFalse();
        assertThat(lockout.registerFailure("ana@example.com")).isFalse();
        assertThat(lockout.registerFailure("ana@example.com")).isTrue();
        // Tras bloquear, el conteo vuelve a cero (si un ADMIN la reactiva, tiene 3 intentos nuevos).
        assertThat(lockout.registerFailure("ana@example.com")).isFalse();
    }

    @Test
    void successfulLogin_resetsTheCount_andAccountsAreIndependent() {
        LoginLockout lockout = new LoginLockout(clock, 3);
        lockout.registerFailure("ana@example.com");
        lockout.registerFailure("ana@example.com");
        lockout.reset("ANA@example.com "); // misma cuenta: sin distinguir mayúsculas ni espacios
        assertThat(lockout.registerFailure("ana@example.com")).isFalse();
        assertThat(lockout.registerFailure("otra@example.com")).isFalse();
        assertThat(lockout.registerFailure("otra@example.com")).isFalse();
        assertThat(lockout.registerFailure("otra@example.com")).isTrue();
    }

    @Test
    void failuresFarApart_doNotAccumulate() {
        LoginLockout lockout = new LoginLockout(clock, 3);
        lockout.registerFailure("ana@example.com");
        lockout.registerFailure("ana@example.com");
        now.set(now.get().plus(Duration.ofMinutes(LoginLockout.WINDOW_MINUTES)));
        assertThat(lockout.registerFailure("ana@example.com")).isFalse(); // ventana nueva
    }

    @Test
    void concurrentFailures_reachTheLimitExactlyOnce() throws Exception {
        LoginLockout lockout = new LoginLockout(clock, 3);
        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reached = new AtomicInteger();
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (lockout.registerFailure("ana@example.com")) {
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
        assertThatThrownBy(() -> new LoginLockout(clock, 0)).isInstanceOf(IllegalArgumentException.class);
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
