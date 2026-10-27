package nexus.market.adapters.in.rest.controllers;

import nexus.market.adapters.in.rest.security.AccessPolicy;
import java.util.List;

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
import nexus.market.adapters.in.rest.requests.ProductRequests.CreateProductRequest;
import nexus.market.adapters.in.rest.requests.ProductRequests.UpdatePriceRequest;
import nexus.market.adapters.in.rest.responses.CatalogResponses.ProductResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.MessageResponse;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.ports.in.ProductCatalogUseCases;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.SellerId;

/**
 * Controlador REST del catálogo de productos: creación, publicación,
 * despublicación, actualización de precio y consulta (individual y del
 * catálogo publicado).
 */
@RestController
@RequestMapping("/api/products")
@Profile("jpa")
public class ProductController {

    private final ProductCatalogUseCases productUseCases;
    private final AccessPolicy policy;

    public ProductController(ProductCatalogUseCases productUseCases, AccessPolicy policy) {
        this.productUseCases = productUseCases;
        this.policy = policy;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        policy.requireOwnSeller(request.sellerId());
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.product(
                productUseCases.createProduct(
                        SellerId.of(request.sellerId()),
                        ProductName.of(request.name()),
                        ProductDescription.of(request.description()),
                        productType(request.type()),
                        request.price().toDomain())));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> findById(@PathVariable String productId) {
        nexus.market.domain.models.Product product = productUseCases.findById(ProductId.of(productId));
        // Un producto no publicado no se revela al público: responde igual que si no existiera.
        if (product.getStatus() != nexus.market.domain.valueobjects.ProductStatus.ACTIVE
                && !policy.canSeeUnpublished(product)) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente.");
        }
        return ResponseEntity.ok(RestMappers.product(product));
    }

    /** Catálogo visible: solo productos publicados. */
    @GetMapping
    public ResponseEntity<List<ProductResponse>> findPublished() {
        return ResponseEntity.ok(productUseCases.findPublished().stream()
                .map(RestMappers::product)
                .toList());
    }

    @PostMapping("/{productId}/publish")
    public ResponseEntity<MessageResponse> publish(@PathVariable String productId) {
        policy.requireOwnProduct(productId);
        productUseCases.publishProduct(ProductId.of(productId));
        return ResponseEntity.ok(new MessageResponse("Producto publicado."));
    }

    @PostMapping("/{productId}/unpublish")
    public ResponseEntity<MessageResponse> unpublish(@PathVariable String productId) {
        policy.requireOwnProduct(productId);
        productUseCases.unpublishProduct(ProductId.of(productId));
        return ResponseEntity.ok(new MessageResponse("Producto despublicado."));
    }

    @PutMapping("/{productId}/price")
    public ResponseEntity<MessageResponse> updatePrice(@PathVariable String productId,
                                                       @Valid @RequestBody UpdatePriceRequest request) {
        policy.requireOwnProduct(productId);
        productUseCases.updatePrice(ProductId.of(productId), request.price().toDomain());
        return ResponseEntity.ok(new MessageResponse("Precio actualizado."));
    }

    private static ProductType productType(String code) {
        if (code == null) {
            throw new BusinessException("INVALID_PRODUCT_TYPE",
                    "El tipo de producto es obligatorio (PHYSICAL o DIGITAL).");
        }
        return switch (code.trim().toUpperCase()) {
            case "PHYSICAL" -> ProductType.physical();
            case "DIGITAL" -> ProductType.digital();
            default -> throw new BusinessException("INVALID_PRODUCT_TYPE",
                    "Tipo de producto desconocido: " + code);
        };
    }
}
