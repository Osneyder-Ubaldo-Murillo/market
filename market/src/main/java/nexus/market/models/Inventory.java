package nexus.market.domain.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import nexus.market.domain.enums.InventoryMovementType;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.exceptions.InsufficientInventoryException;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.InventoryMovement;
import nexus.market.domain.valueobjects.InventoryStatus;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Agregado raíz que representa las existencias de un {@link Product} en una
 * {@link Warehouse}. Garantiza el invariante {@code reservedQuantity <=
 * quantity} y registra todo cambio en {@code movements} (auditoría).
 */
public class Inventory {

    private final InventoryId inventoryId;
    private final ProductId productId;
    private final WarehouseId warehouseId;
    private Quantity quantity;
    private Quantity reservedQuantity;
    private InventoryStatus status;
    private final List<InventoryMovement> movements;
    private LocalDateTime updatedAt;

    public Inventory(InventoryId inventoryId, ProductId productId, WarehouseId warehouseId,
                     Quantity quantity, Quantity reservedQuantity, InventoryStatus status,
                     List<InventoryMovement> movements, LocalDateTime updatedAt) {
        this.inventoryId = Objects.requireNonNull(inventoryId, "inventoryId es obligatorio");
        this.productId = Objects.requireNonNull(productId, "productId es obligatorio");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId es obligatorio");
        this.quantity = Objects.requireNonNull(quantity, "quantity es obligatorio");
        this.reservedQuantity = Objects.requireNonNull(reservedQuantity, "reservedQuantity es obligatorio");
        if (reservedQuantity.isGreaterThan(quantity)) {
            throw new BusinessException("INVALID_RESERVATION",
                    "La cantidad reservada no puede superar la existencia total.");
        }
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        this.movements = new ArrayList<>();
        if (movements != null) {
            this.movements.addAll(movements);
        }
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
    }

    /** Crea un inventario vacío en estado {@code OUT_OF_STOCK}. */
    public static Inventory create(ProductId productId, WarehouseId warehouseId) {
        return new Inventory(InventoryId.generate(), productId, warehouseId,
                Quantity.ZERO, Quantity.ZERO, InventoryStatus.OUT_OF_STOCK, List.of(), LocalDateTime.now());
    }

    /**
     * Existencia disponible para la venta ({@code quantity - reservedQuantity}),
     * nunca negativa por invariante.
     */
    public Quantity getAvailableQuantity() {
        return quantity.subtract(reservedQuantity);
    }

    /** Ingreso de mercancía; {@code OUT_OF_STOCK -> ACTIVE} si estaba en cero. */
    public void addStock(Quantity incoming, Map<String, Object> metadata) {
        requirePositive(incoming, "La cantidad a ingresar debe ser mayor que cero");
        this.quantity = quantity.add(incoming);
        if (status == InventoryStatus.OUT_OF_STOCK) {
            this.status = InventoryStatus.ACTIVE;
        }
        recordMovement(InventoryMovementType.INCOME, incoming, metadata);
    }

    /** Reserva existencias para un pedido en curso (no sobre DAMAGED ni más de lo disponible). */
    public void reserve1(Quantity toReserve, Map<String, Object> metadata) {
        requirePositive(toReserve, "La cantidad a reservar debe ser mayor que cero");
        if (status == InventoryStatus.DAMAGED) {
            throw new BusinessException("DAMAGED_INVENTORY",
                    "No se puede reservar inventario en estado DAMAGED.");
        }
        if (toReserve.isGreaterThan(getAvailableQuantity())) {
            throw new InsufficientInventoryException(
                    "No hay existencias suficientes para reservar " + toReserve
                            + "; disponible: " + getAvailableQuantity());
        }
        this.reservedQuantity = reservedQuantity.add(toReserve);
        recordMovement(InventoryMovementType.RESERVATION, toReserve, metadata);
    }

