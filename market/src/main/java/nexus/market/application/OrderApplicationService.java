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

    public OrderApplicationService(OrderService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Order createOrder(BuyerId buyerId, Address shippingAddress) {
        return delegate.createOrder(buyerId, shippingAddress);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentConfirmation confirmPayment(OrderId orderId, String paymentMethod) {
        return delegate.confirmPayment(orderId, paymentMethod);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void dispatch(OrderId orderId, WarehouseId warehouseId) {
        delegate.dispatch(orderId, warehouseId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deliver(OrderId orderId) {
        delegate.deliver(orderId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancel(OrderId orderId) {
        delegate.cancel(orderId);
    }

    @Override
    public Optional<Order> getOrder(OrderId orderId) {
        return delegate.getOrder(orderId);
    }
}
