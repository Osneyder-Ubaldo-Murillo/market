package com.nexusmarket.domain.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.nexusmarket.domain.exceptions.BusinessException;
import com.nexusmarket.domain.valueobjects.Address;
import com.nexusmarket.domain.valueobjects.BuyerId;
import com.nexusmarket.domain.valueobjects.InvoiceId;
import com.nexusmarket.domain.valueobjects.Money;
import com.nexusmarket.domain.valueobjects.OrderId;
import com.nexusmarket.domain.valueobjects.OrderItem;
import com.nexusmarket.domain.valueobjects.OrderStatus;

/**
 * Agregado raíz que representa un pedido de compra. Consolida productos de un
 * único vendedor en la versión actual (por ello posee una sola
 * {@code invoiceId} y la {@code Invoice} requiere {@code sellerId}).
 *
 * <p>Flujo: {@code PENDING_PAYMENT → PAID → DISPATCHED → DELIVERED};
 * cancelable desde {@code PENDING_PAYMENT | PAID}. Un pedido {@code DELIVERED}
 * o {@code CANCELLED} no puede modificarse.</p>
 */
public class Order {

    private final OrderId orderId;
    private final BuyerId buyerId;
    private final List<OrderItem> items;
    private OrderStatus status;
    private final Money total;
    private final Address shippingAddress;
    private InvoiceId invoiceId;
    private LocalDateTime deliveredAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Order(OrderId orderId, BuyerId buyerId, List<OrderItem> items, OrderStatus status,
                 Money total, Address shippingAddress, InvoiceId invoiceId,
                 LocalDateTime deliveredAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.orderId = Objects.requireNonNull(orderId, "orderId es obligatorio");
        this.buyerId = Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        if (items == null || items.isEmpty()) {
            throw new BusinessException("EMPTY_ORDER", "Un pedido debe contener al menos un ítem.");
        }
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.total = calculateTotal(items);
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        this.shippingAddress = Objects.requireNonNull(shippingAddress, "shippingAddress es obligatorio");
        this.invoiceId = invoiceId;
        this.deliveredAt = deliveredAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
    }

    /**
     * Crea un pedido pendiente de pago a partir de los ítems congelados.
     */
    public static Order create(BuyerId buyerId, List<OrderItem> items, Address shippingAddress) {
        LocalDateTime now = LocalDateTime.now();
        return new Order(OrderId.generate(), buyerId, items, OrderStatus.PENDING_PAYMENT,
                null, shippingAddress, null, null, now, now);
    }

    /**
     * {@code PENDING_PAYMENT → PAID}.
     */
    public void confirmPayment() {
        requireStatus(OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.PAID;
        touch();
    }

    /**
     * {@code PAID → DISPATCHED}.
     */
    public void dispatch() {
        requireStatus(OrderStatus.PAID);
        this.status = OrderStatus.DISPATCHED;
        touch();
    }

    /**
     * {@code DISPATCHED → DELIVERED}. Asigna {@code deliveredAt}, usado para
     * calcular la elegibilidad de devoluciones.
     */
    public void deliver() {
        requireStatus(OrderStatus.DISPATCHED);
        this.status = OrderStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
        touch();
    }

    /**
     * {@code PENDING_PAYMENT | PAID → CANCELLED}.
     */
    public void cancel() {
        if (status != OrderStatus.PENDING_PAYMENT && status != OrderStatus.PAID) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo pueden cancelarse pedidos PENDING_PAYMENT o PAID.");
        }
        this.status = OrderStatus.CANCELLED;
        touch();
    }

    public void assignInvoice(InvoiceId invoiceId) {
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId es obligatorio");
        touch();
    }

    private void requireStatus(OrderStatus required) {
        if (status != required) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "La operación requiere un pedido en estado " + required.getCode()
                            + " (actual: " + status.getCode() + ").");
        }
    }

    private static Money calculateTotal(List<OrderItem> items) {
        Money sum = Money.zero(items.get(0).getTotal().getCurrency());
        for (OrderItem item : items) {
            if (!sum.getCurrency().equals(item.getTotal().getCurrency())) {
                throw new BusinessException("CURRENCY_MISMATCH",
                        "Todos los ítems del pedido deben estar en la misma moneda.");
            }
            sum = sum.add(item.getTotal());
        }
        return sum;
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public BuyerId getBuyerId() {
        return buyerId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Money getTotal() {
        return total;
    }

    public Address getShippingAddress() {
        return shippingAddress;
    }

    public InvoiceId getInvoiceId() {
        return invoiceId;
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}