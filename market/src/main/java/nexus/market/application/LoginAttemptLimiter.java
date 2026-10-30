package nexus.market.application;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Límite de intentos de login contra la fuerza bruta y el relleno de credenciales: ventana fija de 15 minutos con
 * {@value #MAX_PER_EMAIL} intentos por email y {@value #MAX_PER_CLIENT} por cliente (IP). Al agotarse se responde 429
 * con {@code Retry-After}, aunque la contraseña sea correcta. Un login correcto reinicia solo el contador del email.
 * Ver {@link ClientEmailLimiter} para el mecanismo y sus limitaciones.
 */
@Component
@Profile("jpa")
public class LoginAttemptLimiter extends ClientEmailLimiter {

    public static final int MAX_PER_EMAIL = 10;
    public static final int MAX_PER_CLIENT = 50;

    /** Cupos configurables ({@code nexusmarket.login.max-per-email} y {@code ...max-per-client}); ventana de 15 min. */
    @Autowired
    public LoginAttemptLimiter(@Value("${nexusmarket.login.max-per-email:" + MAX_PER_EMAIL + "}") int maxPerEmail,
                               @Value("${nexusmarket.login.max-per-client:" + MAX_PER_CLIENT + "}") int maxPerClient) {
        this(Clock.systemUTC(), maxPerEmail, maxPerClient, WINDOW);
    }

    public LoginAttemptLimiter(Clock clock) {
        this(clock, MAX_PER_EMAIL, MAX_PER_CLIENT, WINDOW);
    }

    LoginAttemptLimiter(Clock clock, int maxPerEmail, int maxPerClient, Duration window) {
        super(clock, maxPerEmail, maxPerClient, window);
    }
}
