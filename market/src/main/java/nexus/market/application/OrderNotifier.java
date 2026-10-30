package nexus.market.application;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import nexus.market.domain.enums.NotificationChannel;
import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Order;
import nexus.market.domain.ports.out.BuyerRepositoryPort;
import nexus.market.domain.ports.out.NotificationPort;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.OrderId;

/**
 * Avisos al comprador sobre su pedido y su envío, por {@link NotificationPort} (hoy un adaptador que escribe en el
 * log; mañana correo, SMS o push sin tocar este código).
 *
 * <p>Reglas: el destinatario se resuelve dentro de la transacción del caso de uso, pero el aviso se envía <b>solo
 * después de confirmarla</b> (si la transacción se revierte no se notifica algo que no ocurrió); y un fallo al notificar
 * <b>nunca</b> rompe ni revierte la operación de negocio: se registra y se continúa. El contenido no incluye datos
 * personales más allá del identificador del pedido.</p>
 */
@Component
@Profile("jpa")
public class OrderNotifier {

    private static final Logger log = LoggerFactory.getLogger(OrderNotifier.class);

    private final NotificationPort notifications;
    private final BuyerRepositoryPort buyers;
    private final OrderRepositoryPort orders;
    private final UserRepositoryPort users;

    public OrderNotifier(NotificationPort notifications, BuyerRepositoryPort buyers, OrderRepositoryPort orders,
                         UserRepositoryPort users) {
        this.notifications = Objects.requireNonNull(notifications, "notifications es obligatorio");
        this.buyers = Objects.requireNonNull(buyers, "buyers es obligatorio");
        this.orders = Objects.requireNonNull(orders, "orders es obligatorio");
        this.users = Objects.requireNonNull(users, "users es obligatorio");
    }

    /** Avisa al comprador dueño de {@code orderId}. */
    public void notifyOrder(OrderId orderId, String subject, String content) {
        try {
            Optional<BuyerId> buyerId = orders.findById(orderId).map(Order::getBuyerId);
            buyerId.ifPresent(id -> notifyBuyer(id, subject, content));
        } catch (RuntimeException ex) {
            log.warn("No se pudo preparar la notificación del pedido {}: {}", orderId, ex.getClass().getSimpleName());
        }
    }

    /** Avisa al comprador {@code buyerId}. */
    public void notifyBuyer(BuyerId buyerId, String subject, String content) {
        String recipient;
        try {
            recipient = buyers.findById(buyerId).map(Buyer::getUserId).flatMap(users::findById)
                    .map(user -> user.getEmail().getValue()).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("No se pudo preparar la notificación del comprador {}: {}", buyerId, ex.getClass().getSimpleName());
            return;
        }
        if (recipient == null) {
            log.warn("Notificación omitida: el comprador {} no tiene un destinatario conocido.", buyerId);
            return;
        }
        afterCommit(() -> send(recipient, subject, content));
    }

    private void send(String recipient, String subject, String content) {
        try {
            notifications.send(NotificationChannel.EMAIL, recipient, subject, content);
        } catch (RuntimeException ex) {
            log.warn("No se pudo enviar la notificación '{}': {}", subject, ex.getClass().getSimpleName());
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
