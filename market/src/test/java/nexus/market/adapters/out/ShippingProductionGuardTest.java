package nexus.market.adapters.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import nexus.market.adapters.out.shipping.SimulatedShippingProviderPort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * La transportadora simulada solo es académica: con el perfil {@code prod} queda bloqueada salvo autorización expresa
 * ({@code nexusmarket.shipping.simulated.allowed=true}); fuera de producción funciona sin configuración.
 */
class ShippingProductionGuardTest {

    private static MockEnvironment env(boolean prod, String allowed) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(prod ? new String[] { "jpa", "prod" } : new String[] { "jpa" });
        if (allowed != null) {
            env.setProperty(SimulatedShippingProviderPort.SIMULATED_ALLOWED_PROPERTY, allowed);
        }
        return env;
    }

    @Test
    void production_withoutExplicitAuthorization_refusesToStart() {
        assertThatThrownBy(() -> new SimulatedShippingProviderPort(env(true, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SimulatedShippingProviderPort.SIMULATED_ALLOWED_PROPERTY);
    }

    @Test
    void production_withFalseEmptyOrOtherValue_refusesToStart() {
        for (String value : new String[] { "false", "FALSE", "no", "0", "", " ", "yes", "1" }) {
            assertThatThrownBy(() -> new SimulatedShippingProviderPort(env(true, value)))
                    .as("valor: [%s]", value)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void production_withExplicitAuthorization_starts_andStillSimulates() {
        SimulatedShippingProviderPort shipping = new SimulatedShippingProviderPort(env(true, "true"));
        assertThat(shipping.createShipment(OrderId.generate(), destination(), WarehouseId.generate()).getTrackingNumber())
                .startsWith("TRK-");
    }

    @Test
    void outsideProduction_startsWithoutConfiguration() {
        assertThatCode(() -> new SimulatedShippingProviderPort(env(false, null))).doesNotThrowAnyException();
        assertThatCode(() -> new SimulatedShippingProviderPort()).doesNotThrowAnyException();
    }

    @Test
    void errorMessage_doesNotLeakConfiguredValues() {
        MockEnvironment env = env(true, "valor-que-no-debe-aparecer");
        assertThatThrownBy(() -> new SimulatedShippingProviderPort(env))
                .hasMessageNotContaining("valor-que-no-debe-aparecer");
    }

    private static Address destination() {
        return Address.of("Calle 1", "2-3", null, "Centro", "Bogotá", "Cundinamarca", "110111", "CO");
    }
}
