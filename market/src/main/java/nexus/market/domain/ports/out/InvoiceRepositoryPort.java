package nexus.market.domain.ports.out;

import java.util.Optional;

import nexus.market.domain.models.Invoice;
import nexus.market.domain.valueobjects.InvoiceId;
import nexus.market.domain.valueobjects.OrderId;

/**
 * Puerto de salida para la persistencia de {@link Invoice}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface InvoiceRepositoryPort {

    Invoice save(Invoice invoice);

    Optional<Invoice> findById(InvoiceId invoiceId);

    Optional<Invoice> findByOrderId(OrderId orderId);
}