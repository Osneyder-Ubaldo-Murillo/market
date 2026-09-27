package nexus.market.adapters.out.shipping;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import nexus.market.domain.ports.out.ShippingProviderPort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.DeliveryInfo;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Adaptador de salida {@link ShippingProviderPort}: transportadora simulada.
 *
 * <p>Genera un número de seguimiento ficticio con una fecha estimada de entrega.
 * En producción debe reemplazarse por la integración real de la transportadora
 * (mismo puerto).</p>
 */
@Component
public class SimulatedShippingProviderPort implements ShippingProviderPort {

    private static final Logger log = LoggerFactory.getLogger(SimulatedShippingProviderPort.class);

    private static final String CARRIER = "SimulCarrier";

    @Override
    public DeliveryInfo createShipment(OrderId orderId, Address destination, WarehouseId originWarehouseId) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Objects.requireNonNull(destination, "destination es obligatorio");
        String trackingNumber = "TRK-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        log.info("[SHIPPING] create order={} origin={} destination={} tracking={}",
                orderId, originWarehouseId, destination, trackingNumber);
        return DeliveryInfo.of(trackingNumber, CARRIER, LocalDate.now().plusDays(5));
    }

    @Override
    public void cancelShipment(String trackingNumber) {
        log.info("[SHIPPING] cancel tracking={}", trackingNumber);
    }
}