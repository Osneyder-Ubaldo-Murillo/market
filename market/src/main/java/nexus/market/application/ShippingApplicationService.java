package nexus.market.application;

import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.domain.ports.in.ShippingUseCases;
import nexus.market.domain.models.Shipping;
import nexus.market.domain.services.ShippingService;
import nexus.market.domain.valueobjects.DeliveryInfo;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ShippingId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Servicio de aplicación de envíos: delega en {@code ShippingService}.
 */
@Service
@Profile("jpa")
public class ShippingApplicationService implements ShippingUseCases {

    private final ShippingService delegate;
    private final OrderNotifier notifier;
    private final OrderTimeline timeline;

    public ShippingApplicationService(ShippingService delegate, OrderNotifier notifier, OrderTimeline timeline) {
        this.timeline = Objects.requireNonNull(timeline, "timeline es obligatorio");
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
        this.notifier = Objects.requireNonNull(notifier, "notifier es obligatorio");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Shipping createShipping(OrderId orderId, WarehouseId warehouseId) {
        Shipping shipping = delegate.createShipping(orderId, warehouseId);
        timeline.record(orderId, nexus.market.domain.enums.OrderEventType.SHIPPING_CREATED, "Envío en preparación");
        return shipping;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void dispatchShipping(ShippingId shippingId, DeliveryInfo deliveryInfo) {
        delegate.dispatchShipping(shippingId, deliveryInfo);
        timeline.record(delegate.findById(shippingId).getOrderId(), nexus.market.domain.enums.OrderEventType.DISPATCHED,
                "Envío despachado");
        notifyShipping(shippingId, "Envío despachado", "El envío de su pedido %s salió hacia su destino.");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markInTransit(ShippingId shippingId) {
        delegate.markInTransit(shippingId);
        timeline.record(delegate.findById(shippingId).getOrderId(), nexus.market.domain.enums.OrderEventType.IN_TRANSIT,
                "Envío en tránsito");
        notifyShipping(shippingId, "Envío en tránsito", "El envío de su pedido %s está en tránsito.");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deliver(ShippingId shippingId, String notes) {
        delegate.deliver(shippingId);
        timeline.record(delegate.findById(shippingId).getOrderId(), nexus.market.domain.enums.OrderEventType.DELIVERED,
                OrderApplicationService.deliveryDetail(notes));
        notifyShipping(shippingId, "Envío entregado", "El envío de su pedido %s fue entregado.");
    }

    private void notifyShipping(ShippingId shippingId, String subject, String contentTemplate) {
        OrderId orderId = delegate.findById(shippingId).getOrderId();
        notifier.notifyOrder(orderId, subject, String.format(contentTemplate, orderId));
    }

    @Override
    public Optional<Shipping> findByOrderId(OrderId orderId) {
        return delegate.findByOrderId(orderId);
    }

    @Override
    public Shipping findById(ShippingId shippingId) {
        return delegate.findById(shippingId);
    }
}
