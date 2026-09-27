package nexus.market.domain.ports.out;

import nexus.market.domain.enums.NotificationChannel;

/**
 * Puerto de salida para el envío de notificaciones por canal.
 * Implementado por {@code adapters.out.notification}.
 */
public interface NotificationPort {

    void send(NotificationChannel channel, String recipient, String subject, String content);
}