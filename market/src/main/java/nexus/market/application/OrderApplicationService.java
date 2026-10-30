package nexus.market.application;

import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.domain.ports.in.OrderUseCases;
import nexus.market.domain.models.Order;
import nexus.market.domain.services.OrderService;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.PaymentConfirmation;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Servicio de aplicación del ciclo de compra: delega en {@code OrderService}
 * (creación desde carrito, pago, despacho, entrega y cancelación).
 */
@Service
@Profile("jpa")
public class OrderApplicationService implements OrderUseCases {

    private final OrderService delegate;
    private final OrderNotifier notifier;

    public OrderApplicationService(OrderService delegate, OrderNotifier notifier) {
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
        this.notifier = Objects.requireNonNull(notifier, "notifier es obligatorio");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order createOrder(BuyerId buyerId, Address shippingAddress) {
        Order order = delegate.createOrder(buyerId, shippingAddress);
        notifier.notifyBuyer(buyerId, "Pedido creado",
                "Recibimos su pedido " + order.getOrderId() + ". Quedó pendiente de pago.");
        return order;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentConfirmation confirmPayment(OrderId orderId, String paymentMethod) {
        PaymentConfirmation confirmation = delegate.confirmPayment(orderId, paymentMethod);
        notifier.notifyOrder(orderId, "Pago confirmado", "El pago del pedido " + orderId + " fue confirmado.");
        return confirmation;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void dispatch(OrderId orderId, WarehouseId warehouseId) {
        delegate.dispatch(orderId, warehouseId);
        notifier.notifyOrder(orderId, "Pedido despachado", "Su pedido " + orderId + " fue despachado.");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deliver(OrderId orderId) {
        delegate.deliver(orderId);
        notifier.notifyOrder(orderId, "Pedido entregado", "Su pedido " + orderId + " fue entregado.");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancel(OrderId orderId) {
        delegate.cancel(orderId);
        notifier.notifyOrder(orderId, "Pedido cancelado", "Su pedido " + orderId + " fue cancelado.");
    }

    @Override
    public java.util.List<Order> findByBuyer(BuyerId buyerId, int offset, int limit) {
        return delegate.findByBuyer(buyerId, offset, limit);
    }

    @Override
    public long countByBuyer(BuyerId buyerId) {
        return delegate.countByBuyer(buyerId);
    }

    @Override
    public Optional<Order> getOrder(OrderId orderId) {
        return delegate.getOrder(orderId);
    }
}
