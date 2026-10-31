package nexus.market.application;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Límite de intentos de login contra la fuerza bruta y el relleno de credenciales: ventana fija de 15 minutos con
 * {@value #MAX_PER_EMAIL} intentos por email, {@value #MAX_PER_CLIENT} por cliente (IP) y {@value #MAX_PER_CLIENT_AND_EMAIL}
 * por pareja cliente+email. Al agotarse se responde 429 con {@code Retry-After}, aunque la contraseña sea correcta. Un login
 * correcto reinicia los contadores del email y de esa pareja. Ver {@link ClientEmailLimiter} para el mecanismo.
 *
 * <p>El tope por pareja protege a la víctima: un solo origen no puede agotar el cupo del email de otra persona (ni
 * llevar su cuenta al bloqueo) con más de {@value #MAX_PER_CLIENT_AND_EMAIL} intentos por ventana. Se comprueba ANTES que
 * los demás para que los intentos rechazados no gasten el cupo del email.</p>
 */
@Component
@Profile("jpa")
public class LoginAttemptLimiter extends ClientEmailLimiter {

    public static final int MAX_PER_EMAIL = 10;
    public static final int MAX_PER_CLIENT = 50;
    public static final int MAX_PER_CLIENT_AND_EMAIL = 3;

    private final PasswordAttemptLimiter byClientAndEmail;

    /** Cupos configurables ({@code nexusmarket.login.max-per-email} y {@code ...max-per-client}); ventana de 15 min. */
    @Autowired
    public LoginAttemptLimiter(@Value("${nexusmarket.login.max-per-email:" + MAX_PER_EMAIL + "}") int maxPerEmail,
                               @Value("${nexusmarket.login.max-per-client:" + MAX_PER_CLIENT + "}") int maxPerClient,
                               @Value("${nexusmarket.login.max-per-client-and-email:" + MAX_PER_CLIENT_AND_EMAIL
                                       + "}") int maxPerClientAndEmail) {
        this(Clock.systemUTC(), maxPerEmail, maxPerClient, maxPerClientAndEmail, WINDOW);
    }

    public LoginAttemptLimiter(Clock clock) {
        this(clock, MAX_PER_EMAIL, MAX_PER_CLIENT, MAX_PER_CLIENT_AND_EMAIL, WINDOW);
    }

    LoginAttemptLimiter(Clock clock, int maxPerEmail, int maxPerClient, Duration window) {
        this(clock, maxPerEmail, maxPerClient, Integer.MAX_VALUE, window);
    }

    LoginAttemptLimiter(Clock clock, int maxPerEmail, int maxPerClient, int maxPerClientAndEmail, Duration window) {
        super(clock, maxPerEmail, maxPerClient, window);
        this.byClientAndEmail = new PasswordAttemptLimiter(clock, maxPerClientAndEmail, window);
    }

    @Override
    public void acquire(String clientAddress, String email) {
        byClientAndEmail.acquire(pairKey(clientAddress, email));
        super.acquire(clientAddress, email);
    }

    /** Tras un login correcto: reinicia el contador del email y el de esta pareja cliente+email. */
    public void reset(String clientAddress, String email) {
        byClientAndEmail.reset(pairKey(clientAddress, email));
        super.reset(email);
    }

    private static String pairKey(String clientAddress, String email) {
        String client = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress.trim();
        return client + "|" + java.util.Objects.requireNonNull(email, "email es obligatorio").trim()
                .toLowerCase(java.util.Locale.ROOT);
    }
}
