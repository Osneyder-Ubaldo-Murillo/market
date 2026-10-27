package nexus.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Límite de 5 intentos en 15 minutos por usuario, en memoria y reservado de forma atómica. */
class PasswordAttemptLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-27T10:00:00Z"));
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
    private final PasswordAttemptLimiter limiter = new PasswordAttemptLimiter(clock);

    @Test
    void fiveAttemptsPass_theSixthIsRejected_withTheRemainingSeconds() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire("u1");
        }
        now.set(now.get().plusSeconds(60));
        assertThatThrownBy(() -> limiter.acquire("u1")).isInstanceOf(TooManyAttemptsException.class)
                .satisfies(e -> assertThat(((TooManyAttemptsException) e).retryAfterSeconds()).isBetween(830L, 842L))
                .hasMessageNotContaining("u1");
    }

    @Test
    void theWindowExpiresAfterFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire("u1");
        }
        now.set(now.get().plus(PasswordAttemptLimiter.WINDOW).minusSeconds(1));
        assertThatThrownBy(() -> limiter.acquire("u1")).isInstanceOf(TooManyAttemptsException.class);
        now.set(now.get().plusSeconds(2));
        assertThatCode(() -> limiter.acquire("u1")).doesNotThrowAnyException();
    }

    @Test
    void usersAreIndependent_andResetClearsTheCounter() {
        for (int i = 0; i < 5; i++) {
            limiter.acquire("u1");
        }
        assertThatCode(() -> limiter.acquire("u2")).doesNotThrowAnyException();
        limiter.reset("u1");
        for (int i = 0; i < 5; i++) {
            limiter.acquire("u1");
        }
        assertThatThrownBy(() -> limiter.acquire("u1")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void concurrentBurst_neverAdmitsMoreThanTheLimit() throws Exception {
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    limiter.acquire("rafaga");
                    admitted.incrementAndGet();
                } catch (TooManyAttemptsException rejected) {
                    // esperado para las que exceden el límite
                }
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(admitted.get()).isEqualTo(PasswordAttemptLimiter.MAX_ATTEMPTS);
    }

    @Test
    void expiredEntriesArePurged_soTheMapDoesNotGrowWithoutBound() {
        for (int i = 0; i < 1100; i++) {
            limiter.acquire("usuario-" + i);
        }
        now.set(now.get().plus(Duration.ofMinutes(16)));
        limiter.acquire("nuevo"); // dispara la limpieza de ventanas vencidas
        for (int i = 0; i < 5; i++) { // un usuario antiguo vuelve a tener una ventana completa
            limiter.acquire("usuario-0");
        }
        assertThatThrownBy(() -> limiter.acquire("usuario-0")).isInstanceOf(TooManyAttemptsException.class);
    }
}
