package nexus.market.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Cuenta los inicios de sesión fallidos consecutivos de una cuenta existente. Al llegar al tope
 * ({@value #DEFAULT_MAX_FAILURES} por defecto) el servicio de login bloquea la cuenta ({@code BLOCKED}); solo un ADMIN la
 * puede reactivar ({@code PATCH /api/users/{id}/activate}).
 *
 * <p>Los fallos son "consecutivos": un inicio de sesión correcto reinicia la cuenta, y los fallos separados por más de
 * {@value #WINDOW_MINUTES} minutos no se acumulan. El conteo es atómico (varias peticiones simultáneas no pueden
 * superar el tope sin bloquear). El estado vive en memoria: es local a la instancia y se pierde al reiniciar; el
 * <b>bloqueo</b> sí queda guardado en la base de datos.</p>
 *
 * <p>Riesgo asumido: quien conoce el email de otra persona puede bloquear su cuenta fallando a propósito. Se mitiga con el
 * límite por cliente ({@link LoginAttemptLimiter}) y con que el ADMIN nunca se bloquea por este camino.</p>
 */
@Component
@Profile("jpa")
public class LoginLockout {

    public static final int DEFAULT_MAX_FAILURES = 3;
    static final int WINDOW_MINUTES = 60;
    private static final int PURGE_THRESHOLD = 1024;

    private record Failures(Instant last, int count) {
    }

    private final Clock clock;
    private final int maxFailures;
    private final Duration window = Duration.ofMinutes(WINDOW_MINUTES);
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    /** Tope configurable con {@code nexusmarket.login.lockout-after} (por defecto 3). */
    @Autowired
    public LoginLockout(@Value("${nexusmarket.login.lockout-after:" + DEFAULT_MAX_FAILURES + "}") int maxFailures) {
        this(Clock.systemUTC(), maxFailures);
    }

    LoginLockout(Clock clock, int maxFailures) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("El número de intentos para bloquear debe ser positivo.");
        }
        this.clock = Objects.requireNonNull(clock, "clock es obligatorio");
        this.maxFailures = maxFailures;
    }

    /**
     * Registra un fallo de la cuenta.
     *
     * @return {@code true} si con este fallo se alcanza el tope y la cuenta debe bloquearse (el conteo se reinicia)
     */
    public boolean registerFailure(String email) {
        Instant now = clock.instant();
        boolean[] reached = { false };
        failures.compute(key(email), (k, current) -> {
            int count = current == null || !now.isBefore(current.last().plus(window)) ? 1 : current.count() + 1;
            if (count >= maxFailures) {
                reached[0] = true;
                return null; // la cuenta se bloqueará: el conteo vuelve a cero
            }
            return new Failures(now, count);
        });
        if (failures.size() > PURGE_THRESHOLD) {
            failures.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().last().plus(window)));
        }
        return reached[0];
    }

    /** Reinicia el conteo tras un inicio de sesión correcto. */
    public void reset(String email) {
        failures.remove(key(email));
    }

    private static String key(String email) {
        return Objects.requireNonNull(email, "email es obligatorio").trim().toLowerCase(Locale.ROOT);
    }
}
