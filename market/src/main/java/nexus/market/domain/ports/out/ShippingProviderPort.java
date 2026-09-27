package nexus.market.domain.ports.out;

import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.DeliveryInfo;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Puerto de salida de la transportadora externa: creación y cancelación de
 * envíos. Implementado por {@code adapters.out.shipping}.
 */
public interface ShippingProviderPort {

    DeliveryInfo createShipment(OrderId orderId, Address destination, WarehouseId originWarehouseId);

    void cancelShipment(String trackingNumber);
}