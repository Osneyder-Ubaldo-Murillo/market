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

    PaymentConfirmation refund(OrderId orderId, Money amount, String originalTransactionId);
}