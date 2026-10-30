package nexus.market.application;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Límite del registro público de compradores: {@value #MAX_PER_EMAIL} intentos por email y {@value #MAX_PER_CLIENT} por
 * cliente cada 15 minutos. Frena la creación masiva de cuentas y el sondeo de correos ya registrados (el registro
 * responde 409 si el email existe). Configurable con {@code nexusmarket.registration.max-per-email} y
 * {@code nexusmarket.registration.max-per-client}.
 */
@Component
@Profile("jpa")
public class RegistrationAttemptLimiter extends ClientEmailLimiter {

    public static final int MAX_PER_EMAIL = 5;
    public static final int MAX_PER_CLIENT = 20;

    @Autowired
    public RegistrationAttemptLimiter(
            @Value("${nexusmarket.registration.max-per-email:" + MAX_PER_EMAIL + "}") int maxPerEmail,
            @Value("${nexusmarket.registration.max-per-client:" + MAX_PER_CLIENT + "}") int maxPerClient) {
        this(Clock.systemUTC(), maxPerEmail, maxPerClient, WINDOW);
    }

    RegistrationAttemptLimiter(Clock clock, int maxPerEmail, int maxPerClient, Duration window) {
        super(clock, maxPerEmail, maxPerClient, window);
    }
}
