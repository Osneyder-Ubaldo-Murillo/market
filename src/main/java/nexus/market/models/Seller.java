package com.nexusmarket.domain.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.nexusmarket.domain.exceptions.BusinessException;
import com.nexusmarket.domain.valueobjects.BusinessName;
import com.nexusmarket.domain.valueobjects.SellerId;
import com.nexusmarket.domain.valueobjects.SellerStatus;
import com.nexusmarket.domain.valueobjects.TaxId;
import com.nexusmarket.domain.valueobjects.UserId;
import com.nexusmarket.domain.valueobjects.WarehouseId;

/**
 * Agregado raíz que extiende la información de un {@link User} con rol
 * {@code SELLER}. Los vendedores no pueden auto-registrarse; solo un
 * Administrador puede crearlos. Estado inicial {@code PENDING_VERIFICATION} y
 * máximo 10 bodegas asociadas.
 */
public class Seller {

    public static final int MAX_WAREHOUSES = 10;

    private final SellerId sellerId;
    private final UserId userId;
    private final BusinessName businessName;
    private final TaxId taxId;
    private SellerStatus status;
    private final List<WarehouseId> warehouses = new ArrayList<>();
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Seller(SellerId sellerId, UserId userId, BusinessName businessName, TaxId taxId,
                  SellerStatus status, List<WarehouseId> warehouses,
                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId es obligatorio");
        this.userId = Objects.requireNonNull(userId, "userId es obligatorio");
        this.businessName = Objects.requireNonNull(businessName, "businessName es obligatorio");
        this.taxId = Objects.requireNonNull(taxId, "taxId es obligatorio");
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        if (warehouses != null) {
            if (warehouses.size() > MAX_WAREHOUSES) {
                throw new BusinessException("WAREHOUSE_LIMIT_EXCEEDED",
                        "El vendedor no puede tener más de 10 bodegas asociadas.");
            }
            this.warehouses.addAll(warehouses);
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
    }

    /**
     * Crea un vendedor nuevo en estado {@code PENDING_VERIFICATION}.
     */
    public static Seller create(UserId userId, BusinessName businessName, TaxId taxId) {
        LocalDateTime now = LocalDateTime.now();
        return new Seller(SellerId.generate(), userId, businessName, taxId,
                SellerStatus.PENDING_VERIFICATION, List.of(), now, now);
    }

    /**
     * {@code PENDING_VERIFICATION → ACTIVE}. Solo el Administrador aprueba.
     */
    public void approve() {
        if (status != SellerStatus.PENDING_VERIFICATION) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo un vendedor PENDING_VERIFICATION puede ser aprobado.");
        }
        this.status = SellerStatus.ACTIVE;
        touch();
    }

    public void block() {
        requireActive();
        this.status = SellerStatus.BLOCKED;
        touch();
    }

    public void activate() {
        if (status != SellerStatus.BLOCKED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo un vendedor BLOCKED puede volver al estado ACTIVE.");
        }
        this.status = SellerStatus.ACTIVE;
        touch();
    }

    public void deactivate() {
        requireActive();
        this.status = SellerStatus.INACTIVE;
        touch();
    }

    /**
     * Asocia una bodega validando el límite de 10 y la no duplicación.
     */
    public void addWarehouse(WarehouseId warehouseId) {
        Objects.requireNonNull(warehouseId, "warehouseId es obligatorio");
        if (warehouses.size() >= MAX_WAREHOUSES) {
            throw new BusinessException("WAREHOUSE_LIMIT_EXCEEDED",
                    "El vendedor no puede tener más de 10 bodegas asociadas.");
        }
        if (warehouses.contains(warehouseId)) {
            throw new BusinessException("DUPLICATE_WAREHOUSE",
                    "La bodega ya está asociada al vendedor.");
        }
        warehouses.add(warehouseId);
        touch();
    }

    private void requireActive() {
        if (status != SellerStatus.ACTIVE) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "La operación requiere un vendedor en estado ACTIVE.");
        }
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public SellerId getSellerId() {
        return sellerId;
    }

    public UserId getUserId() {
        return userId;
    }

    public BusinessName getBusinessName() {
        return businessName;
    }

    public TaxId getTaxId() {
        return taxId;
    }

    public SellerStatus getStatus() {
        return status;
    }

    public List<WarehouseId> getWarehouses() {
        return Collections.unmodifiableList(warehouses);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}