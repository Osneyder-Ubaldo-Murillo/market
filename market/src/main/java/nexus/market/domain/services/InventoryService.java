package nexus.market.domain.services;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.exceptions.InsufficientInventoryException;
import nexus.market.domain.models.Inventory;
import nexus.market.domain.ports.out.InventoryRepositoryPort;
import nexus.market.domain.ports.out.WarehouseRepositoryPort;
import nexus.market.domain.specifications.AvailableInventorySpecification;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.InventoryStatus;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Servicio de dominio de inventario (SDD - Domain Services:
 * {@code InventoryService}): existencias, reservas, ventas, ajustes y
 * devoluciones. Todo cambio queda en {@code Inventory.movements} y además se
 * audita.
 */
public class InventoryService {

    private final InventoryRepositoryPort inventories;
    private final WarehouseRepositoryPort warehouses;
    private final AvailableInventorySpecification available;
    private final AuditService audit;

    public InventoryService(InventoryRepositoryPort inventories,
                            WarehouseRepositoryPort warehouses,
                            AvailableInventorySpecification available,
                            AuditService audit) {
        this.inventories = Objects.requireNonNull(inventories, "inventories es obligatorio");
        this.warehouses = Objects.requireNonNull(warehouses, "warehouses es obligatorio");
        this.available = Objects.requireNonNull(available, "available es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    /**
     * Crea el inventario en cero ({@code OUT_OF_STOCK}) para un par
     * (producto, bodega). La bodega debe existir y estar {@code ACTIVE}.
     */
    public Inventory createInventory(ProductId productId, WarehouseId warehouseId) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        Objects.requireNonNull(warehouseId, "warehouseId es obligatorio");

        warehouses.findById(warehouseId)
                .orElseThrow(() -> new BusinessException("WAREHOUSE_NOT_FOUND", "Bodega inexistente."));
        if (inventories.findByProductIdAndWarehouseId(productId, warehouseId).isPresent()) {
            throw new BusinessException("INVENTORY_ALREADY_EXISTS",
                    "Ya existe inventario para el par (producto, bodega).");
        }
        Inventory inventory = inventories.save(Inventory.create(productId, warehouseId));
        audit.record(OperationType.INVENTORY_ADD, AuditSeverity.INFO,
                productId.toString(), "Inventario creado: " + inventory.getInventoryId());
        return inventory;
    }

    /** Ingreso de mercancía ({@code OUT_OF_STOCK → ACTIVE}). */
    public void addStock(InventoryId inventoryId, Quantity quantity, Map<String, Object> metadata) {
        Inventory inventory = loadInventory(inventoryId);
        inventory.addStock(quantity, metadata);
        inventories.save(inventory);
        audit.record(OperationType.INVENTORY_ADD, AuditSeverity.INFO,
                inventory.getProductId().toString(), "Ingreso: " + quantity);
    }

    /** Reserva existencias para un pedido (no sobre {@code DAMAGED}). */
    public void reserve(InventoryId inventoryId, Quantity quantity, Map<String, Object> metadata) {
        Inventory inventory = loadInventory(inventoryId);
        if (!available.isSatisfiedBy(inventory, quantity)) {
            throw new InsufficientInventoryException(
                    "No hay existencias disponibles para reservar " + quantity);
        }
        inventory.reserve(quantity, metadata);
        inventories.save(inventory);
        audit.record(OperationType.INVENTORY_RESERVE, AuditSeverity.INFO,
                inventory.getProductId().toString(), "Reserva: " + quantity);
    }

    /**
     * Reserva existencias para un producto (usado por {@code OrderService}
     * sobre los ítems físicos del pedido). Elige la primera bodega del
     * vendedor que cubra la cantidad pedida.
     */
    public void reserveForProduct(ProductId productId, Quantity quantity, String reference) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        Objects.requireNonNull(quantity, "quantity es obligatorio");
        List<Inventory> entries = inventories.findByProductId(productId);
        Inventory target = null;
        for (Inventory inv : entries) {
            if (inv.getStatus() != InventoryStatus.DAMAGED
                    && quantity.isLessThanOrEqual(inv.getAvailableQuantity())) {
                target = inv;
                break;
            }
        }
        if (target == null) {
            throw new InsufficientInventoryException(
                    "No hay inventario disponible para el producto " + productId
                            + " (pedido " + reference + ").");
        }
        reserve(target.getInventoryId(), quantity, Map.of("reference", reference));
    }

