package com.nexusmarket.domain.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.nexusmarket.domain.exceptions.BusinessException;
import com.nexusmarket.domain.valueobjects.OrderId;
import com.nexusmarket.domain.valueobjects.ReturnId;
import com.nexusmarket.domain.valueobjects.ReturnItem;
import com.nexusmarket.domain.valueobjects.ReturnStatus;

/**
 * Agregado raíz que representa una devolución sobre un pedido entregado. El
 * motivo de devolución es por ítem ({@link ReturnItem}). Al aprobarse la
 * devolución se crea el {@link Refund}.
 */
public class Return {

    private final ReturnId returnId;
    private final OrderId orderId;
    private final List<ReturnItem> items;
    private ReturnStatus status;
    private final LocalDateTime requestDate;
    private LocalDateTime resolutionDate;

    public Return(ReturnId returnId, OrderId orderId, List<ReturnItem> items,
                  ReturnStatus status, LocalDateTime requestDate, LocalDateTime resolutionDate) {
        this.returnId = Objects.requireNonNull(returnId, "returnId es obligatorio");
        this.orderId = Objects.requireNonNull(orderId, "orderId es obligatorio");
        if (items == null || items.isEmpty()) {
            throw new BusinessException("EMPTY_RETURN", "Una devolución debe contener al menos un ítem.");
        }
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        this.requestDate = Objects.requireNonNull(requestDate, "requestDate es obligatorio");
        this.resolutionDate = resolutionDate;
    }

    /**
     * Inicia una devolución en estado {@code REQUESTED}.
     */
    public static Return request(OrderId orderId, List<ReturnItem> items) {
        return new Return(ReturnId.generate(), orderId, items,
                ReturnStatus.REQUESTED, LocalDateTime.now(), null);
    }

    /** {@code REQUESTED → APPROVED}. Al aprobar se debe crear el {@link Refund}. */
    public void approve() {
        requireStatus(ReturnStatus.REQUESTED);
        this.status = ReturnStatus.APPROVED;
        this.resolutionDate = LocalDateTime.now();
    }

    /** {@code REQUESTED → REJECTED}. */
    public void reject() {
        requireStatus(ReturnStatus.REQUESTED);
        this.status = ReturnStatus.REJECTED;
        this.resolutionDate = LocalDateTime.now();
    }

    /** {@code APPROVED → PROCESSED}. */
    public void process() {
        requireStatus(ReturnStatus.APPROVED);
        this.status = ReturnStatus.PROCESSED;
        this.resolutionDate = LocalDateTime.now();
    }

    private void requireStatus(ReturnStatus required) {
        if (status != required) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "La operación requiere una devolución en estado " + required.getCode()
                            + " (actual: " + status.getCode() + ").");
        }
    }

    public ReturnId getReturnId() {
        return returnId;
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public List<ReturnItem> getItems() {
        return items;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public LocalDateTime getRequestDate() {
        return requestDate;
    }

    public LocalDateTime getResolutionDate() {
        return resolutionDate;
    }
}