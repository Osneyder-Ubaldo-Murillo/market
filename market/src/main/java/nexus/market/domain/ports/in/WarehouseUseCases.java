package nexus.market.domain.ports.in;

import java.util.List;
import java.util.Optional;

import nexus.market.domain.models.Warehouse;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Puerto de entrada (caso de uso) de bodegas. No existe servicio de dominio
 * equivalente (la creación solo aplica las fábricas del agregado
 * {@code Warehouse}); la capa de aplicación lo orquesta con el repositorio de
 * salida y valida que el vendedor exista.
 */
public interface WarehouseUseCases {

    Warehouse createMarketplaceWarehouse(String name, Address address);

    Warehouse createSellerWarehouse(SellerId sellerId, String name, Address address);

    Optional<Warehouse> findById(WarehouseId warehouseId);

    List<Warehouse> findBySeller(SellerId sellerId);
}
