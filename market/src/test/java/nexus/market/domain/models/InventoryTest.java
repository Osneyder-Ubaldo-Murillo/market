package nexus.market.domain.models;

import java.util.Map;

import org.junit.jupiter.api.Test;

import nexus.market.domain.enums.InventoryMovementType;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.exceptions.InsufficientInventoryException;
import nexus.market.domain.valueobjects.InventoryStatus;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.WarehouseId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba el agregado {@code Inventory}: flujo completo de existencias,
 * reservas, ventas, ajustes, devoluciones y auditoría de movimientos.
 */
class InventoryTest {

    private static Inventory newInventory() {
        return Inventory.create(ProductId.generate(), WarehouseId.generate());
    }

    private static Map<String, Object> metadata(String ref) {
        return Map.of("reference", ref);
    }

    @Test
    void inventarioNuevoEstaAgotadoYConTotalEnCero() {
        Inventory inventory = newInventory();
        assertEquals(InventoryStatus.OUT_OF_STOCK, inventory.getStatus());
        assertTrue(inventory.getAvailableQuantity().isZero());
    }

    @Test
    void ingresoDeMercanciaActivaElInventario() {
        Inventory inventory = newInventory();
        inventory.addStock(Quantity.of(10), metadata("entrada-1"));
        assertEquals(InventoryStatus.ACTIVE, inventory.getStatus());
        assertEquals(10, inventory.getQuantity().value());
        assertEquals(10, inventory.getAvailableQuantity().value());
        assertEquals(1, inventory.getMovements().size());
        assertEquals(InventoryMovementType.INCOME, inventory.getMovements().get(0).getType());
    }

    @Test
    void reservarYConfirmarVentaDescuentaExistencias() {
        Inventory inventory = newInventory();
        inventory.addStock(Quantity.of(10), metadata("entrada-1"));

        inventory.reserve(Quantity.of(4), metadata("pedido-1"));
        assertEquals(4, inventory.getReservedQuantity().value());
        assertEquals(6, inventory.getAvailableQuantity().value());

        inventory.confirmSale(Quantity.of(4), metadata("venta-1"));
        assertEquals(6, inventory.getQuantity().value());
        assertEquals(0, inventory.getReservedQuantity().value());
        assertEquals(6, inventory.getAvailableQuantity().value());
    }

    @Test
    void noSePuedeReservarMasDeLoDisponible() {
        Inventory inventory = newInventory();
        inventory.addStock(Quantity.of(5), metadata("entrada-1"));
        assertThrows(InsufficientInventoryException.class,
                () -> inventory.reserve(Quantity.of(6), metadata("pedido-1")));
    }

    @Test
    void liberarReservaDevuelveDisponibilidad() {
        Inventory inventory = newInventory();
        inventory.addStock(Quantity.of(5), metadata("entrada-1"));
        inventory.reserve(Quantity.of(3), metadata("pedido-1"));
        inventory.release(Quantity.of(3), metadata("cancelacion-1"));
        assertEquals(0, inventory.getReservedQuantity().value());
        assertEquals(5, inventory.getAvailableQuantity().value());
    }

    @Test
    void ventaQueAgotaReiniciaElEstadoOutOfStock() {
        Inventory inventory = newInventory();
        inventory.addStock(Quantity.of(2), metadata("entrada-1"));
        inventory.reserve(Quantity.of(2), metadata("pedido-1"));
        inventory.confirmSale(Quantity.of(2), metadata("venta-1"));
        assertEquals(InventoryStatus.OUT_OF_STOCK, inventory.getStatus());
        assertTrue(inventory.getQuantity().isZero());
    }

    @Test
    void inventarioDanadoNoAceptaReservas() {
        Inventory inventory = newInventory();
        inventory.addStock(Quantity.of(10), metadata("entrada-1"));
        inventory.markDamaged();
        assertEquals(InventoryStatus.DAMAGED, inventory.getStatus());
        assertThrows(BusinessException.class,
                () -> inventory.reserve(Quantity.of(1), metadata("pedido-1")));
    }

    @Test
    void ajusteFisicoRegistraDelta() {
        Inventory inventory = newInventory();
        inventory.addStock(Quantity.of(10), metadata("entrada-1"));
        inventory.adjust(Quantity.of(8), metadata("merma"));
        assertEquals(8, inventory.getQuantity().value());
        assertEquals(-2, inventory.getMovements().get(1).getMetadata().get("delta"));
        assertEquals(InventoryMovementType.ADJUSTMENT, inventory.getMovements().get(1).getType());
    }

    @Test
    void ajusteNoPuedeDejarExistenciaMenorQueReservada() {
        Inventory inventory = newInventory();
        inventory.addStock(Quantity.of(10), metadata("entrada-1"));
        inventory.reserve(Quantity.of(4), metadata("pedido-1"));
        assertThrows(BusinessException.class,
                () -> inventory.adjust(Quantity.of(3), metadata("ajuste")));
    }

    @Test
    void recepcionPorDevolucionReponeExistencias() {
        Inventory inventory = newInventory();
        inventory.addStock(Quantity.of(5), metadata("entrada-1"));
        inventory.receiveReturn(Quantity.of(2), metadata("devolucion-1"));
        assertEquals(7, inventory.getQuantity().value());
        assertEquals(InventoryMovementType.RETURN, inventory.getMovements().get(1).getType());
    }

    @Test
    void ingresarCeroLanzaExcepcion() {
        Inventory inventory = newInventory();
        assertThrows(IllegalArgumentException.class,
                () -> inventory.addStock(Quantity.ZERO, metadata("entrada-0")));
    }
}