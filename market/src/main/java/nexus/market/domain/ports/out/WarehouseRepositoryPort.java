package nexus.market.domain.ports.out;

import java.util.List;
import java.util.Optional;

import nexus.market.domain.models.Warehouse;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Puerto de salida para la persistencia de {@link Warehouse}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface WarehouseRepositoryPort {

    Warehouse save(Warehouse warehouse);

    Optional<Warehouse> findById(WarehouseId warehouseId);

    List<Warehouse> findBySellerId(SellerId sellerId);
}