package nexus.market.adapters.out.payment;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
 *
 * <p><b>Bloqueado en producción por defecto:</b> con el perfil {@code prod} activo la aplicación no arranca
 * mientras {@value #SIMULATED_ALLOWED_PROPERTY} no sea {@code true}. Es una decisión explícita y académica:
 * un despliegue real no debe responder "pago confirmado" sin cobrar.</p>
 */
@Component
public class LoggingPaymentServicePort implements PaymentServicePort {

    /** Propiedad que autoriza expresamente el pago simulado con el perfil {@code prod}. */
    public static final String SIMULATED_ALLOWED_PROPERTY = "nexusmarket.payment.simulated.allowed";

    private static final Logger log = LoggerFactory.getLogger(LoggingPaymentServicePort.class);

    @Autowired
    public LoggingPaymentServicePort(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            // Solo el texto "true" autoriza; cualquier otro valor bloquea (y no pasa por la conversión de Spring).
            if (!"true".equalsIgnoreCase(environment.getProperty(SIMULATED_ALLOWED_PROPERTY, "").trim())) {
                throw new IllegalStateException("El pago simulado está bloqueado con el perfil prod. Este proyecto "
                        + "solo tiene una pasarela simulada (no cobra): para un uso académico declare "
                        + SIMULATED_ALLOWED_PROPERTY + "=true (NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED=true) o "
                        + "reemplace el adaptador por una pasarela real.");
            }
            log.warn("[PAYMENT] Pasarela SIMULADA habilitada en producción por {}: no se realizan cobros reales.",
                    SIMULATED_ALLOWED_PROPERTY);
        }
    }

    /** Sin perfil de producción (desarrollo y pruebas unitarias). */
    public LoggingPaymentServicePort() {
        // sin comprobaciones: el bloqueo solo aplica con el perfil prod
    }

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