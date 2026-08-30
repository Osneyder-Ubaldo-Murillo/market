package com.nexusmarket.domain.models;

import java.time.LocalDateTime;
import java.util.Objects;

import com.nexusmarket.domain.exceptions.BusinessException;
import com.nexusmarket.domain.valueobjects.Address;
import com.nexusmarket.domain.valueobjects.SellerId;
import com.nexusmarket.domain.valueobjects.WarehouseId;
import com.nexusmarket.domain.valueobjects.WarehouseStatus;
import com.nexusmarket.domain.valueobjects.WarehouseType;

/**
 * Agregado raíz que representa una bodega de almacenamiento, de tipo
 * {@code MARKETPLACE} (central) o {@code SELLER} (privada). Solo las bodegas
 * {@code ACTIVE} pueden recibir inventario.
 */
public class Warehouse {

    private final WarehouseId warehouseId;
    private final String name;
    private final Address address;
    private final WarehouseType type;
    private final SellerId sellerId;
    private WarehouseStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Warehouse(WarehouseId warehouseId, String name, Address address, WarehouseType type,
                     SellerId sellerId, WarehouseStatus status,
                     LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId es obligatorio");
        if (name == null || name.isBlank() || name.trim().length() < 3 || name.trim().length() > 120) {
            throw new IllegalArgumentException("El nombre de la bodega debe tener entre 3 y 120 caracteres");
        }
        this.name = name.trim();
        this.address = Objects.requireNonNull(address, "address es obligatorio");
        this.type = Objects.requireNonNull(type, "type es obligatorio");
        if (type == WarehouseType.SELLER && sellerId == null) {
            throw new BusinessException("SELLER_REQUIRED",
                    "Una bodega de tipo SELLER requiere un sellerId propietario.");
        }
        if (type == WarehouseType.MARKETPLACE && sellerId != null) {
            throw new BusinessException("SELLER_NOT_ALLOWED",
                    "Una bodega de tipo MARKETPLACE no puede tener sellerId.");
        }
        this.sellerId = sellerId;
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
    }

    public static Warehouse createMarketplace(String name, Address address) {
        LocalDateTime now = LocalDateTime.now();
        return new Warehouse(WarehouseId.generate(), name, address, WarehouseType.MARKETPLACE,
                null, WarehouseStatus.ACTIVE, now, now);
    }

    public static Warehouse createSeller(String name, Address address, SellerId sellerId) {
        LocalDateTime now = LocalDateTime.now();
        return new Warehouse(WarehouseId.generate(), name, address, WarehouseType.SELLER,
                sellerId, WarehouseStatus.ACTIVE, now, now);
    }

    /**
     * {@code INACTIVE → ACTIVE}. Solo así puede recibir inventario.
     */
    public void activate() {
        if (status != WarehouseStatus.INACTIVE) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo una bodega INACTIVE puede activarse.");
        }
        this.status = WarehouseStatus.ACTIVE;
        touch();
    }

    /**
     * {@code ACTIVE → INACTIVE}.
     */
    public void deactivate() {
        if (status != WarehouseStatus.ACTIVE) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo una bodega ACTIVE puede desactivarse.");
        }
        this.status = WarehouseStatus.INACTIVE;
        touch();
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public WarehouseId getWarehouseId() {
        return warehouseId;
    }

    public String getName() {
        return name;
    }

    public Address getAddress() {
        return address;
    }

    public WarehouseType getType() {
        return type;
    }

    public SellerId getSellerId() {
        return sellerId;
    }

    public WarehouseStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}