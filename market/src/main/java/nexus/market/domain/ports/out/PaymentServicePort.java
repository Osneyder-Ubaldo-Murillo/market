package nexus.market.domain.ports.out;

import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.PaymentConfirmation;

/**
 * Puerto de salida de la pasarela de pagos: cobro de pedidos y reembolsos.
 * Implementado por {@code adapters.out.payment}.
 */
public interface PaymentServicePort {

    PaymentConfirmation charge(OrderId orderId, Money amount, String paymentMethod);

    /**
     * Reembolsa un cobro. {@code originalTransactionId} es la transacción que devolvió {@link #charge}. En pedidos pagados
     * antes de guardarse esa transacción llega la referencia derivada {@code CHARGE-<pedido>}, que NO es un id emitido por
     * la pasarela: una implementación real debe localizar el cobro por el pedido en ese caso.
     */
    PaymentConfirmation refund(OrderId orderId, Money amount, String originalTransactionId);
}