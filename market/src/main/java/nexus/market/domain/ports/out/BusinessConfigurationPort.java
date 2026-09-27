package nexus.market.domain.ports.out;

import java.util.Currency;

/**
 * Puerto de salida de parámetros de negocio configurables (plazos, moneda y
 * límites). Implementado por {@code adapters.out.config} (propiedades o BD).
 */
public interface BusinessConfigurationPort {

    int refundMaxDays();

    Currency defaultCurrency();

    int maxAdditionalAddresses();

    int maxWarehousesPerSeller();
}