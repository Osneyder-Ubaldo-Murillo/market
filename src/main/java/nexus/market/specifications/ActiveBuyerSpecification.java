package com.nexusmarket.domain.specifications;

import com.nexusmarket.domain.models.Buyer;
import com.nexusmarket.domain.valueobjects.CommercialStatus;

/**
 * Verifica que un comprador tenga estado comercial activo
 * ({@code CommercialStatus.ACTIVE}). Solo compradores activos compran.
 */
public class ActiveBuyerSpecification {

    public boolean isSatisfiedBy(Buyer buyer) {
        return buyer != null && buyer.getCommercialStatus() == CommercialStatus.ACTIVE;
    }
}