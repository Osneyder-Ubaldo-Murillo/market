package nexus.market.domain.ports.in;

import java.util.Optional;

import nexus.market.domain.models.Shipping;
import nexus.market.domain.valueobjects.DeliveryInfo;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ShippingId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Puerto de entrada (caso de uso) del ciclo de vida de envíos. Delega en
 * {@code ShippingService}: {@code PREPARING → DISPATCHED → IN_TRANSIT → DELIVERED}.
 */
public interface ShippingUseCases {

    Shipping createShipping(OrderId orderId, WarehouseId warehouseId);

    void dispatchShipping(ShippingId shippingId, DeliveryInfo deliveryInfo);

    void markInTransit(ShippingId shippingId);

    void deliver(ShippingId shippingId);

    Optional<Shipping> findByOrderId(OrderId orderId);

    Shipping findById(ShippingId shippingId);
}
