package nexus.market.adapters.out.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import nexus.market.domain.enums.NotificationChannel;
import nexus.market.domain.ports.out.NotificationPort;

/**
 * Adaptador de salida {@link NotificationPort}: notificaciones simuladas.
 *
 * <p>Registra el envío en el log de la aplicación. En producción debe
 * conectarse con el proveedor de correo/SMS/push según el canal.</p>
 */
@Component
public class LoggingNotificationPort implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationPort.class);

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