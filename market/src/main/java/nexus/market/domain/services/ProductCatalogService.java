package nexus.market.domain.services;

import java.util.List;
import java.util.Objects;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Product;
import nexus.market.domain.ports.out.InventoryRepositoryPort;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.specifications.ProductPublishableSpecification;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductStatus;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SellerSku;

/**
 * Servicio de dominio del catálogo de productos (SDD - Domain Services:
 * {@code ProductCatalogService}): creación, publicación, despublicación y
 * cambio de precio.
 */
public class ProductCatalogService {

    private final ProductRepositoryPort products;
    private final InventoryRepositoryPort inventories;
    private final ProductPublishableSpecification publishable;
    private final AuditService audit;

    public ProductCatalogService(ProductRepositoryPort products,
                                 InventoryRepositoryPort inventories,
                                 ProductPublishableSpecification publishable,
                                 AuditService audit) {
        this.products = Objects.requireNonNull(products, "products es obligatorio");
        this.inventories = Objects.requireNonNull(inventories, "inventories es obligatorio");
        this.publishable = Objects.requireNonNull(publishable, "publishable es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    /** Crea el producto en estado {@code INACTIVE} (sin publicar). */
    public Product createProduct(SellerId sellerId, ProductName name,
                                 ProductDescription description, ProductType type, Money price, SellerSku sellerSku) {
        SellerSku sku = sellerSku == null ? generateFreeSku(sellerId) : sellerSku;
        if (sellerSku != null) {
            requireSkuFree(sellerId, sku, null);
        }
        Product product = products.save(
                Product.create(sellerId, name, description, type, price, sku));
        audit.record(OperationType.PRODUCT_CREATION, AuditSeverity.INFO,
                sellerId.toString(), "Producto creado: " + product.getProductId());
        return product;
    }

    /**
     * Publica el producto. Los productos físicos deben cumplir
     * {@code ProductPublishableSpecification} (inventario activo con stock);
     * los digitales siempre son publicables.
     */
    public void publishProduct(ProductId productId) {
        Product product = loadProduct(productId);
        if (!publishable.isSatisfiedBy(product)) {
            throw new BusinessException("NOT_PUBLISHABLE",
                    "Producto físico sin inventario disponible; no puede publicarse.");
        }
        product.publish();
        products.save(product);
        audit.record(OperationType.PRODUCT_PUBLISH, AuditSeverity.INFO,
                product.getSellerId().toString(), "Producto publicado: " + productId);
    }

    /** Oculta el producto ({@code ACTIVE → INACTIVE}). */
    public void unpublishProduct(ProductId productId) {
        Product product = loadProduct(productId);
        product.unpublish();
        products.save(product);
        audit.record(OperationType.PRODUCT_UNPUBLISH, AuditSeverity.INFO,
                product.getSellerId().toString(), "Producto despublicado: " + productId);
    }

    /** Retira temporalmente el producto ({@code ACTIVE → SUSPENDED}); publicarlo de nuevo lo reactiva. */
    public void suspendProduct(ProductId productId) {
        Product product = loadProduct(productId);
        product.suspend();
        products.save(product);
        audit.record(OperationType.PRODUCT_SUSPEND, AuditSeverity.INFO,
                product.getSellerId().toString(), "Producto suspendido: " + productId);
    }

    /** Retira definitivamente el producto (estado final {@code DISCONTINUED}). */
    public void discontinueProduct(ProductId productId) {
        Product product = loadProduct(productId);
        product.discontinue();
        products.save(product);
        audit.record(OperationType.PRODUCT_DISCONTINUE, AuditSeverity.INFO,
                product.getSellerId().toString(), "Producto descontinuado: " + productId);
    }

    public Product addVariant(ProductId productId, String variant) {
        Product product = loadProduct(productId);
        product.addVariant(variant);
        Product saved = products.save(product);
        audit.record(OperationType.PRODUCT_UPDATE, AuditSeverity.INFO,
                product.getSellerId().toString(), "Variante agregada al producto: " + productId);
        return saved;
    }

    public Product removeVariant(ProductId productId, String variant) {
        Product product = loadProduct(productId);
        product.removeVariant(variant);
        Product saved = products.save(product);
        audit.record(OperationType.PRODUCT_UPDATE, AuditSeverity.INFO,
                product.getSellerId().toString(), "Variante retirada del producto: " + productId);
        return saved;
    }

    /** El código comercial no debe repetirse dentro del catálogo del mismo vendedor (otros vendedores sí pueden usarlo). */
    private void requireSkuFree(SellerId sellerId, SellerSku sku, ProductId self) {
        products.findBySellerIdAndSellerSku(sellerId, sku)
                .filter(existing -> self == null || !existing.getProductId().equals(self))
                .ifPresent(existing -> {
                    throw new BusinessException("DUPLICATE_SELLER_SKU",
                            "Ya tiene un producto con el código " + sku + ". Cada código es único en su catálogo.");
                });
    }

    private SellerSku generateFreeSku(SellerId sellerId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            SellerSku candidate = SellerSku.generate();
            if (products.findBySellerIdAndSellerSku(sellerId, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BusinessException("SELLER_SKU_GENERATION_FAILED", "No se pudo generar un código de producto; reintente.");
    }

    /** El producto del vendedor con ese código comercial. */
    public Product findBySellerAndSku(SellerId sellerId, SellerSku sellerSku) {
        return products.findBySellerIdAndSellerSku(sellerId, sellerSku)
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente."));
    }

    /** Actualiza el nombre y la descripción del producto y, si se indica, su código comercial. */
    public void updateDetails(ProductId productId, ProductName name, ProductDescription description, SellerSku sellerSku) {
        Product product = loadProduct(productId);
        if (sellerSku != null && !sellerSku.equals(product.getSellerSku())) {
            requireSkuFree(product.getSellerId(), sellerSku, productId);
            product.changeSellerSku(sellerSku);
        }
        product.updateDetails(name, description);
        products.save(product);
        audit.record(OperationType.PRODUCT_UPDATE, AuditSeverity.INFO,
                product.getSellerId().toString(), "Datos del producto actualizados: " + productId);
    }

    /** Actualiza el precio del producto. */
    public void updatePrice(ProductId productId, Money newPrice) {
        Product product = loadProduct(productId);
        product.updatePrice(newPrice);
        products.save(product);
        audit.record(OperationType.PRODUCT_UPDATE, AuditSeverity.INFO,
                product.getSellerId().toString(), "Precio actualizado: " + productId);
    }

    /** Consulta el catálogo visible (publicado y con stock). */
    public List<Product> findPublished() {
        return products.findPublished();
    }

    /** Una página del catálogo visible. */
    public List<Product> findPublished(String nameContains, int offset, int limit) {
        return products.findPublished(nameContains, offset, limit);
    }

    public long countPublished(String nameContains) {
        return products.countPublished(nameContains);
    }

    /** Una página de los productos del vendedor en cualquier estado; {@code statusCode} opcional filtra por estado. */
    public List<Product> findBySeller(SellerId sellerId, String statusCode, int offset, int limit) {
        Objects.requireNonNull(sellerId, "sellerId es obligatorio");
        return products.findBySellerId(sellerId, statusCode, offset, limit);
    }

    public long countBySeller(SellerId sellerId, String statusCode) {
        Objects.requireNonNull(sellerId, "sellerId es obligatorio");
        return products.countBySellerId(sellerId, statusCode);
    }

    public Product findById(ProductId productId) {
        return loadProduct(productId);
    }

    public boolean isPublished(ProductId productId) {
        return loadProduct(productId).getStatus() == ProductStatus.ACTIVE;
    }

    private Product loadProduct(ProductId productId) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        return products.findById(productId)
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente."));
    }
}