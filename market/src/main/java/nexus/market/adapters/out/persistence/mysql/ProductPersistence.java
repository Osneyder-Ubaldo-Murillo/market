package nexus.market.adapters.out.persistence.mysql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.Product;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductStatus;
import nexus.market.domain.valueobjects.SellerId;

/**
 * Entidad JPA de {@link Product} (tabla {@code products}). Las especificaciones
 * técnicas se guardan como JSON (texto) en {@code specifications_json}.
 */
@Entity
@Table(name = "products")
class ProductEntity {

    @Id
    @Column(name = "product_id", length = 36, updatable = false)
    String productId;

    @Column(name = "seller_id", length = 36, nullable = false)
    String sellerId;

    @Column(name = "name", length = 120, nullable = false)
    String name;

    @Column(name = "description", length = 500, nullable = false)
    String description;

    @Column(name = "product_type", length = 30, nullable = false)
    String productType;

    @Column(name = "price_amount", precision = 15, scale = 2, nullable = false)
    BigDecimal priceAmount;

    @Column(name = "price_currency", length = 3, nullable = false)
    String priceCurrency;

    @Column(name = "status", length = 30, nullable = false)
    String status;

    @Lob
    @Column(name = "specifications_json", nullable = false)
    String specificationsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected ProductEntity() {
    }

    static ProductEntity from(Product product) {
        ProductEntity entity = new ProductEntity();
        entity.productId = product.getProductId().value();
        entity.sellerId = product.getSellerId().value();
        entity.name = product.getName().value();
        entity.description = product.getDescription().value();
        entity.productType = product.getProductType().getCode();
        entity.priceAmount = product.getPrice().getAmount();
        entity.priceCurrency = product.getPrice().getCurrency().getCurrencyCode();
        entity.status = product.getStatus().getCode();
        entity.specificationsJson = JpaValueTypes.JsonCodec.toJson(product.getSpecifications());
        entity.createdAt = product.getCreatedAt();
        entity.updatedAt = product.getUpdatedAt();
        return entity;
    }

    Product toDomain() {
        JpaCatalogCodecs catalogs = JpaCatalogCodecs.INSTANCE;
        Map<String, Object> specifications = JpaValueTypes.JsonCodec.fromJson(specificationsJson);
        return new Product(ProductId.of(productId), SellerId.of(sellerId),
                ProductName.of(name), ProductDescription.of(description),
                catalogs.productType(productType),
                Money.of(priceAmount, priceCurrency), catalogs.productStatus(status),
                specifications, createdAt, updatedAt);
    }
}

/**
 * Adaptador de salida {@link ProductRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaProductRepositoryPort extends JpaRepositorySupport implements ProductRepositoryPort {

    JpaProductRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Product save(Product product) {
        return persist(ProductEntity.from(product)).toDomain();
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        return find(ProductEntity.class, productId.value()).map(ProductEntity::toDomain);
    }

    @Override
    public List<Product> findBySellerId(SellerId sellerId) {
        return query(ProductEntity.class,
                "select p from ProductEntity p where p.sellerId = :sid",
                Map.of("sid", sellerId.value()))
                .stream().map(ProductEntity::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Product> findByStatus(ProductStatus status) {
        return query(ProductEntity.class,
                "select p from ProductEntity p where p.status = :status",
                Map.of("status", status.getCode()))
                .stream().map(ProductEntity::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<Product> findPublished() {
        return query(ProductEntity.class,
                "select p from ProductEntity p where p.status = :status",
                Map.of("status", ProductStatus.ACTIVE.getCode()))
                .stream().map(ProductEntity::toDomain).collect(Collectors.toList());
    }
}