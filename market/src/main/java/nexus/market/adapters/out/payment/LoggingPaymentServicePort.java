package nexus.market.adapters.out.payment;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import nexus.market.domain.ports.out.PaymentServicePort;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.PaymentConfirmation;

/**
 * Adaptador de salida {@link PaymentServicePort}: pasarela de pagos simulada.
 *
 * <p>No realiza cobros reales: genera una confirmación con un identificador de
 * transacción determinista derivado del pedido y registra la operación en el
 * log. En producción debe reemplazarse por un adaptador de la pasarela
 * elegida (Stripe, PayU, etc.) que implemente el mismo puerto.</p>
 */
@Component
public class LoggingPaymentServicePort implements PaymentServicePort {

    private static final Logger log = LoggerFactory.getLogger(LoggingPaymentServicePort.class);

    @Override
    public PaymentConfirmation charge(OrderId orderId, Money amount, String paymentMethod) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Objects.requireNonNull(amount, "amount es obligatorio");
        String method = paymentMethod == null ? "CARD" : paymentMethod;
        String transactionId = "TXN-" + UUID.nameUUIDFromBytes(
                ("charge:" + orderId.value()).getBytes()).toString().substring(0, 12);
        log.info("[PAYMENT] charge order={} amount={} method={} txn={}",
                orderId, amount, method, transactionId);
        return PaymentConfirmation.of(transactionId, LocalDateTime.now(), method);
    }

    @Override
    public PaymentConfirmation refund(OrderId orderId, Money amount, String originalTransactionId) {
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Objects.requireNonNull(amount, "amount es obligatorio");
        String method = "REFUND";
        String transactionId = "RFN-" + UUID.nameUUIDFromBytes(
                ("refund:" + orderId.value()).getBytes()).toString().substring(0, 12);
        log.info("[PAYMENT] refund order={} amount={} original={} txn={}",
                orderId, amount, originalTransactionId, transactionId);
        return PaymentConfirmation.of(transactionId, LocalDateTime.now(), method);
    }
}