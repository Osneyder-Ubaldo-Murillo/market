package com.nexusmarket.domain.valueobjects;

/**
 * Catálogo de negocio: tipo de bodega ({@code Warehouse}).
 */
public final class WarehouseType extends DomainCatalog {

    public static final WarehouseType MARKETPLACE = new WarehouseType("MARKETPLACE", "Marketplace", "Bodega central de la plataforma");
    public static final WarehouseType SELLER = new WarehouseType("SELLER", "Vendedor", "Bodega privada de un vendedor");

    private WarehouseType(String code, String name, String description) {
        super(code, name, description);
    }

    public static WarehouseType marketplace() {
        return MARKETPLACE;
    }

    public static WarehouseType seller() {
        return SELLER;
    }
}