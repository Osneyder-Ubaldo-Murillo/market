package nexus.market.domain.specifications;

import nexus.market.domain.models.Buyer;
import nexus.market.domain.valueobjects.CommercialStatus;

/**
 * Verifica que un comprador tenga estado comercial activo
 * ({@code CommercialStatus.ACTIVE}). Solo compradores activos compran.
 */
public class ActiveBuyerSpecification {

    public boolean isSatisfiedBy(Buyer buyer) {
        return buyer != null && buyer.getCommercialStatus() == CommercialStatus.ACTIVE;
    }
}