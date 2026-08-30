package nexus.market.domain.models;

import java.time.LocalDateTime;
import java.util.Objects;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.RefundId;
import nexus.market.domain.valueobjects.RefundStatus;
import nexus.market.domain.valueobjects.ReturnId;

/**
 * Agregado raíz que representa un reembolso. {@code returnId} es opcional:
 * presente cuando el reembolso proviene de una devolución.
 */
public class Refund {

    private final RefundId refundId;
    private final ReturnId returnId;
    private final OrderId orderId;
    private final Money amount;
    private RefundStatus status;
    private final LocalDateTime requestDate;
    private LocalDateTime processedDate;

    public Refund(RefundId refundId, ReturnId returnId, OrderId orderId, Money amount,
                  RefundStatus status, LocalDateTime requestDate, LocalDateTime processedDate) {
        this.refundId = Objects.requireNonNull(refundId, "refundId es obligatorio");
        this.returnId = returnId;
        this.orderId = Objects.requireNonNull(orderId, "orderId es obligatorio");
        this.amount = Objects.requireNonNull(amount, "amount es obligatorio");
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        this.requestDate = Objects.requireNonNull(requestDate, "requestDate es obligatorio");
        this.processedDate = processedDate;
    }

    /** Crea un reembolso directo (sin devolución asociada), estado {@code PENDING}. */
    public static Refund create(OrderId orderId, Money amount) {
        return new Refund(RefundId.generate(), null, orderId, amount,
                RefundStatus.PENDING, LocalDateTime.now(), null);
    }

    /** Crea un reembolso originado por una devolución aprobada. */
    public static Refund createFromReturn(ReturnId returnId, OrderId orderId, Money amount) {
        Objects.requireNonNull(returnId, "returnId es obligatorio");
        return new Refund(RefundId.generate(), returnId, orderId, amount,
                RefundStatus.PENDING, LocalDateTime.now(), null);
    }

    /** {@code PENDING → PROCESSED}. */
    public void process() {
        requireStatus(RefundStatus.PENDING);
        this.status = RefundStatus.PROCESSED;
        this.processedDate = LocalDateTime.now();
    }

    /** {@code PENDING → FAILED}. */
    public void fail() {
        requireStatus(RefundStatus.PENDING);
        this.status = RefundStatus.FAILED;
        this.processedDate = LocalDateTime.now();
    }

    private void requireStatus(RefundStatus required) {
        if (status != required) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "La operación requiere un reembolso en estado " + required.getCode());
        }
    }

    public RefundId getRefundId() {
        return refundId;
    }

    public ReturnId getReturnId() {
        return returnId;
    }

    public OrderId getOrderId() {
        return orderId;
    }

    public Money getAmount() {
        return amount;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public LocalDateTime getRequestDate() {
        return requestDate;
    }

    public LocalDateTime getProcessedDate() {
        return processedDate;
    }
}