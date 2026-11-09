package nexus.market.domain.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
import nexus.market.domain.valueobjects.SellerSku;

/**
 * Agregado raíz que representa un producto vendido por un {@link Seller}.
 * Un producto {@code DIGITAL} se entrega inmediatamente después del pago y no
 * requiere inventario ni despacho; un producto {@code PHYSICAL} solo puede
 * publicarse si cumple {@code ProductPublishableSpecification}.
 */
public class Product {

    /** Clave de las variantes dentro de las especificaciones. */
    public static final String VARIANTS_KEY = "variants";
    public static final int MAX_VARIANTS = 20;
    public static final int MAX_VARIANT_LENGTH = 50;

    private final ProductId productId;
    private final SellerId sellerId;
    private ProductName name;
    private ProductDescription description;
    private final ProductType productType;
    private Money price;
    private ProductStatus status;
    private Map<String, Object> specifications;
    private SellerSku sellerSku;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Product(ProductId productId, SellerId sellerId, ProductName name,
                   ProductDescription description, ProductType productType, Money price,
                   ProductStatus status, Map<String, Object> specifications,
                   LocalDateTime createdAt, LocalDateTime updatedAt, SellerSku sellerSku) {
        this.sellerSku = sellerSku; // null en los productos anteriores a V4 (sin código asignado)
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
        return create(sellerId, name, description, productType, price, null);
    }

    /** Igual que el anterior, con el código comercial que define el vendedor ({@code null} = sin código). */
    public static Product create(SellerId sellerId, ProductName name, ProductDescription description,
                                 ProductType productType, Money price, SellerSku sellerSku) {
        LocalDateTime now = LocalDateTime.now();
        return new Product(ProductId.generate(), sellerId, name, description,
                productType, price, ProductStatus.INACTIVE, Map.of(), now, now, sellerSku);
    }

    /**
     * Publica el producto. Para productos físicos la disponibilidad debe
     * validarse previamente con {@code ProductPublishableSpecification}.
     */
    public void publish() {
        if (status == ProductStatus.DISCONTINUED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Un producto descontinuado no puede volver a publicarse.");
        }
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

    /** Retira temporalmente el producto del catálogo ({@code ACTIVE → SUSPENDED}); {@link #publish()} lo reactiva. */
    public void suspend() {
        if (status != ProductStatus.ACTIVE) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo un producto publicado (ACTIVE) puede suspenderse.");
        }
        this.status = ProductStatus.SUSPENDED;
        touch();
    }

    /** Retira definitivamente el producto ({@code cualquier estado → DISCONTINUED}); no vuelve a publicarse. */
    public void discontinue() {
        if (status == ProductStatus.DISCONTINUED) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "El producto ya está descontinuado.");
        }
        this.status = ProductStatus.DISCONTINUED;
        touch();
    }

    /** Variantes del producto (color, talla, modelo...), en el orden en que se agregaron. */
    @SuppressWarnings("unchecked")
    public List<String> getVariants() {
        Object value = specifications.get(VARIANTS_KEY);
        return value instanceof List<?> list ? List.copyOf((List<String>) list) : List.of();
    }

    /** Agrega una variante: texto no vacío de hasta 50 caracteres, sin repetidas (sin distinguir mayúsculas), máximo 20. */
    public void addVariant(String variant) {
        requireNotDiscontinued();
        String clean = variant == null ? "" : variant.trim();
        if (clean.isEmpty() || clean.length() > MAX_VARIANT_LENGTH) {
            throw new BusinessException("INVALID_VARIANT",
                    "La variante es obligatoria y no puede exceder " + MAX_VARIANT_LENGTH + " caracteres.");
        }
        List<String> updated = new ArrayList<>(getVariants());
        if (updated.stream().anyMatch(existing -> existing.equalsIgnoreCase(clean))) {
            throw new BusinessException("DUPLICATE_VARIANT", "La variante ya existe: " + clean);
        }
        if (updated.size() >= MAX_VARIANTS) {
            throw new BusinessException("TOO_MANY_VARIANTS", "Un producto admite hasta " + MAX_VARIANTS + " variantes.");
        }
        updated.add(clean);
        storeVariants(updated);
    }

    public void removeVariant(String variant) {
        requireNotDiscontinued();
        String clean = variant == null ? "" : variant.trim();
        List<String> updated = new ArrayList<>(getVariants());
        if (!updated.removeIf(existing -> existing.equalsIgnoreCase(clean))) {
            throw new BusinessException("VARIANT_NOT_FOUND", "La variante no existe: " + clean);
        }
        storeVariants(updated);
    }

    private void storeVariants(List<String> variants) {
        Map<String, Object> copy = new HashMap<>(specifications);
        if (variants.isEmpty()) {
            copy.remove(VARIANTS_KEY);
        } else {
            copy.put(VARIANTS_KEY, variants);
        }
        this.specifications = Collections.unmodifiableMap(copy);
        touch();
    }

    private void requireNotDiscontinued() {
        if (status == ProductStatus.DISCONTINUED) {
            throw new BusinessException("INVALID_STATE_TRANSITION", "Un producto descontinuado no puede modificarse.");
        }
    }

    /**
     * Marca el producto agotado (el inventario quedó en cero).
     */
    public void markOutOfStock() {
        this.status = ProductStatus.OUT_OF_STOCK;
        touch();
    }

    /** Cambia el nombre y la descripción. El tipo, el vendedor, el precio y el estado no se modifican por aquí. */
    public void updateDetails(ProductName newName, ProductDescription newDescription) {
        requireNotDiscontinued();
        this.name = Objects.requireNonNull(newName, "newName es obligatorio");
        this.description = Objects.requireNonNull(newDescription, "newDescription es obligatorio");
        touch();
    }

    /** Cambia el código comercial. El {@code ProductId} no cambia, así que inventario, carritos y pedidos no se afectan. */
    public void changeSellerSku(SellerSku newSellerSku) {
        requireNotDiscontinued();
        this.sellerSku = Objects.requireNonNull(newSellerSku, "newSellerSku es obligatorio");
        touch();
    }

    public SellerSku getSellerSku() {
        return sellerSku;
    }

    public void updatePrice(Money newPrice) {
        requireNotDiscontinued();
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