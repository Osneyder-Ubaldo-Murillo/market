package nexus.market.application;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Límite de las solicitudes y canjes de recuperación de contraseña: {@value #MAX_PER_EMAIL} solicitudes por email y
 * {@value #MAX_PER_CLIENT} por cliente cada 15 minutos. Evita usar el endpoint para inundar de avisos a una persona.
 * Configurable con {@code nexusmarket.recovery.max-per-email} y {@code nexusmarket.recovery.max-per-client}.
 */
@Component
@Profile("jpa")
public class PasswordRecoveryLimiter extends ClientEmailLimiter {

    public static final int MAX_PER_EMAIL = 3;
    public static final int MAX_PER_CLIENT = 20;

    @Autowired
    public PasswordRecoveryLimiter(
            @Value("${nexusmarket.recovery.max-per-email:" + MAX_PER_EMAIL + "}") int maxPerEmail,
            @Value("${nexusmarket.recovery.max-per-client:" + MAX_PER_CLIENT + "}") int maxPerClient) {
        this(Clock.systemUTC(), maxPerEmail, maxPerClient, WINDOW);
    }

    PasswordRecoveryLimiter(Clock clock, int maxPerEmail, int maxPerClient, Duration window) {
        super(clock, maxPerEmail, maxPerClient, window);
    }
}
