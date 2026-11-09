package nexus.market.adapters.in.rest.controllers;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import nexus.market.application.AccessPolicy;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nexus.market.adapters.in.rest.PageParams;
import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.application.IdentityDirectory;
import nexus.market.adapters.in.rest.requests.ProductRequests.AddVariantRequest;
import nexus.market.adapters.in.rest.requests.ProductRequests.CreateProductRequest;
import nexus.market.adapters.in.rest.requests.ProductRequests.UpdatePriceRequest;
import nexus.market.adapters.in.rest.requests.ProductRequests.UpdateProductRequest;
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
import nexus.market.domain.valueobjects.SellerSku;

/**
 * Controlador REST del catálogo de productos: creación, publicación,
 * despublicación, actualización de precio y consulta (individual y del
 * catálogo publicado).
 */
@Tag(name = "Catálogo y productos", description = "Catálogo público y gestión de productos por el vendedor.")
@RestController
@RequestMapping("/api/products")
@Profile("jpa")
public class ProductController {

    private final ProductCatalogUseCases productUseCases;
    private final AccessPolicy policy;
    private final IdentityDirectory directory;

    public ProductController(ProductCatalogUseCases productUseCases, AccessPolicy policy, IdentityDirectory directory) {
        this.productUseCases = productUseCases;
        this.policy = policy;
        this.directory = directory;
    }

