package nexus.market.adapters.in.rest.controllers;

import nexus.market.application.AccessPolicy;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.requests.CommonRequests.AddressRequest;
import nexus.market.adapters.in.rest.responses.CatalogResponses.WarehouseResponse;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.ports.in.WarehouseUseCases;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Controlador REST de bodegas: creación de bodega del marketplace (central) y
 * bodegas privadas de vendedor, más consultas por id y por vendedor.
 */
@RestController
@RequestMapping("/api")
@Profile("jpa")
public class WarehouseController {

    private final WarehouseUseCases warehouseUseCases;
    private final AccessPolicy policy;

    public WarehouseController(WarehouseUseCases warehouseUseCases, AccessPolicy policy) {
        this.warehouseUseCases = warehouseUseCases;
        this.policy = policy;
    }

    /** Cuerpo: {name, address:{...}} — bodega central del marketplace. */
    @PostMapping("/warehouses/marketplace")
    public ResponseEntity<WarehouseResponse> createMarketplace(@Valid @RequestBody WarehouseCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.warehouse(
                warehouseUseCases.createMarketplaceWarehouse(request.name(), request.address().toDomain())));
    }

    @PostMapping("/sellers/{sellerId}/warehouses")
    public ResponseEntity<WarehouseResponse> createForSeller(@PathVariable String sellerId,
                                                             @Valid @RequestBody WarehouseCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.warehouse(
                warehouseUseCases.createSellerWarehouse(SellerId.of(sellerId),
                        request.name(), request.address().toDomain())));
    }

    @GetMapping("/warehouses/{warehouseId}")
    public ResponseEntity<WarehouseResponse> findById(@PathVariable String warehouseId) {
        policy.requireWarehouseReadable(warehouseId);
        return ResponseEntity.ok(RestMappers.warehouse(warehouseUseCases
                .findById(WarehouseId.of(warehouseId))
                .orElseThrow(() -> new BusinessException("WAREHOUSE_NOT_FOUND", "Bodega inexistente."))));
    }

    @GetMapping("/sellers/{sellerId}/warehouses")
    public ResponseEntity<List<WarehouseResponse>> findBySeller(@PathVariable String sellerId) {
        policy.requireSellerWarehousesReadable(sellerId);
        return ResponseEntity.ok(warehouseUseCases.findBySeller(SellerId.of(sellerId)).stream()
                .map(RestMappers::warehouse)
                .toList());
    }

    /** Cuerpo de creación de bodega (nombre + dirección). */
    public record WarehouseCreateRequest(
            @jakarta.validation.constraints.NotBlank(message = "name es obligatorio") @jakarta.validation.constraints.Size(max = 120, message = "name no puede exceder 120 caracteres") String name,
            @jakarta.validation.constraints.NotNull(message = "address es obligatoria") @Valid AddressRequest address) {
    }
}
