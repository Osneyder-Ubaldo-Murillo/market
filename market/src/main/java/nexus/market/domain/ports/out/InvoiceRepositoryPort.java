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

    /** Una página de las facturas del comprador, de la más reciente a la más antigua (orden estable). */
    java.util.List<Invoice> findByBuyerId(nexus.market.domain.valueobjects.BuyerId buyerId, int offset, int limit);

    long countByBuyerId(nexus.market.domain.valueobjects.BuyerId buyerId);
}