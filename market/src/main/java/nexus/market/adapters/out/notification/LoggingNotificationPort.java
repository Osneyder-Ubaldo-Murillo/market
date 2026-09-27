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
                channel, recipient, subject, content);
    }
}