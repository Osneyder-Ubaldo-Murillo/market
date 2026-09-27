package nexus.market.domain.services;

import java.util.List;
import java.util.Objects;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Order;
import nexus.market.domain.models.Refund;
import nexus.market.domain.models.Return;
import nexus.market.domain.ports.out.BusinessConfigurationPort;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.PaymentServicePort;
import nexus.market.domain.ports.out.RefundRepositoryPort;
import nexus.market.domain.ports.out.ReturnRepositoryPort;
import nexus.market.domain.specifications.RefundEligibilitySpecification;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.PaymentConfirmation;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.RefundId;
import nexus.market.domain.valueobjects.ReturnId;
import nexus.market.domain.valueobjects.ReturnItem;

/**
 * Servicio de dominio de devoluciones y reembolsos (SDD - Domain Services:
 * {@code ReturnRefundService}). La elegibilidad depende del plazo configurado.
 */
public class ReturnRefundService {

    private final ReturnRepositoryPort returns;
    private final RefundRepositoryPort refunds;
    private final OrderRepositoryPort orders;
    private final PaymentServicePort payments;
    private final BusinessConfigurationPort config;
    private final RefundEligibilitySpecification eligibility;
    private final InventoryService inventoryService;
    private final AuditService audit;

    public ReturnRefundService(ReturnRepositoryPort returns, RefundRepositoryPort refunds,
                               OrderRepositoryPort orders, PaymentServicePort payments,
                               BusinessConfigurationPort config,
                               RefundEligibilitySpecification eligibility,
                               InventoryService inventoryService,
                               AuditService audit) {
        this.returns = Objects.requireNonNull(returns, "returns es obligatorio");
        this.refunds = Objects.requireNonNull(refunds, "refunds es obligatorio");
        this.orders = Objects.requireNonNull(orders, "orders es obligatorio");
        this.payments = Objects.requireNonNull(payments, "payments es obligatorio");
        this.config = Objects.requireNonNull(config, "config es obligatorio");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility es obligatorio");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    /** Crea la devolución en {@code REQUESTED} si los ítems son elegibles. */
    public Return requestReturn(OrderId orderId, List<ReturnItem> items) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        if (items == null || items.isEmpty()) {
            throw new BusinessException("EMPTY_RETURN", "Una devolución debe contener al menos un ítem.");
        }
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
        for (ReturnItem item : items) {
            if (!eligibility.isSatisfiedBy(order, item)) {
                throw new BusinessException("RETURN_NOT_ELIGIBLE",
                        "El ítem no es elegible para devolución dentro del plazo vigente.");
            }
        }
        Return aReturn = returns.save(Return.request(orderId, items));
        audit.record(OperationType.RETURN_REQUEST, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Devolución solicitada: " + aReturn.getReturnId());
        return aReturn;
    }

    /**
     * Aprueba la devolución ({@code REQUESTED → APPROVED}) y crea el reembolso
     * {@code PENDING} por el importe de los ítems devueltos.
     */
    public Refund approveReturn(ReturnId returnId) {
        Objects.requireNonNull(returnId, "returnId es obligatorio");
        Return aReturn = loadReturn(returnId);
        Order order = orders.findById(aReturn.getOrderId())
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));

        Money amount = refundAmount(order, aReturn.getItems());
        aReturn.approve();
        Refund refund = refunds.save(
                Refund.createFromReturn(aReturn.getReturnId(), order.getOrderId(), amount));
        returns.save(aReturn);

        audit.record(OperationType.RETURN_APPROVAL, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Devolución aprobada: " + returnId);
        return refund;
    }

    /** Rechaza la devolución ({@code REQUESTED → REJECTED}). */
    public void rejectReturn(ReturnId returnId) {
        Objects.requireNonNull(returnId, "returnId es obligatorio");
        Return aReturn = loadReturn(returnId);
        aReturn.reject();
        returns.save(aReturn);
        audit.record(OperationType.RETURN_REJECTION, AuditSeverity.WARNING,
                aReturn.getOrderId().toString(), "Devolución rechazada: " + returnId);
    }

    /**
     * Procesa el reembolso contra la pasarela: {@code PENDING → PROCESSED} o
     * {@code PENDING → FAILED} si la pasarela no responde.
     */
    public void processRefund(RefundId refundId) {
        Objects.requireNonNull(refundId, "refundId es obligatorio");
        Refund refund = refunds.findById(refundId)
                .orElseThrow(() -> new BusinessException("REFUND_NOT_FOUND", "Reembolso inexistente."));
        try {
            PaymentConfirmation confirmation = payments.refund(refund.getOrderId(),
                    refund.getAmount(), "CHARGE-" + refund.getOrderId());
            refund.process();
            refunds.save(refund);
            audit.record(OperationType.REFUND_PROCESS, AuditSeverity.INFO,
                    refund.getOrderId().toString(), "Reembolso procesado: " + refundId);
        } catch (RuntimeException ex) {
            refund.fail();
            refunds.save(refund);
            audit.record(OperationType.REFUND_PROCESS, AuditSeverity.ERROR,
                    refund.getOrderId().toString(), "Reembolso fallido: " + refundId);
            throw new BusinessException("REFUND_FAILED",
                    "La pasarela no pudo procesar el reembolso " + refundId + ".");
        }
    }

    /** Completa la devolución ({@code APPROVED → PROCESSED}) y repone inventario físico. */
    public void processReturn(ReturnId returnId) {
        Objects.requireNonNull(returnId, "returnId es obligatorio");
        Return aReturn = loadReturn(returnId);
        aReturn.process();

        Order order = orders.findById(aReturn.getOrderId())
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
        String reference = returnId.toString();
        for (ReturnItem item : aReturn.getItems()) {
            // La reposición aplica solo a productos físicos: el tipo se resuelve
            // desde los ítems del pedido (ReturnItem no transporta ProductType).
            boolean physical = order.getItems().stream().anyMatch(oi ->
                    oi.getProductId().equals(item.getProductId())
                            && oi.getProductType() == ProductType.PHYSICAL);
            if (physical) {
                inventoryService.receiveReturnForProduct(item.getProductId(), item.getQuantity(), reference);
            }
        }
        returns.save(aReturn);
    }

    public Return findById(ReturnId returnId) {
        return loadReturn(returnId);
    }

    private Money refundAmount(Order order, List<ReturnItem> items) {
        Money sum = null;
        for (ReturnItem item : items) {
            for (OrderItem orderItem : order.getItems()) {
                if (orderItem.getProductId().equals(item.getProductId())) {
                    Money partial = orderItem.getUnitPrice().multiply(item.getQuantity());
                    sum = (sum == null) ? partial : sum.add(partial);
                    break;
                }
            }
        }
        if (sum == null) {
            throw new BusinessException("EMPTY_RETURN",
                    "Los ítems devueltos no pertenecen al pedido.");
        }
        return sum;
    }

    private Return loadReturn(ReturnId returnId) {
        Objects.requireNonNull(returnId, "returnId es obligatorio");
        return returns.findById(returnId)
                .orElseThrow(() -> new BusinessException("RETURN_NOT_FOUND", "Devolución inexistente."));
    }
}