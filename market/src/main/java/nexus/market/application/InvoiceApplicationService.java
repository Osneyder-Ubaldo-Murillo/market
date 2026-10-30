package nexus.market.application;

import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.domain.ports.in.InvoiceUseCases;
import nexus.market.domain.models.Invoice;
import nexus.market.domain.services.InvoiceService;
import nexus.market.domain.valueobjects.InvoiceId;
import nexus.market.domain.valueobjects.OrderId;

/**
 * Servicio de aplicación de facturación: delega en {@code InvoiceService}.
 */
@Service
@Profile("jpa")
public class InvoiceApplicationService implements InvoiceUseCases {

    private final InvoiceService delegate;

    public InvoiceApplicationService(InvoiceService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Invoice generateInvoice(OrderId orderId) {
        return delegate.generateInvoice(orderId);
    }

    @Override
    public void cancelInvoice(InvoiceId invoiceId) {
        delegate.cancelInvoice(invoiceId);
    }

    @Override
    public java.util.List<Invoice> findByBuyer(nexus.market.domain.valueobjects.BuyerId buyerId, int offset,
                                               int limit) {
        return delegate.findByBuyer(buyerId, offset, limit);
    }

    @Override
    public long countByBuyer(nexus.market.domain.valueobjects.BuyerId buyerId) {
        return delegate.countByBuyer(buyerId);
    }

    @Override
    public Optional<Invoice> findByOrderId(OrderId orderId) {
        return delegate.findByOrderId(orderId);
    }

    @Override
    public Invoice findById(InvoiceId invoiceId) {
        return delegate.findById(invoiceId);
    }
}
