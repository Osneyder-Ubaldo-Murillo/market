package nexus.market.domain.ports.in;

import java.util.List;

import nexus.market.domain.models.Refund;
import nexus.market.domain.models.Return;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.RefundId;
import nexus.market.domain.valueobjects.ReturnId;
import nexus.market.domain.valueobjects.ReturnReason;

/**
 * Puerto de entrada (caso de uso) de devoluciones y reembolsos. Delega en
 * {@code ReturnRefundService}.
 *
 * <p>El cliente solo envía {@code productId}, cantidad y motivo: la capa de
 * aplicación resuelve el nombre del producto desde el catálogo para construir
 * el {@code ReturnItem} del dominio (ver {@code ReturnRequestLine}).</p>
 */
public interface ReturnRefundUseCases {

    Return requestReturn(OrderId orderId, List<ReturnRequestLine> lines);

    Refund approveReturn(ReturnId returnId);

    void rejectReturn(ReturnId returnId);

    void processReturn(ReturnId returnId);

    void processRefund(RefundId refundId);

    Return findById(ReturnId returnId);

    /** Línea de devolución en términos del dominio (sin nombre de producto). */
    record ReturnRequestLine(ProductId productId, Quantity quantity, ReturnReason reason) {
    }
}