    /** Libera una reserva (pedido cancelado o reserva anulada). */
    public void release1(Quantity toRelease, Map<String, Object> metadata) {
        requirePositive(toRelease, "La cantidad a liberar debe ser mayor que cero");
        if (toRelease.isGreaterThan(reservedQuantity)) {
            throw new BusinessException("INVALID_RELEASE",
                    "No se puede liberar más de lo reservado: " + toRelease
                            + " > " + reservedQuantity);
        }
        this.reservedQuantity = reservedQuantity.subtract(toRelease);
        recordMovement(InventoryMovementType.RELEASE, toRelease, metadata);
    }

    /** Salida definitiva por venta: descuenta de total y reserva; agota si llega a cero. */
    public void confirmSale1(Quantity sold, Map<String, Object> metadata) {
        requirePositive(sold, "La cantidad vendida debe ser mayor que cero");
        if (status == InventoryStatus.DAMAGED) {
            throw new BusinessException("DAMAGED_INVENTORY",
                    "No se puede vender inventario en estado DAMAGED.");
        }
        if (sold.isGreaterThan(getAvailableQuantity())) {
            throw new InsufficientInventoryException(
                    "No hay existencias suficientes para vender " + sold
                            + "; disponible: " + getAvailableQuantity());
        }
        this.quantity = quantity.subtract(sold);
        this.reservedQuantity = reservedQuantity.subtract(sold);
        if (quantity.isZero()) {
            this.status = InventoryStatus.OUT_OF_STOCK;
        }
        recordMovement(InventoryMovementType.SALE, sold, metadata);
    }

    /** Recepción de mercancía por devolución. */
    public void receiveReturn(Quantity returned, Map<String, Object> metadata) {
        requirePositive(returned, "La cantidad devuelta debe ser mayor que cero");
        this.quantity = quantity.add(returned);
        if (status == InventoryStatus.OUT_OF_STOCK) {
            this.status = InventoryStatus.ACTIVE;
        }
        recordMovement(InventoryMovementType.RETURN, returned, metadata);
    }

    /**
     * Ajuste físico: fija la existencia total al valor indicado. La nueva
     * existencia no puede ser menor que la cantidad reservada.
     */
    public void adjust(Quantity newQuantity, Map<String, Object> metadata) {
        Objects.requireNonNull(newQuantity, "newQuantity es obligatorio");
        if (newQuantity.isLessThan(reservedQuantity)) {
            throw new BusinessException("INVALID_ADJUSTMENT",
                    "La nueva existencia no puede ser menor que la cantidad reservada ("
                            + reservedQuantity + ").");
        }
        Map<String, Object> enriched = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        int delta = newQuantity.value() - quantity.value();
        enriched.put("delta", delta);
        this.quantity = newQuantity;
        this.status = quantity.isZero() ? InventoryStatus.OUT_OF_STOCK : InventoryStatus.ACTIVE;
        recordMovement(InventoryMovementType.ADJUSTMENT, Quantity.of(Math.abs(delta)), enriched);
    }

    /** Marca el inventario como dañado (no comercializable). */
    public void markDamaged() {
        this.status = InventoryStatus.DAMAGED;
        this.updatedAt = LocalDateTime.now();
    }

    /** Rehabilita el inventario (ya no está dañado). */
    public void markActive() {
        this.status = InventoryStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
    }

    private void recordMovement(InventoryMovementType type, Quantity quantity,
                                Map<String, Object> metadata) {
        movements.add(InventoryMovement.of(type, quantity, LocalDateTime.now(), metadata));
        this.updatedAt = LocalDateTime.now();
    }

    private static void requirePositive(Quantity value, String message) {
        Objects.requireNonNull(value, "quantity es obligatorio");
        if (value.isZero()) {
            throw new IllegalArgumentException(message);
        }
    }

    public InventoryId getInventoryId() {
        return inventoryId;
    }

    public ProductId getProductId() {
        return productId;
    }

    public WarehouseId getWarehouseId() {
        return warehouseId;
    }

    public Quantity getQuantity() {
        return quantity;
    }

    public Quantity getReservedQuantity() {
        return reservedQuantity;
    }

    public InventoryStatus getStatus() {
        return status;
    }

    public List<InventoryMovement> getMovements() {
        return Collections.unmodifiableList(movements);
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}