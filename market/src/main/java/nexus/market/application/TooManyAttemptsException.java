package nexus.market.application;

import nexus.market.domain.exceptions.BusinessException;

/** Se agotaron los intentos permitidos en la ventana; {@link #retryAfterSeconds()} indica cuánto falta para que termine. */
public class TooManyAttemptsException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "TOO_MANY_ATTEMPTS";

    private final long retryAfterSeconds;

    public TooManyAttemptsException(long retryAfterSeconds) {
        super(CODE, "Demasiados intentos. Intente de nuevo más tarde.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
