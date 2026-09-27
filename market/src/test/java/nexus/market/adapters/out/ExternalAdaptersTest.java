package nexus.market.adapters.out;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import nexus.market.adapters.out.payment.LoggingPaymentServicePort;
import nexus.market.adapters.out.security.BcryptPasswordServicePort;
import nexus.market.adapters.out.security.JwtJwtServicePort;
import nexus.market.adapters.out.shipping.SimulatedShippingProviderPort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.PaymentConfirmation;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.WarehouseId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias de los adaptadores de salida que no requieren base de
 * datos (seguridad, pasarela de pagos simulada y transportadora simulada).
 */
class ExternalAdaptersTest {

    private static final String JWT_SECRET = "test-secret-1234567890abcdef";

    @Test
    void bcryptHashYVerificacion() {
        BcryptPasswordServicePort service = new BcryptPasswordServicePort();

        String hash = service.hash("MiClaveSecreta#2026");
        assertThat(hash).isNotEqualTo("MiClaveSecreta#2026");
        assertThat(hash).startsWith("$2");

        assertThat(service.verify("MiClaveSecreta#2026", hash)).isTrue();
        assertThat(service.verify("otra-clave", hash)).isFalse();
        assertThat(service.verify(null, hash)).isFalse();
        assertThat(service.verify("MiClaveSecreta#2026", "no-es-un-hash")).isFalse();
    }

    @Test
    void jwtEmitirValidarYExtraerUsuario() {
        JwtJwtServicePort service = new JwtJwtServicePort(JWT_SECRET, "nexusmarket", 86_400_000L);
        UserId userId = UserId.generate();

        String token = service.issue(userId, SystemRole.BUYER);
        assertThat(token).isNotBlank();

        assertThat(service.isValid(token)).isTrue();
        Optional<UserId> recovered = service.getUserId(token);
        assertThat(recovered).isPresent();
        assertThat(recovered.get()).isEqualTo(userId);

        assertThat(service.isValid("tokén-inválido")).isFalse();
        assertThat(service.getUserId(null)).isEmpty();
        assertThat(service.getUserId("")).isEmpty();
    }

    @Test
    void pasarelaDePagosSimuladaDevuelveConfirmaciones() {
        LoggingPaymentServicePort payments = new LoggingPaymentServicePort();
        OrderId orderId = OrderId.generate();
        Money amount = Money.of(new BigDecimal("150.50"), Currency.getInstance("USD"));

        PaymentConfirmation charge = payments.charge(orderId, amount, "CARD");
        assertThat(charge.getTransactionId()).startsWith("TXN-");
        assertThat(charge.getPaymentMethod()).isEqualTo("CARD");
        assertThat(charge.getDate()).isNotNull();

        PaymentConfirmation refund = payments.refund(orderId, amount, charge.getTransactionId());
        assertThat(refund.getTransactionId()).startsWith("RFN-");
        assertThat(refund.getPaymentMethod()).isEqualTo("REFUND");
    }

    @Test
    void transportadoraSimuladaGeneraSeguimiento() {
        SimulatedShippingProviderPort provider = new SimulatedShippingProviderPort();
        OrderId orderId = OrderId.generate();
        WarehouseId warehouseId = WarehouseId.generate();
        Address destination = Address.of("Calle 1", "2-3", null, "Centro",
                "Bogotá", "Cundinamarca", "110001", "CO");

        var info = provider.createShipment(orderId, destination, warehouseId);
        assertThat(info.getCarrier()).isEqualTo("SimulCarrier");
        assertThat(info.hasTrackingNumber()).isTrue();
        assertThat(info.getEstimatedDate()).isNotNull();
    }
}