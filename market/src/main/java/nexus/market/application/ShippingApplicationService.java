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

    public ShippingApplicationService(ShippingService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Shipping createShipping(OrderId orderId, WarehouseId warehouseId) {
        return delegate.createShipping(orderId, warehouseId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void dispatchShipping(ShippingId shippingId, DeliveryInfo deliveryInfo) {
        delegate.dispatchShipping(shippingId, deliveryInfo);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markInTransit(ShippingId shippingId) {
        delegate.markInTransit(shippingId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deliver(ShippingId shippingId) {
        delegate.deliver(shippingId);
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
