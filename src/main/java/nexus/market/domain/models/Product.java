package nexus.market.domain.models;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductStatus;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.SellerId;

/**
 * Agregado raíz que representa un producto vendido por un {@link Seller}.
 * Un producto {@code DIGITAL} se entrega inmediatamente después del pago y no
 * requiere inventario ni despacho; un producto {@code PHYSICAL} solo puede
 * publicarse si cumple {@code ProductPublishableSpecification}.
 */
public class Product {

    private final ProductId productId;
    private final SellerId sellerId;
    private final ProductName name;
    private final ProductDescription description;
    private final ProductType productType;
    private Money price;
    private ProductStatus status;
    private final Map<String, Object> specifications;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Product(ProductId productId, SellerId sellerId, ProductName name,
                   ProductDescription description, ProductType productType, Money price,
                   ProductStatus status, Map<String, Object> specifications,
                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.productId = Objects.requireNonNull(productId, "productId es obligatorio");
        this.sellerId = Objects.requireNonNull(sellerId, "sellerId es obligatorio");
        this.name = Objects.requireNonNull(name, "name es obligatorio");
        this.description = Objects.requireNonNull(description, "description es obligatorio");
        this.productType = Objects.requireNonNull(productType, "productType es obligatorio");
        this.price = Objects.requireNonNull(price, "price es obligatorio");
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        this.specifications = Collections.unmodifiableMap(
                specifications == null ? new HashMap<>() : new HashMap<>(specifications));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
    }

    /**
     * Crea un producto nuevo en estado {@code INACTIVE} (sin publicar).
     */
    public static Product create(SellerId sellerId, ProductName name, ProductDescription description,
                                 ProductType productType, Money price) {
        LocalDateTime now = LocalDateTime.now();
        return new Product(ProductId.generate(), sellerId, name, description,
                productType, price, ProductStatus.INACTIVE, Map.of(), now, now);
    }

    /**
     * Publica el producto. Para productos físicos la disponibilidad debe
     * validarse previamente con {@code ProductPublishableSpecification}.
     */
    public void publish() {
        if (status == ProductStatus.ACTIVE) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "El producto ya está publicado.");
        }
        this.status = ProductStatus.ACTIVE;
        touch();
    }

    /**
     * Oculta el producto del catálogo.
     */
    public void unpublish() {
        if (status != ProductStatus.ACTIVE) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo un producto ACTIVE puede despublicarse.");
        }
        this.status = ProductStatus.INACTIVE;
        touch();
    }

    /**
     * Marca el producto agotado (el inventario quedó en cero).
     */
    public void markOutOfStock() {
        this.status = ProductStatus.OUT_OF_STOCK;
        touch();
    }

    public void updatePrice(Money newPrice) {
        this.price = Objects.requireNonNull(newPrice, "newPrice es obligatorio");
        touch();
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public ProductId getProductId() {
        return productId;
    }

    public SellerId getSellerId() {
        return sellerId;
    }

    public ProductName getName() {
        return name;
    }

    public ProductDescription getDescription() {
        return description;
    }

    public ProductType getProductType() {
        return productType;
    }

    public Money getPrice() {
        return price;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public Map<String, Object> getSpecifications() {
        return specifications;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}