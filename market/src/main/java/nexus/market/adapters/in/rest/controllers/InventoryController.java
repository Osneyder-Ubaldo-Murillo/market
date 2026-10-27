package nexus.market.adapters.in.rest.controllers;

import nexus.market.adapters.in.rest.security.AccessPolicy;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.requests.CommonRequests.QuantityRequest;
import nexus.market.adapters.in.rest.requests.InventoryRequests.CreateInventoryRequest;
import nexus.market.adapters.in.rest.responses.CatalogResponses.InventoryResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.MessageResponse;
import nexus.market.domain.ports.in.InventoryUseCases;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Controlador REST de inventario: creación del par (producto, bodega),
 * ingresos, reservas, liberaciones, ajustes y estados.
 */
@RestController
@RequestMapping("/api/inventory")
@Profile("jpa")
public class InventoryController {

    private final InventoryUseCases inventoryUseCases;
    private final AccessPolicy policy;

    public InventoryController(InventoryUseCases inventoryUseCases, AccessPolicy policy) {
        this.inventoryUseCases = inventoryUseCases;
        this.policy = policy;
    }

    @PostMapping
    public ResponseEntity<InventoryResponse> create(@Valid @RequestBody CreateInventoryRequest request) {
        policy.requireOwnProductAndWarehouse(request.productId(), request.warehouseId());
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.inventory(
                inventoryUseCases.createInventory(
                        ProductId.of(request.productId()),
                        WarehouseId.of(request.warehouseId()))));
    }

    @GetMapping("/{inventoryId}")
    public ResponseEntity<InventoryResponse> findById(@PathVariable String inventoryId) {
        policy.requireInventoryReadable(inventoryId);
        return ResponseEntity.ok(
                RestMappers.inventory(inventoryUseCases.findById(InventoryId.of(inventoryId))));
    }

    @PostMapping("/{inventoryId}/stock")
    public ResponseEntity<MessageResponse> addStock(@PathVariable String inventoryId,
                                                    @Valid @RequestBody QuantityRequest request) {
        policy.requireOwnInventory(inventoryId);
        inventoryUseCases.addStock(InventoryId.of(inventoryId), request.toDomain());
        return ResponseEntity.ok(new MessageResponse("Ingreso registrado."));
    }

    @PostMapping("/{inventoryId}/reserve")
    public ResponseEntity<MessageResponse> reserve(@PathVariable String inventoryId,
                                                   @Valid @RequestBody QuantityRequest request) {
        inventoryUseCases.reserve(InventoryId.of(inventoryId), request.toDomain());
        return ResponseEntity.ok(new MessageResponse("Inventario reservado."));
    }

    @PostMapping("/{inventoryId}/release")
    public ResponseEntity<MessageResponse> release(@PathVariable String inventoryId,
                                                   @Valid @RequestBody QuantityRequest request) {
        inventoryUseCases.release(InventoryId.of(inventoryId), request.toDomain());
        return ResponseEntity.ok(new MessageResponse("Reserva liberada."));
    }

    /** Ajuste físico de existencias (nueva existencia total). */
    @PutMapping("/{inventoryId}/quantity")
    public ResponseEntity<MessageResponse> adjust(@PathVariable String inventoryId,
                                                  @Valid @RequestBody QuantityRequest request) {
        policy.requireOwnInventory(inventoryId);
        inventoryUseCases.adjust(InventoryId.of(inventoryId), request.toDomain());
        return ResponseEntity.ok(new MessageResponse("Existencias ajustadas."));
    }

    @PostMapping("/{inventoryId}/damaged")
    public ResponseEntity<MessageResponse> markDamaged(@PathVariable String inventoryId) {
        policy.requireOwnInventory(inventoryId);
        inventoryUseCases.markDamaged(InventoryId.of(inventoryId));
        return ResponseEntity.ok(new MessageResponse("Inventario marcado como dañado."));
    }

    @PostMapping("/{inventoryId}/active")
    public ResponseEntity<MessageResponse> markActive(@PathVariable String inventoryId) {
        policy.requireOwnInventory(inventoryId);
        inventoryUseCases.markActive(InventoryId.of(inventoryId));
        return ResponseEntity.ok(new MessageResponse("Inventario reactivado."));
    }
}