    /** Libera una reserva (pedido cancelado o reserva anulada). */
    public void release(InventoryId inventoryId, Quantity quantity, Map<String, Object> metadata) {
        Inventory inventory = loadInventory(inventoryId);
        inventory.release(quantity, metadata);
        inventories.save(inventory);
        audit.record(OperationType.INVENTORY_RELEASE, AuditSeverity.INFO,
                inventory.getProductId().toString(), "Liberación: " + quantity);
    }

    /** Libera la reserva de un producto (usado al cancelar pedidos). */
    public void releaseForProduct(ProductId productId, Quantity quantity, String reference) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        List<Inventory> entries = inventories.findByProductId(productId);
        for (Inventory inv : entries) {
            if (quantity.isLessThanOrEqual(inv.getReservedQuantity())) {
                release(inv.getInventoryId(), quantity, Map.of("reference", reference));
                return;
            }
        }
        // No hay reserva que liberar: el inventario ya fue liberado o nunca se reservó.
    }

    /** Salida definitiva por venta confirmada. */
    public void confirmSale(InventoryId inventoryId, Quantity quantity, Map<String, Object> metadata) {
        Inventory inventory = loadInventory(inventoryId);
        if (!available.isSatisfiedBy(inventory, quantity)) {
            throw new InsufficientInventoryException(
                    "No hay existencias disponibles para vender " + quantity);
        }
        inventory.confirmSale(quantity, metadata);
        inventories.save(inventory);
        audit.record(OperationType.INVENTORY_CONFIRM_SALE, AuditSeverity.INFO,
                inventory.getProductId().toString(), "Venta confirmada: " + quantity);
    }

    /** Confirma la venta de lo previamente reservado para un producto. */
    public void confirmSaleForProduct(ProductId productId, Quantity quantity, String reference) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        List<Inventory> entries = inventories.findByProductId(productId);
        for (Inventory inv : entries) {
            if (quantity.isLessThanOrEqual(inv.getReservedQuantity())) {
                confirmSale(inv.getInventoryId(), quantity, Map.of("reference", reference));
                return;
            }
        }
        throw new InsufficientInventoryException(
                "No hay reservas suficientes para confirmar la venta de " + quantity
                        + " del producto " + productId);
    }

    /** Ajuste físico: la nueva existencia no puede ser menor que la reservada. */
    public void adjust(InventoryId inventoryId, Quantity newQuantity, Map<String, Object> metadata) {
        Inventory inventory = loadInventory(inventoryId);
        inventory.adjust(newQuantity, metadata);
        inventories.save(inventory);
        audit.record(OperationType.INVENTORY_ADJUST, AuditSeverity.WARNING,
                inventory.getProductId().toString(), "Ajuste físico a " + newQuantity);
    }

    /** Marca el inventario como dañado (no comercializable). */
    public void markDamaged(InventoryId inventoryId) {
        Inventory inventory = loadInventory(inventoryId);
        inventory.markDamaged();
        inventories.save(inventory);
    }

    /** Rehabilita el inventario dañado. */
    public void markActive(InventoryId inventoryId) {
        Inventory inventory = loadInventory(inventoryId);
        inventory.markActive();
        inventories.save(inventory);
    }

    /** Recepción de mercancía por devolución. */
    public void receiveReturn(InventoryId inventoryId, Quantity quantity, Map<String, Object> metadata) {
        Inventory inventory = loadInventory(inventoryId);
        inventory.receiveReturn(quantity, metadata);
        inventories.save(inventory);
        audit.record(OperationType.INVENTORY_RETURN, AuditSeverity.INFO,
                inventory.getProductId().toString(), "Devolución recibida: " + quantity);
    }

    /** Recepción de mercancía por devolución, localizando el inventario por producto. */
    public void receiveReturnForProduct(ProductId productId, Quantity quantity, String reference) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        List<Inventory> entries = inventories.findByProductId(productId);
        if (entries.isEmpty()) {
            throw new BusinessException("INVENTORY_NOT_FOUND",
                    "No existe inventario para el producto " + productId);
        }
        receiveReturn(entries.get(0).getInventoryId(), quantity, Map.of("reference", reference));
    }

    public Inventory findById(InventoryId inventoryId) {
        return loadInventory(inventoryId);
    }

    private Inventory loadInventory(InventoryId inventoryId) {
        Objects.requireNonNull(inventoryId, "inventoryId es obligatorio");
        return inventories.findById(inventoryId)
                .orElseThrow(() -> new BusinessException("INVENTORY_NOT_FOUND", "Inventario inexistente."));
    }
}