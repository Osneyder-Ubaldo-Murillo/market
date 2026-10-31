package nexus.market.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Cuenta los inicios de sesión fallidos consecutivos de una cuenta existente. Al llegar al tope
 * ({@value #DEFAULT_MAX_FAILURES} por defecto) <b>y</b> haber fallado desde al menos {@value #DEFAULT_MIN_CLIENTS} orígenes
 * (direcciones de cliente) distintos, el servicio de login bloquea la cuenta ({@code BLOCKED}); solo un ADMIN la puede
 * reactivar ({@code PATCH /api/users/{id}/activate}).
 *
 * <p><b>Control contra el bloqueo malintencionado:</b> un único origen nunca puede bloquear una cuenta ajena, por muchos
 * fallos que acumule (además {@link LoginAttemptLimiter} le corta el paso tras 3 intentos por ventana). Para bloquearla
 * hace falta fallar desde varios orígenes, que es el patrón de un ataque distribuido.</p>
 *
 * <p>Los fallos son "consecutivos": un inicio de sesión correcto reinicia la cuenta, y los fallos separados por más de
 * {@value #WINDOW_MINUTES} minutos no se acumulan. El conteo es atómico (varias peticiones simultáneas no pueden
 * superar el tope sin bloquear). El estado vive en memoria: es local a la instancia y se pierde al reiniciar; el
 * <b>bloqueo</b> sí queda guardado en la base de datos.</p>
 *
 * <p>Riesgo residual: quien controle varios orígenes (varias IP) y conozca el email de otra persona todavía puede
 * bloquear su cuenta. Se encarece con los límites por cliente y por pareja cliente+email ({@link LoginAttemptLimiter}), se
 * deja constancia en la auditoría ({@code ACCOUNT_LOCKED}) y el ADMIN nunca se bloquea por este camino.</p>
 */
@Component
@Profile("jpa")
public class LoginLockout {

    public static final int DEFAULT_MAX_FAILURES = 3;
    public static final int DEFAULT_MIN_CLIENTS = 2;
    static final int WINDOW_MINUTES = 60;
    private static final int PURGE_THRESHOLD = 1024;

    private record Failures(Instant last, int count, Set<String> clients) {
    }

    private final Clock clock;
    private final int maxFailures;
    private final int minClients;
    private final Duration window = Duration.ofMinutes(WINDOW_MINUTES);
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    /**
     * Topes configurables: {@code nexusmarket.login.lockout-after} (fallos, por defecto 3) y
     * {@code nexusmarket.login.lockout-min-clients} (orígenes distintos, por defecto 2).
     */
    @Autowired
    public LoginLockout(@Value("${nexusmarket.login.lockout-after:" + DEFAULT_MAX_FAILURES + "}") int maxFailures,
                        @Value("${nexusmarket.login.lockout-min-clients:" + DEFAULT_MIN_CLIENTS + "}") int minClients) {
        this(Clock.systemUTC(), maxFailures, minClients);
    }

    LoginLockout(Clock clock, int maxFailures, int minClients) {
        if (maxFailures < 1 || minClients < 1) {
            throw new IllegalArgumentException("Los topes para bloquear deben ser positivos.");
        }
        this.clock = Objects.requireNonNull(clock, "clock es obligatorio");
        this.maxFailures = maxFailures;
        this.minClients = minClients;
    }

    /**
     * Registra un fallo de la cuenta desde un origen.
     *
     * @return {@code true} si con este fallo se alcanza el tope de fallos y ya hay fallos de suficientes orígenes
     *         distintos: la cuenta debe bloquearse (el conteo se reinicia)
     */
    public boolean registerFailure(String email, String clientAddress) {
        Instant now = clock.instant();
        String client = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress.trim();
        boolean[] reached = { false };
        failures.compute(key(email), (k, current) -> {
            boolean fresh = current == null || !now.isBefore(current.last().plus(window));
            int count = fresh ? 1 : Math.min(current.count() + 1, 1_000_000);
            Set<String> clients = fresh ? new HashSet<>() : new HashSet<>(current.clients());
            if (clients.size() < minClients) {
                clients.add(client); // basta con saber si se llegó al mínimo: no se guardan más
            }
            if (count >= maxFailures && clients.size() >= minClients) {
                reached[0] = true;
                return null; // la cuenta se bloqueará: el conteo vuelve a cero
            }
            return new Failures(now, count, Set.copyOf(clients));
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
