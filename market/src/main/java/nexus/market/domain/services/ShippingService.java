package nexus.market.domain.services;

import java.util.Objects;
import java.util.Optional;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Order;
import nexus.market.domain.models.Shipping;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.ShippingProviderPort;
import nexus.market.domain.ports.out.ShippingRepositoryPort;
import nexus.market.domain.valueobjects.DeliveryInfo;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.ShippingId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Servicio de dominio del ciclo de vida de los envíos (SDD - Domain Services:
 * {@code ShippingService}): {@code PREPARING → DISPATCHED → IN_TRANSIT → DELIVERED}.
 */
public class ShippingService {

    private final ShippingRepositoryPort shippings;
    private final OrderRepositoryPort orders;
    private final ShippingProviderPort shippingProvider;
    private final AuditService audit;

    public ShippingService(ShippingRepositoryPort shippings, OrderRepositoryPort orders,
                           ShippingProviderPort shippingProvider, AuditService audit) {
        this.shippings = Objects.requireNonNull(shippings, "shippings es obligatorio");
        this.orders = Objects.requireNonNull(orders, "orders es obligatorio");
        this.shippingProvider = Objects.requireNonNull(shippingProvider, "shippingProvider es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    /** Crea el envío en {@code PREPARING}. Solo pedidos {@code PAID}. */
    public Shipping createShipping(OrderId orderId, WarehouseId warehouseId) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Objects.requireNonNull(warehouseId, "warehouseId es obligatorio");
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo se puede crear el envío de un pedido PAID.");
        }
        Shipping shipping = shippings.save(Shipping.create(orderId, warehouseId));
        audit.record(OperationType.SHIPPING_CREATION, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Envío creado: " + shipping.getShippingId());
        return shipping;
    }

    /**
     * Despacha el envío ({@code PREPARING → DISPATCHED}). Si la información
     * provista no tiene número de seguimiento, se obtiene de la transportadora
     * ({@code ShippingProviderPort.createShipment}).
     */
    public void dispatchShipping(ShippingId shippingId, DeliveryInfo deliveryInfo) {
        Objects.requireNonNull(shippingId, "shippingId es obligatorio");
        Objects.requireNonNull(deliveryInfo, "deliveryInfo es obligatorio");
        Shipping shipping = loadShipping(shippingId);
        Order order = orders.findById(shipping.getOrderId())
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));

        DeliveryInfo effective = deliveryInfo;
        if (!deliveryInfo.hasTrackingNumber()) {
            effective = shippingProvider.createShipment(order.getOrderId(),
                    order.getShippingAddress(), shipping.getWarehouseId());
        }
        shipping.dispatch(effective);
        shippings.save(shipping);
        audit.record(OperationType.SHIPPING_UPDATE, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Envío despachado: " + shippingId);
    }

    /** {@code DISPATCHED → IN_TRANSIT}. */
    public void markInTransit(ShippingId shippingId) {
        Objects.requireNonNull(shippingId, "shippingId es obligatorio");
        Shipping shipping = loadShipping(shippingId);
        shipping.markInTransit();
        shippings.save(shipping);
        audit.record(OperationType.SHIPPING_UPDATE, AuditSeverity.INFO,
                shippingId.toString(), "Envío en tránsito: " + shippingId);
    }

    /**
     * {@code IN_TRANSIT → DELIVERED}; además marca el pedido entregado
     * ({@code Order.deliver()} asigna {@code deliveredAt}) usando el puerto
     * de pedidos que ya posee el servicio (evita dependencia circular).
     */
    public void deliver(ShippingId shippingId) {
        Objects.requireNonNull(shippingId, "shippingId es obligatorio");
        Shipping shipping = loadShipping(shippingId);
        shipping.deliver();

        Order order = orders.findById(shipping.getOrderId())
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
        order.deliver();

        shippings.save(shipping);
        orders.save(order);
        audit.record(OperationType.SHIPPING_UPDATE, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Envío entregado: " + shippingId);
        audit.record(OperationType.ORDER_DELIVERY, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Pedido entregado: " + order.getOrderId());
    }

    public Shipping findById(ShippingId shippingId) {
        return loadShipping(shippingId);
    }

    public Optional<Shipping> findByOrderId(OrderId orderId) {
        return shippings.findByOrderId(orderId);
    }

    private Shipping loadShipping(ShippingId shippingId) {
        Objects.requireNonNull(shippingId, "shippingId es obligatorio");
        return shippings.findById(shippingId)
                .orElseThrow(() -> new BusinessException("SHIPPING_NOT_FOUND", "Envío inexistente."));
    }
}