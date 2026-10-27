package nexus.market.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Product;
import nexus.market.domain.ports.in.ReturnRefundUseCases;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.services.ReturnRefundService;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.RefundId;
import nexus.market.domain.models.Refund;
import nexus.market.domain.models.Return;
import nexus.market.domain.valueobjects.ReturnId;
import nexus.market.domain.valueobjects.ReturnItem;

/**
 * Servicio de aplicación de devoluciones y reembolsos: traduce las líneas
 * {@code ReturnRequestLine} (productId + cantidad + motivo) a {@code ReturnItem}
 * del dominio resolviendo el nombre del producto desde el catálogo, y delega en
 * {@code ReturnRefundService}.
 */
@Service
@Profile("jpa")
public class ReturnRefundApplicationService implements ReturnRefundUseCases {

    private final ReturnRefundService delegate;
    private final ProductRepositoryPort products;

    public ReturnRefundApplicationService(ReturnRefundService delegate, ProductRepositoryPort products) {
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
        this.products = Objects.requireNonNull(products, "products es obligatorio");
    }

    @Override
    public Return requestReturn(OrderId orderId, List<ReturnRequestLine> lines) {
        Objects.requireNonNull(lines, "lines es obligatorio");
        List<ReturnItem> items = new ArrayList<>();
        for (ReturnRequestLine line : lines) {
            Product product = products.findById(line.productId())
                    .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente."));
            items.add(ReturnItem.of(product.getProductId(), product.getName(), line.quantity(), line.reason()));
        }
        return delegate.requestReturn(orderId, items);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Refund approveReturn(ReturnId returnId) {
        return delegate.approveReturn(returnId);
    }

    @Override
    public void rejectReturn(ReturnId returnId) {
        delegate.rejectReturn(returnId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void processReturn(ReturnId returnId) {
        delegate.processReturn(returnId);
    }

    @Override
    public void processRefund(RefundId refundId) {
        delegate.processRefund(refundId);
    }

    @Override
    public Return findById(ReturnId returnId) {
        return delegate.findById(returnId);
    }
}
