package nexus.market.adapters.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import nexus.market.adapters.out.payment.LoggingPaymentServicePort;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderId;

/**
 * El pago simulado solo es académico: con el perfil {@code prod} queda bloqueado salvo autorización expresa
 * ({@code nexusmarket.payment.simulated.allowed=true}); fuera de producción funciona sin configuración.
 */
class PaymentProductionGuardTest {

    private static MockEnvironment env(boolean prod, String allowed) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(prod ? new String[] { "jpa", "prod" } : new String[] { "jpa" });
        if (allowed != null) {
            env.setProperty(LoggingPaymentServicePort.SIMULATED_ALLOWED_PROPERTY, allowed);
        }
        return env;
    }

    @Test
    void production_withoutExplicitAuthorization_refusesToStart() {
        assertThatThrownBy(() -> new LoggingPaymentServicePort(env(true, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LoggingPaymentServicePort.SIMULATED_ALLOWED_PROPERTY);
    }

    @Test
    void production_withFalseEmptyOrOtherValue_refusesToStart() {
        for (String value : new String[] { "false", "FALSE", "no", "0", "", " " }) {
            assertThatThrownBy(() -> new LoggingPaymentServicePort(env(true, value)))
                    .as("valor: [%s]", value)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void production_withExplicitAuthorization_starts_andStillSimulates() {
        LoggingPaymentServicePort payments = new LoggingPaymentServicePort(env(true, "true"));
        assertThat(payments.charge(OrderId.generate(), Money.of(10, "USD"), "CARD").getTransactionId())
                .startsWith("TXN-");
    }

    @Test
    void outsideProduction_startsWithoutConfiguration() {
        assertThatCode(() -> new LoggingPaymentServicePort(env(false, null))).doesNotThrowAnyException();
        assertThatCode(() -> new LoggingPaymentServicePort()).doesNotThrowAnyException();
    }

    @Test
    void errorMessage_doesNotLeakConfiguredValues() {
        MockEnvironment env = env(true, "valor-que-no-debe-aparecer");
        assertThatThrownBy(() -> new LoggingPaymentServicePort(env))
                .hasMessageNotContaining("valor-que-no-debe-aparecer");
    }
}
