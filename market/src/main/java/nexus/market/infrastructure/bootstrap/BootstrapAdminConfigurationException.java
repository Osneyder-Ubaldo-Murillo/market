package nexus.market.infrastructure.bootstrap;

/**
 * Configuración del administrador inicial ausente o inválida. Los mensajes nombran las variables de entorno
 * y la regla incumplida; NUNCA incluyen valores (contraseña, correo, rutas).
 */
public class BootstrapAdminConfigurationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public BootstrapAdminConfigurationException(String message) {
        super(message);
    }
}
