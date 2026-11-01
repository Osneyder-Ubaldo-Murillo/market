package nexus.market.adapters.out.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import nexus.market.domain.enums.NotificationChannel;
import nexus.market.domain.ports.out.NotificationPort;

/**
 * Adaptador de salida {@link NotificationPort}: notificaciones simuladas.
 *
 * <p>Registra el envío en el log de la aplicación, <b>incluido su contenido</b>: los códigos de verificación de correo y de
 * recuperación de contraseña quedan escritos en claro, de modo que quien lea el log puede tomar una cuenta. Por eso
 * <b>está bloqueado en producción por defecto</b>: con el perfil {@code prod} la aplicación no arranca mientras
 * {@value #LOGGING_ALLOWED_PROPERTY} no sea {@code true}. Es una autorización expresa y solo académica; un despliegue real
 * debe reemplazar este adaptador por el del proveedor de correo/SMS/push que implemente el mismo puerto.</p>
 */
@Component
public class LoggingNotificationPort implements NotificationPort {

    /** Propiedad que autoriza expresamente las notificaciones por log (con códigos en claro) con el perfil {@code prod}. */
    public static final String LOGGING_ALLOWED_PROPERTY = "nexusmarket.notifications.logging.allowed";

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationPort.class);

    @Autowired
    public LoggingNotificationPort(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            // Solo el texto "true" autoriza; cualquier otro valor bloquea (y no pasa por la conversión de Spring).
            if (!"true".equalsIgnoreCase(environment.getProperty(LOGGING_ALLOWED_PROPERTY, "").trim())) {
                throw new IllegalStateException("Las notificaciones por log están bloqueadas con el perfil prod: "
                        + "escriben en claro los códigos de verificación y de recuperación de contraseña, y quien lea el "
                        + "log podría tomar una cuenta. Este proyecto no tiene un proveedor real de correo/SMS: para un uso "
                        + "académico declare " + LOGGING_ALLOWED_PROPERTY + "=true "
                        + "(NEXUSMARKET_NOTIFICATIONS_LOGGING_ALLOWED=true) o reemplace el adaptador por uno real.");
            }
            log.warn("[NOTIFICATION] Notificaciones por LOG habilitadas en producción por {}: los códigos de verificación y "
                    + "de recuperación quedan escritos en el log. Solo para demostración académica; no use esta "
                    + "configuración con usuarios reales.", LOGGING_ALLOWED_PROPERTY);
        }
    }

    /** Sin perfil de producción (desarrollo y pruebas unitarias). */
    public LoggingNotificationPort() {
        // sin comprobaciones: el bloqueo solo aplica con el perfil prod
    }

    @Override
    public void send(NotificationChannel channel, String recipient, String subject, String content) {
        log.info("[NOTIFICATION] channel={} to={} subject={} content={}",
                channel, mask(recipient), subject, content);
    }

    /** El log no debe guardar direcciones completas: {@code ana@example.com} se registra como {@code a***@example.com}. */
    static String mask(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return "(sin destinatario)";
        }
        int at = recipient.indexOf('@');
        if (at > 0) {
            return recipient.charAt(0) + "***" + recipient.substring(at);
        }
        return recipient.length() <= 4 ? "***" : recipient.substring(0, 2) + "***";
    }
}