package nexus.market.application;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Límite de las peticiones de verificación y reenvío del código de correo: {@value #MAX_PER_EMAIL} por email y
 * {@value #MAX_PER_CLIENT} por cliente cada 15 minutos (429 con {@code Retry-After}). Evita inundar de avisos a una
 * persona y probar códigos a ciegas. Configurable con {@code nexusmarket.verification.max-per-email} y
 * {@code nexusmarket.verification.max-per-client}.
 */
@Component
@Profile("jpa")
public class EmailVerificationLimiter extends ClientEmailLimiter {

    public static final int MAX_PER_EMAIL = 3;
    public static final int MAX_PER_CLIENT = 20;

    @Autowired
    public EmailVerificationLimiter(
            @Value("${nexusmarket.verification.max-per-email:" + MAX_PER_EMAIL + "}") int maxPerEmail,
            @Value("${nexusmarket.verification.max-per-client:" + MAX_PER_CLIENT + "}") int maxPerClient) {
        this(Clock.systemUTC(), maxPerEmail, maxPerClient, WINDOW);
    }

    EmailVerificationLimiter(Clock clock, int maxPerEmail, int maxPerClient, Duration window) {
        super(clock, maxPerEmail, maxPerClient, window);
    }
}
