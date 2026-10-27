package nexus.market.adapters.in.rest.responses;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTOs de respuesta del catálogo, inventario y bodegas.
 */
public final class CatalogResponses {

    private CatalogResponses() {
    }

    /** Producto publicado o no (estado {@code ACTIVE}/{@code INACTIVE}). */
    public record ProductResponse(String productId, String sellerId, String name, String description,
                                  String type, UserResponses.MoneyResponse price, String status,
                                  Map<String, Object> specifications, LocalDateTime createdAt) {
    }

    /** Existencias de un producto en una bodega. */
    public record InventoryResponse(String inventoryId, String productId, String warehouseId,
                                    int quantity, int reservedQuantity, int availableQuantity,
                                    String status, LocalDateTime updatedAt) {
    }

    /** Bodega del marketplace o de un vendedor. */
    public record WarehouseResponse(String warehouseId, String name, UserResponses.AddressResponse address,
                                    String type, String sellerId, String status) {
    }
}
