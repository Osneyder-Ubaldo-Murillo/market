package nexus.market.domain.services;

import java.util.Objects;
import java.util.Optional;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Invoice;
import nexus.market.domain.models.Order;
import nexus.market.domain.ports.out.InvoiceRepositoryPort;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.valueobjects.InvoiceId;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderStatus;

/**
 * Servicio de dominio de facturación (SDD - Domain Services:
 * {@code InvoiceService}). Emite la factura de un pedido {code PAID} y permite
 * anularla si aún está {@code ISSUED}.
 */
public class InvoiceService {

    private final InvoiceRepositoryPort invoices;
    private final OrderRepositoryPort orders;
    private final ProductRepositoryPort products;
    private final AuditService audit;

    public InvoiceService(InvoiceRepositoryPort invoices, OrderRepositoryPort orders,
                          ProductRepositoryPort products, AuditService audit) {
        this.invoices = Objects.requireNonNull(invoices, "invoices es obligatorio");
        this.orders = Objects.requireNonNull(orders, "orders es obligatorio");
        this.products = Objects.requireNonNull(products, "products es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    /**
     * Emite la factura {@code ISSUED} de un pedido {@code PAID} y asigna
     * {@code order.invoiceId}.
     *
     * <p>El `sellerId` se resuelve consultando el producto del primer ítem
     * (el pedido es mono-vendedor; ver nota pendiente de la SDD).</p>
     */
    public Invoice generateInvoice(OrderId orderId) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo se factura un pedido PAID.");
        }

        var sellerId = products.findById(order.getItems().get(0).getProductId())
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente."))
                .getSellerId();

        Invoice invoice = invoices.save(Invoice.issue(order.getOrderId(), order.getBuyerId(),
                sellerId, order.getItems(), order.getTotal()));
        order.assignInvoice(invoice.getInvoiceId());
        orders.save(order);

        audit.record(OperationType.INVOICE_GENERATION, AuditSeverity.INFO,
                order.getBuyerId().toString(), "Factura emitida: " + invoice.getInvoiceId());
        return invoice;
    }

    /** Anula la factura ({@code ISSUED → CANCELLED}). */
    public void cancelInvoice(InvoiceId invoiceId) {
        Objects.requireNonNull(invoiceId, "invoiceId es obligatorio");
        Invoice invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new BusinessException("INVOICE_NOT_FOUND", "Factura inexistente."));
        invoice.cancel();
        invoices.save(invoice);
        audit.record(OperationType.INVOICE_CANCELLATION, AuditSeverity.WARNING,
                invoice.getBuyerId().toString(), "Factura anulada: " + invoiceId);
    }

    public Optional<Invoice> findByOrderId(OrderId orderId) {
        return invoices.findByOrderId(orderId);
    }

    public Invoice findById(InvoiceId invoiceId) {
        return invoices.findById(invoiceId)
                .orElseThrow(() -> new BusinessException("INVOICE_NOT_FOUND", "Factura inexistente."));
    }
}