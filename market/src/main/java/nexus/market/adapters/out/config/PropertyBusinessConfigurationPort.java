package nexus.market.adapters.out.config;

import java.util.Currency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import nexus.market.domain.ports.out.BusinessConfigurationPort;

/**
 * Adaptador de salida {@link BusinessConfigurationPort}: parámetros de negocio
 * configurables por propiedades (con valores por defecto razonables).
 */
@Component
public class PropertyBusinessConfigurationPort implements BusinessConfigurationPort {

    private final int refundMaxDays;
    private final String defaultCurrencyCode;
    private final int maxAdditionalAddresses;
    private final int maxWarehousesPerSeller;

    public PropertyBusinessConfigurationPort(
            @Value("${nexusmarket.refund-max-days:15}") int refundMaxDays,
            @Value("${nexusmarket.default-currency:COP}") String defaultCurrencyCode,
            @Value("${nexusmarket.max-additional-addresses:10}") int maxAdditionalAddresses,
            @Value("${nexusmarket.max-warehouses-per-seller:10}") int maxWarehousesPerSeller) {
        this.refundMaxDays = refundMaxDays;
        this.defaultCurrencyCode = defaultCurrencyCode;
        this.maxAdditionalAddresses = maxAdditionalAddresses;
        this.maxWarehousesPerSeller = maxWarehousesPerSeller;
    }

    @Override
    public int refundMaxDays() {
        return refundMaxDays;
    }

    @Override
    public Currency defaultCurrency() {
        return Currency.getInstance(defaultCurrencyCode);
    }

    @Override
    public int maxAdditionalAddresses() {
        return maxAdditionalAddresses;
    }

    @Override
    public int maxWarehousesPerSeller() {
        return maxWarehousesPerSeller;
    }
}