package nexus.market.adapters.in.rest.controllers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nexus.market.adapters.in.rest.PageParams;
import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.responses.CatalogResponses.ProductResponse;
import nexus.market.application.AccessPolicy;
import nexus.market.application.IdentityDirectory;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Product;
import nexus.market.domain.ports.in.ProductCatalogUseCases;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SellerSku;

/**
 * Controlador REST de los productos de un vendedor en cualquier estado (publicado, suspendido, descontinuado...). El
 * catálogo público ({@code GET /api/products}) solo muestra los publicados.
 */
@Tag(name = "Catálogo y productos", description = "Catálogo público y gestión de productos del vendedor.")
@RestController
@RequestMapping("/api/sellers")
@Profile("jpa")
public class SellerProductController {

    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "OUT_OF_STOCK", "SUSPENDED", "DISCONTINUED");

    private final ProductCatalogUseCases productUseCases;
    private final AccessPolicy policy;
    private final IdentityDirectory directory;

    public SellerProductController(ProductCatalogUseCases productUseCases, AccessPolicy policy,
                                   IdentityDirectory directory) {
        this.productUseCases = productUseCases;
        this.policy = policy;
        this.directory = directory;
    }

    @Operation(summary = "Listar los productos de un vendedor",
            description = "VENDEDOR dueño, ADMIN y SUPERVISOR. Todos los estados (ACTIVE, INACTIVE, SUSPENDED, DISCONTINUED, "
                    + "OUT_OF_STOCK); filtre con status. Paginado: page y size (máximo 100); el total va en X-Total-Count.")
    @GetMapping("/{sellerId}/products")
    public ResponseEntity<List<ProductResponse>> listBySeller(
            @PathVariable String sellerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        policy.requireSellerProductsReadable(sellerId);
        PageParams params = PageParams.of(page, size);
        String statusCode = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (statusCode != null && !STATUSES.contains(statusCode)) {
            throw new BusinessException("INVALID_STATUS",
                    "status debe ser uno de: ACTIVE, INACTIVE, OUT_OF_STOCK, SUSPENDED, DISCONTINUED.");
        }
        SellerId id = SellerId.of(sellerId);
        List<Product> products = productUseCases.findBySeller(id, statusCode, params.offset(), params.size());
        return ResponseEntity.ok()
                .header(PageParams.TOTAL_COUNT_HEADER, String.valueOf(productUseCases.countBySeller(id, statusCode)))
                .body(views(products));
    }

    @Operation(summary = "Buscar un producto por su código comercial",
            description = "VENDEDOR dueño, ADMIN y SUPERVISOR. Localiza el producto del vendedor {sellerId} cuyo código comercial "
                    + "(sellerSku) es el indicado, en cualquier estado. El código es único dentro de cada vendedor, así que el "
                    + "resultado nunca es ambiguo; si no existe, 404.")
    @GetMapping("/{sellerId}/products/by-sku/{sellerSku}")
    public ResponseEntity<ProductResponse> findBySku(@PathVariable String sellerId, @PathVariable String sellerSku) {
        policy.requireSellerProductsReadable(sellerId);
        Product product = productUseCases.findBySellerAndSku(SellerId.of(sellerId), SellerSku.of(sellerSku));
        return ResponseEntity.ok(views(List.of(product)).get(0));
    }

    private List<ProductResponse> views(List<Product> products) {
        Map<String, String> names = directory.sellerNames(products.stream().map(Product::getSellerId).distinct().toList());
        return products.stream().map(p -> RestMappers.product(p, names.get(p.getSellerId().value()))).toList();
    }
}
