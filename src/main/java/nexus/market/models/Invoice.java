package com.nexusmarket.domain.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.nexusmarket.domain.exceptions.BusinessException;
import com.nexusmarket.domain.valueobjects.BuyerId;
import com.nexusmarket.domain.valueobjects.InvoiceId;
import com.nexusmarket.domain.valueobjects.InvoiceStatus;
import com.nexusmarket.domain.valueobjects.Money;
import com.nexusmarket.domain.valueobjects.OrderId;
import com.nexusmarket.domain.valueobjects.OrderItem;
import com.nexusmarket.domain.valueobjects.SellerId;

/**
 * Agregado raíz que representa la factura de un pedido. Solo puede cancelarse
 * una factura {@code ISSUED}; si el pedido se cancela tras el pago, la factura
 * debe anularse.
 */
public class Invoice {

    private final InvoiceId invoiceId;
    private final OrderId orderId;
    private final BuyerId buyerId;
    private final SellerId sellerId;
    private final List<OrderItem> items;
    private final Money total;
    private final LocalDateTime issueDate;
    private InvoiceStatus status;

    public Invoice(InvoiceId invoiceId, OrderId orderId, BuyerId buyerId, SellerId sellerId,
                   List<OrderItem> items, Money total, LocalDateTime issueDate, InvoiceStatus status) {
        this.invoiceId = Objects.requireNonNull(invoiceId, "invoiceId es obligatorio");
        this.orderId = Objects.requireNonNull(orderId, "orderId es obligatorio");
        this.buyerId = Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId es obligatorio");
        if (items == null || items.isEmpty()) {
            throw new BusinessException("EMPTY_INVOICE", "Una factura debe contener al menos un ítem.");
        }
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.total = Objects.requireNonNull(total, "total es obligatorio");
        this.issueDate = Objects.requireNonNull(issueDate, "issueDate es obligatorio");
        this.status = Objects.requireNonNull(status, "status es obligatorio");
    }

    /**
     * Emite una factura en estado {@code ISSUED} con el total indicado (debe
     * coincidir con el total verificado del pedido).
     */
    public static Invoice issue(OrderId orderId, BuyerId buyerId, SellerId sellerId,
                                List<OrderItem> items, Money total) {
        LocalDateTime now = LocalDateTime.now();
        return new Invoice(InvoiceId.generate(), orderId, buyerId, sellerId, items, total,
                now, InvoiceStatus.ISSUED);
    }

    /**
     * {@code ISSUED → CANCELLED}.
     */
    public void cancel() {
        if (status != InvoiceStatus.ISSUED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo puede cancelarse una factura en estado ISSUED.");
        }
        this.status = InvoiceStatus.CANCELLED;
    }

    public InvoiceId getInvoiceId() {
        return invoiceId;
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public BuyerId getBuyerId() {
        return buyerId;
    }

    public SellerId getSellerId() {
        return sellerId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public Money getTotal() {
        return total;
    }

    public LocalDateTime getIssueDate() {
        return issueDate;
    }

    public InvoiceStatus getStatus() {
        return status;
    }
}