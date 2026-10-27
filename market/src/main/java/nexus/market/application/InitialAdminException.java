package nexus.market.application;

/**
 * Fallo del aprovisionamiento del administrador inicial. Los mensajes nombran variables o condiciones,
 * <b>nunca</b> valores (contraseña, hash, correo).
 */
public class InitialAdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InitialAdminException(String message) {
        super(message);
    }

    public InitialAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
