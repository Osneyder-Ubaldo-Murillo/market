package com.nexusmarket.domain.models;

import java.util.Objects;

import com.nexusmarket.domain.exceptions.BusinessException;
import com.nexusmarket.domain.valueobjects.DeliveryInfo;
import com.nexusmarket.domain.valueobjects.OrderId;
import com.nexusmarket.domain.valueobjects.ShippingId;
import com.nexusmarket.domain.valueobjects.ShippingStatus;
import com.nexusmarket.domain.valueobjects.WarehouseId;

/**
 * Agregado raíz que representa el envío físico de un pedido.
 *
 * <p>Flujo: {@code PREPARING → DISPATCHED → IN_TRANSIT → DELIVERED}.</p>
 */
public class Shipping {

    private final ShippingId shippingId;
    private final OrderId orderId;
    private final WarehouseId warehouseId;
    private DeliveryInfo deliveryInfo;
    private ShippingStatus status;

    public Shipping(ShippingId shippingId, OrderId orderId, WarehouseId warehouseId,
                    DeliveryInfo deliveryInfo, ShippingStatus status) {
        this.shippingId = Objects.requireNonNull(shippingId, "shippingId es obligatorio");
        this.orderId = Objects.requireNonNull(orderId, "orderId es obligatorio");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId es obligatorio");
        this.deliveryInfo = deliveryInfo;
        this.status = Objects.requireNonNull(status, "status es obligatorio");
    }

    /**
     * Crea un envío en estado {@code PREPARING}. Aún no hay información de
     * transportadora hasta el despacho.
     */
    public static Shipping create(OrderId orderId, WarehouseId warehouseId) {
        return new Shipping(ShippingId.generate(), orderId, warehouseId,
                null, ShippingStatus.PREPARING);
    }

    /** {@code PREPARING → DISPATCHED}. */
    public void dispatch(DeliveryInfo deliveryInfo) {
        requireStatus(ShippingStatus.PREPARING);
        this.deliveryInfo = Objects.requireNonNull(deliveryInfo, "deliveryInfo es obligatorio al despachar");
        this.status = ShippingStatus.DISPATCHED;
    }

    /** {@code DISPATCHED → IN_TRANSIT}. */
    public void markInTransit() {
        requireStatus(ShippingStatus.DISPATCHED);
        this.status = ShippingStatus.IN_TRANSIT;
    }

    /** {@code IN_TRANSIT → DELIVERED}. */
    public void deliver() {
        requireStatus(ShippingStatus.IN_TRANSIT);
        this.status = ShippingStatus.DELIVERED;
    }

    /**
     * Actualiza los datos de seguimiento mientras el envío no esté entregado.
     */
    public void updateDeliveryInfo(DeliveryInfo newInfo) {
        if (status == ShippingStatus.DELIVERED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "No se puede actualizar la información de un envío DELIVERED.");
        }
        this.deliveryInfo = Objects.requireNonNull(newInfo, "newInfo es obligatorio");
    }

    private void requireStatus(ShippingStatus required) {
        if (status != required) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "La operación requiere un envío en estado " + required.getCode()
                            + " (actual: " + status.getCode() + ").");
        }
    }

    public ShippingId getShippingId() {
        return shippingId;
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public WarehouseId getWarehouseId() {
        return warehouseId;
    }

    public DeliveryInfo getDeliveryInfo() {
        return deliveryInfo;
    }

    public ShippingStatus getStatus() {
        return status;
    }
}