package nexus.market.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Límite de intentos por email y por cliente (IP) en una ventana fija. Base de {@link LoginAttemptLimiter},
 * {@link RegistrationAttemptLimiter} y {@link PasswordRecoveryLimiter}, cada uno con su propio cupo y estado.
 *
 * <p>Reutiliza el mecanismo de {@link PasswordAttemptLimiter}: el intento se reserva ANTES de la operación y de forma
 * atómica. Se comprueba primero el cliente: un cliente bloqueado no crea entradas nuevas por email, lo que acota la
 * memoria usada por quien prueba correos al azar.</p>
 *
 * <p><b>Limitaciones:</b> el estado está en memoria, es local a cada instancia y se pierde al reiniciar. Bloquear por email
 * permite que un tercero retrase a una víctima durante la ventana. La IP es la dirección TCP de la petición: detrás de un
 * proxy inverso sin configurar el reenvío de cabeceras todos los clientes compartirían la misma IP; no se confía en
 * {@code X-Forwarded-For} porque el cliente puede falsificarlo.</p>
 */
public abstract class ClientEmailLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(15);

    private final PasswordAttemptLimiter byEmail;
    private final PasswordAttemptLimiter byClient;

    protected ClientEmailLimiter(Clock clock, int maxPerEmail, int maxPerClient, Duration window) {
        this.byEmail = new PasswordAttemptLimiter(clock, maxPerEmail, window);
        this.byClient = new PasswordAttemptLimiter(clock, maxPerClient, window);
    }

    /**
     * Reserva un intento.
     *
     * @throws TooManyAttemptsException si el cliente o el email agotaron su cupo en la ventana vigente
     */
    public void acquire(String clientAddress, String email) {
        byClient.acquire(clientKey(clientAddress));
        byEmail.acquire(emailKey(email));
    }

    /** Reserva un intento solo contra el cupo del cliente (operaciones sin email, como canjear un código). */
    public void acquireClient(String clientAddress) {
        byClient.acquire(clientKey(clientAddress));
    }

    /** Reinicia el contador del email (no el del cliente). */
    public void reset(String email) {
        byEmail.reset(emailKey(email));
    }

    private static String clientKey(String clientAddress) {
        return clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress.trim();
    }

    private static String emailKey(String email) {
        return Objects.requireNonNull(email, "email es obligatorio").trim().toLowerCase(Locale.ROOT);
    }
}