    @Operation(summary = "Crear un producto", description = "Solo VENDEDOR. Queda sin publicar. sellerSku es el código comercial "
            + "legible (p. ej. TEC-001), único en el catálogo del vendedor; si se omite se genera uno (SKU-XXXXXXXX). "
            + "El productId (UUID) sigue siendo la referencia técnica.")
    @ApiResponse(responseCode = "201", description = "Producto creado.")
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        policy.requireOwnSeller(request.sellerId());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(
                productUseCases.createProduct(
                        SellerId.of(request.sellerId()),
                        ProductName.of(request.name()),
                        ProductDescription.of(request.description()),
                        productType(request.type()),
                        request.price().toDomain(),
                        sku(request.sellerSku()))));
    }

    @Operation(summary = "Consultar un producto publicado", description = "Pública. Solo devuelve productos publicados.")
    @SecurityRequirements
    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> findById(@PathVariable String productId) {
        nexus.market.domain.models.Product product = productUseCases.findById(ProductId.of(productId));
        // Un producto no publicado no se revela al público: responde igual que si no existiera.
        if (product.getStatus() != nexus.market.domain.valueobjects.ProductStatus.ACTIVE
                && !policy.canSeeUnpublished(product)) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente.");
        }
        return ResponseEntity.ok(view(product));
    }

    /**
     * Catálogo visible: solo productos publicados, paginado (por defecto 20, máximo {@value PageParams#MAX_SIZE} por
     * página) y ordenado por nombre; {@code q} filtra por texto contenido en el nombre. Un catálogo público sin límite permitiría descargar toda la tabla en una petición.
     * El total está en la cabecera {@code X-Total-Count}.
     */
    @Operation(summary = "Listar el catálogo público", description = "Pública. Solo productos publicados, con su código comercial (sellerSku) "
            + "y el nombre del vendedor. Paginado: page y size (máximo 100), búsqueda por nombre con q; el total va en X-Total-Count. "
            + "Para localizar un producto por su código comercial indique sellerSku junto con sellerId (el mismo código puede existir "
            + "en varios vendedores, por eso sellerSku solo es ambiguo y se rechaza).")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<List<ProductResponse>> findPublished(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String sellerId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String sellerSku) {
        PageParams params = PageParams.of(page, size);
        if (sellerSku != null && !sellerSku.isBlank()) {
            if (sellerId == null || sellerId.isBlank()) {
                throw new BusinessException("SELLER_REQUIRED",
                        "El código comercial (sellerSku) se repite entre vendedores: indique también sellerId.");
            }
            List<ProductResponse> found = findOnePublishedBySku(sellerId, sellerSku);
            return ResponseEntity.ok().header(PageParams.TOTAL_COUNT_HEADER, String.valueOf(found.size())).body(found);
        }
        String filter = PageParams.searchText(q);
        return ResponseEntity.ok()
                .header(PageParams.TOTAL_COUNT_HEADER, String.valueOf(productUseCases.countPublished(filter)))
                .body(views(productUseCases.findPublished(filter, params.offset(), params.size())));
    }

    /** El vendedor dueño edita el nombre y la descripción de su producto. */
    @Operation(summary = "Editar el nombre y la descripción", description = "Solo VENDEDOR dueño.")
    @PutMapping("/{productId}")
    public ResponseEntity<ProductResponse> update(@PathVariable String productId,
                                                  @Valid @RequestBody UpdateProductRequest request) {
        policy.requireOwnProduct(productId);
        productUseCases.updateDetails(ProductId.of(productId), ProductName.of(request.name()),
                ProductDescription.of(request.description()), sku(request.sellerSku()));
        return ResponseEntity.ok(view(productUseCases.findById(ProductId.of(productId))));
    }

    @Operation(summary = "Publicar un producto", description = "Solo VENDEDOR dueño. Cuerpo vacío ({}).")
    @PostMapping("/{productId}/publish")
    public ResponseEntity<MessageResponse> publish(@PathVariable String productId) {
        policy.requireOwnProduct(productId);
        productUseCases.publishProduct(ProductId.of(productId));
        return ResponseEntity.ok(new MessageResponse("Producto publicado."));
    }

    @Operation(summary = "Despublicar un producto", description = "Solo VENDEDOR dueño. Cuerpo vacío ({}).")
    @PostMapping("/{productId}/unpublish")
    public ResponseEntity<MessageResponse> unpublish(@PathVariable String productId) {
        policy.requireOwnProduct(productId);
        productUseCases.unpublishProduct(ProductId.of(productId));
        return ResponseEntity.ok(new MessageResponse("Producto despublicado."));
    }

    @Operation(summary = "Suspender un producto", description = "Solo VENDEDOR dueño. Retiro temporal del catálogo (solo desde ACTIVE); "
            + "publicarlo de nuevo lo reactiva. Cuerpo vacío ({}).")
    @PostMapping("/{productId}/suspend")
    public ResponseEntity<MessageResponse> suspend(@PathVariable String productId) {
        policy.requireOwnProduct(productId);
        productUseCases.suspendProduct(ProductId.of(productId));
        return ResponseEntity.ok(new MessageResponse("Producto suspendido."));
    }

    @Operation(summary = "Descontinuar un producto", description = "Solo VENDEDOR dueño. Retiro definitivo: no vuelve a publicarse "
            + "ni a editarse. Cuerpo vacío ({}).")
    @PostMapping("/{productId}/discontinue")
    public ResponseEntity<MessageResponse> discontinue(@PathVariable String productId) {
        policy.requireOwnProduct(productId);
        productUseCases.discontinueProduct(ProductId.of(productId));
        return ResponseEntity.ok(new MessageResponse("Producto descontinuado."));
    }

    @Operation(summary = "Agregar una variante", description = "Solo VENDEDOR dueño. Color, talla, modelo...: hasta 20 por producto, "
            + "sin repetir. Las variantes se ven en specifications.variants del producto.")
    @PostMapping("/{productId}/variants")
    public ResponseEntity<ProductResponse> addVariant(@PathVariable String productId,
                                                      @Valid @RequestBody AddVariantRequest request) {
        policy.requireOwnProduct(productId);
        return ResponseEntity.ok(view(
                productUseCases.addVariant(ProductId.of(productId), request.variant())));
    }

    @Operation(summary = "Quitar una variante", description = "Solo VENDEDOR dueño.")
    @DeleteMapping("/{productId}/variants/{variant}")
    public ResponseEntity<ProductResponse> removeVariant(@PathVariable String productId, @PathVariable String variant) {
        policy.requireOwnProduct(productId);
        return ResponseEntity.ok(view(
                productUseCases.removeVariant(ProductId.of(productId), variant)));
    }

    @Operation(summary = "Cambiar el precio", description = "Solo VENDEDOR dueño.")
    @PutMapping("/{productId}/price")
    public ResponseEntity<MessageResponse> updatePrice(@PathVariable String productId,
                                                       @Valid @RequestBody UpdatePriceRequest request) {
        policy.requireOwnProduct(productId);
        productUseCases.updatePrice(ProductId.of(productId), request.price().toDomain());
        return ResponseEntity.ok(new MessageResponse("Precio actualizado."));
    }

    private static SellerSku sku(String raw) {
        return raw == null || raw.isBlank() ? null : SellerSku.of(raw);
    }

    private ProductResponse view(nexus.market.domain.models.Product product) {
        return RestMappers.product(product,
                directory.sellerNames(List.of(product.getSellerId())).get(product.getSellerId().value()));
    }

    private List<ProductResponse> views(List<nexus.market.domain.models.Product> products) {
        java.util.Map<String, String> names = directory.sellerNames(
                products.stream().map(nexus.market.domain.models.Product::getSellerId).distinct().toList());
        return products.stream().map(p -> RestMappers.product(p, names.get(p.getSellerId().value()))).toList();
    }

    /** El producto publicado de ese vendedor con ese código; lista vacía si no existe o no está publicado. */
    private List<ProductResponse> findOnePublishedBySku(String sellerId, String sellerSku) {
        try {
            nexus.market.domain.models.Product product = productUseCases.findBySellerAndSku(
                    SellerId.of(sellerId), SellerSku.of(sellerSku));
            return product.getStatus() == nexus.market.domain.valueobjects.ProductStatus.ACTIVE
                    ? List.of(view(product)) : List.of();
        } catch (BusinessException notFound) {
            if ("PRODUCT_NOT_FOUND".equals(notFound.getCode())) {
                return List.of();
            }
            throw notFound;
        }
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
