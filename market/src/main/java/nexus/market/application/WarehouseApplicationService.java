package nexus.market.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Warehouse;
import nexus.market.domain.ports.in.WarehouseUseCases;
import nexus.market.domain.ports.out.SellerRepositoryPort;
import nexus.market.domain.ports.out.WarehouseRepositoryPort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Servicio de aplicación de bodegas: aplica las fábricas del agregado
 * {@code Warehouse} sobre el repositorio de salida, validando la existencia
 * del vendedor para las bodegas privadas.
 */
@Service
@Profile("jpa")
public class WarehouseApplicationService implements WarehouseUseCases {

    private final WarehouseRepositoryPort warehouses;
    private final SellerRepositoryPort sellers;

    public WarehouseApplicationService(WarehouseRepositoryPort warehouses, SellerRepositoryPort sellers) {
        this.warehouses = Objects.requireNonNull(warehouses, "warehouses es obligatorio");
        this.sellers = Objects.requireNonNull(sellers, "sellers es obligatorio");
    }

    @Override
    public Warehouse createMarketplaceWarehouse(String name, Address address) {
        return warehouses.save(Warehouse.createMarketplace(name, address));
    }

    @Override
    public Warehouse createSellerWarehouse(SellerId sellerId, String name, Address address) {
        Objects.requireNonNull(sellerId, "sellerId es obligatorio");
        sellers.findById(sellerId)
                .orElseThrow(() -> new BusinessException("SELLER_NOT_FOUND", "Vendedor inexistente."));
        return warehouses.save(Warehouse.createSeller(name, address, sellerId));
    }

    @Override
    public Optional<Warehouse> findById(WarehouseId warehouseId) {
        return warehouses.findById(warehouseId);
    }

    @Override
    public List<Warehouse> findBySeller(SellerId sellerId) {
        return warehouses.findBySellerId(sellerId);
    }
}
