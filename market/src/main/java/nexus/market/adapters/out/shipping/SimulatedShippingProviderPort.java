package nexus.market.adapters.out.shipping;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
 *
 * <p><b>Bloqueado en producción por defecto:</b> con el perfil {@code prod} la aplicación no arranca mientras
 * {@value #SIMULATED_ALLOWED_PROPERTY} no sea {@code true}. Un despliegue real no debe informar un seguimiento que no
 * existe en ninguna transportadora.</p>
 */
@Component
public class SimulatedShippingProviderPort implements ShippingProviderPort {

    /** Propiedad que autoriza expresamente la transportadora simulada con el perfil {@code prod}. */
    public static final String SIMULATED_ALLOWED_PROPERTY = "nexusmarket.shipping.simulated.allowed";

    private static final Logger log = LoggerFactory.getLogger(SimulatedShippingProviderPort.class);

    private static final String CARRIER = "SimulCarrier";

    @Autowired
    public SimulatedShippingProviderPort(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            // Solo el texto "true" autoriza; cualquier otro valor bloquea (y no pasa por la conversión de Spring).
            if (!"true".equalsIgnoreCase(environment.getProperty(SIMULATED_ALLOWED_PROPERTY, "").trim())) {
                throw new IllegalStateException("La transportadora simulada está bloqueada con el perfil prod. Este "
                        + "proyecto solo tiene una transportadora simulada (no crea envíos reales; el seguimiento es "
                        + "ficticio): para un uso académico declare " + SIMULATED_ALLOWED_PROPERTY
                        + "=true (NEXUSMARKET_SHIPPING_SIMULATED_ALLOWED=true) o reemplace el adaptador por una "
                        + "transportadora real.");
            }
            log.warn("[SHIPPING] Transportadora SIMULADA habilitada en producción por {}: no se crean envíos reales.",
                    SIMULATED_ALLOWED_PROPERTY);
        }
    }

    /** Sin perfil de producción (desarrollo y pruebas unitarias). */
    public SimulatedShippingProviderPort() {
        // sin comprobaciones: el bloqueo solo aplica con el perfil prod
    }

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