package nexus.market.adapters.out.persistence.mysql;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.Seller;
import nexus.market.domain.ports.out.SellerRepositoryPort;
import nexus.market.domain.valueobjects.BusinessName;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SellerStatus;
import nexus.market.domain.valueobjects.TaxId;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Entidad JPA de {@link Seller} (tabla {@code sellers}) con su colección de
 * bodegas asociadas ({@code seller_warehouses}).
 */
@Entity
@Table(name = "sellers")
class SellerEntity {

    @Id
    @Column(name = "seller_id", length = 36, updatable = false)
    String sellerId;

    @Column(name = "user_id", length = 36, nullable = false)
    String userId;

    @Column(name = "business_name", length = 160, nullable = false)
    String businessName;

    @Column(name = "tax_id", length = 40, nullable = false, unique = true)
    String taxId;

    @Column(name = "status", length = 30, nullable = false)
    String status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "seller_warehouses", joinColumns = @JoinColumn(name = "seller_id"))
    @OrderColumn(name = "position")
    List<JpaValueTypes.WarehouseRefValue> warehouses = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected SellerEntity() {
    }

    static SellerEntity from(Seller seller) {
        SellerEntity entity = new SellerEntity();
        entity.sellerId = seller.getSellerId().value();
        entity.userId = seller.getUserId().value();
        entity.businessName = seller.getBusinessName().value();
        entity.taxId = seller.getTaxId().value();
        entity.status = seller.getStatus().getCode();
        entity.warehouses = seller.getWarehouses().stream()
                .map(JpaValueTypes.WarehouseRefValue::from)
                .collect(Collectors.toCollection(ArrayList::new));
        entity.createdAt = seller.getCreatedAt();
        entity.updatedAt = seller.getUpdatedAt();
        return entity;
    }

    Seller toDomain() {
        List<WarehouseId> warehouseIds = warehouses.stream()
                .map(JpaValueTypes.WarehouseRefValue::toDomain)
                .collect(Collectors.toList());
        return new Seller(SellerId.of(sellerId), UserId.of(userId),
                BusinessName.of(businessName), TaxId.of(taxId),
                JpaCatalogCodecs.INSTANCE.sellerStatus(status),
                warehouseIds, createdAt, updatedAt);
    }
}
/**
 * Adaptador de salida {@link SellerRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaSellerRepositoryPort extends JpaRepositorySupport implements SellerRepositoryPort {

    JpaSellerRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Seller save(Seller seller) {
        return persist(SellerEntity.from(seller)).toDomain();
    }

    @Override
    public Optional<Seller> findById(SellerId sellerId) {
        return find(SellerEntity.class, sellerId.value()).map(SellerEntity::toDomain);
    }

    @Override
    public Optional<Seller> findByUserId(UserId userId) {
        return querySingle(SellerEntity.class,
                "select s from SellerEntity s where s.userId = :uid", Map.of("uid", userId.value()))
                .map(SellerEntity::toDomain);
    }

    @Override
    public List<Seller> findByStatus(SellerStatus status) {
        return query(SellerEntity.class,
                "select s from SellerEntity s where s.status = :status", Map.of("status", status.getCode()))
                .stream().map(SellerEntity::toDomain).collect(Collectors.toList());
    }
}