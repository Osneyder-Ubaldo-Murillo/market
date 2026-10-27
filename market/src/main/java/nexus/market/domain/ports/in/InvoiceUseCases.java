package nexus.market.domain.ports.in;

import java.util.Optional;

import nexus.market.domain.models.Invoice;
import nexus.market.domain.valueobjects.InvoiceId;
import nexus.market.domain.valueobjects.OrderId;

/**
 * Puerto de entrada (caso de uso) de facturación. Delega en
 * {@code InvoiceService}.
 */
public interface InvoiceUseCases {

    Invoice generateInvoice(OrderId orderId);

    void cancelInvoice(InvoiceId invoiceId);

    Optional<Invoice> findByOrderId(OrderId orderId);

    Invoice findById(InvoiceId invoiceId);
}
