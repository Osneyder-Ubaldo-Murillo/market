package nexus.market.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Límite de intentos de cambio de contraseña: como máximo {@value #MAX_ATTEMPTS} intentos por usuario en una ventana
 * fija de 15 minutos; los siguientes se rechazan (429) hasta que la ventana termina, aunque la contraseña sea correcta.
 *
 * <p><b>El intento se reserva ANTES de verificar la contraseña</b>, de forma atómica ({@link ConcurrentHashMap#compute}):
 * una ráfaga de peticiones simultáneas no puede superar el límite (comprobar-y-luego-registrar permitiría que todas
 * pasaran). Un cambio exitoso reinicia el contador.</p>
 *
 * <p><b>Limitación:</b> el estado está en memoria. Es <b>local a cada instancia</b> (con N instancias el máximo efectivo es
 * N × 5 por ventana) y <b>se pierde al reiniciar</b>. Una garantía distribuida exigiría estado compartido (esquema o
 * Redis) y no se incluye.</p>
 */
@Component
@Profile("jpa")
public class PasswordAttemptLimiter {

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int PURGE_THRESHOLD = 1024;

    private record Window(Instant start, int attempts) {
    }

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public PasswordAttemptLimiter() {
        this(Clock.systemUTC());
    }

    public PasswordAttemptLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock es obligatorio");
    }

    /**
     * Reserva un intento para {@code key}.
     *
     * @throws TooManyAttemptsException si ya se usaron {@value #MAX_ATTEMPTS} intentos en la ventana vigente
     */
    public void acquire(String key) {
        Instant now = clock.instant();
        long[] retryAfter = { 0 };
        windows.compute(key, (k, window) -> {
            if (window == null || !now.isBefore(window.start().plus(WINDOW))) {
                return new Window(now, 1); // ventana nueva
            }
            if (window.attempts() >= MAX_ATTEMPTS) {
                retryAfter[0] = Duration.between(now, window.start().plus(WINDOW)).toSeconds() + 1;
                return window; // sin cambios: se rechaza
            }
            return new Window(window.start(), window.attempts() + 1);
        });
        if (windows.size() > PURGE_THRESHOLD) {
            windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().start().plus(WINDOW)));
        }
        if (retryAfter[0] > 0) {
            throw new TooManyAttemptsException(retryAfter[0]);
        }
    }

    /** Reinicia el contador tras un cambio exitoso. */
    public void reset(String key) {
        windows.remove(key);
    }
}
