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
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.Buyer;
import nexus.market.domain.ports.out.BuyerRepositoryPort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.UserId;

/**
 * Entidad JPA de {@link Buyer} (tabla {@code buyers}) con sus direcciones
 * adicionales ({@code buyer_additional_addresses}).
 */
@Entity
@Table(name = "buyers")
class BuyerEntity {

    @Id
    @Column(name = "buyer_id", length = 36, updatable = false)
    String buyerId;

    @Column(name = "user_id", length = 36, nullable = false)
    String userId;

    @Embedded
    JpaValueTypes.AddressValue mainAddress = new JpaValueTypes.AddressValue();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "buyer_additional_addresses", joinColumns = @JoinColumn(name = "buyer_id"))
    @OrderColumn(name = "position")
    List<JpaValueTypes.AddressValue> additionalAddresses = new ArrayList<>();

    @Column(name = "commercial_status", length = 30, nullable = false)
    String commercialStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected BuyerEntity() {
    }

    static BuyerEntity from(Buyer buyer) {
        BuyerEntity entity = new BuyerEntity();
        entity.buyerId = buyer.getBuyerId().value();
        entity.userId = buyer.getUserId().value();
        entity.mainAddress = JpaValueTypes.AddressValue.from(buyer.getMainAddress());
        entity.additionalAddresses = buyer.getAdditionalAddresses().stream()
                .map(JpaValueTypes.AddressValue::from)
                .collect(Collectors.toCollection(ArrayList::new));
        entity.commercialStatus = buyer.getCommercialStatus().getCode();
        entity.createdAt = buyer.getCreatedAt();
        entity.updatedAt = buyer.getUpdatedAt();
        return entity;
    }

    Buyer toDomain() {
        List<Address> additional = additionalAddresses.stream()
                .map(JpaValueTypes.AddressValue::toDomain)
                .collect(Collectors.toList());
        return new Buyer(BuyerId.of(buyerId), UserId.of(userId), mainAddress.toDomain(),
                additional, JpaCatalogCodecs.INSTANCE.commercialStatus(commercialStatus),
                createdAt, updatedAt);
    }
}
/**
 * Adaptador de salida {@link BuyerRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaBuyerRepositoryPort extends JpaRepositorySupport implements BuyerRepositoryPort {

    JpaBuyerRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Buyer save(Buyer buyer) {
        return persist(BuyerEntity.from(buyer)).toDomain();
    }

    @Override
    public Optional<Buyer> findById(BuyerId buyerId) {
        return find(BuyerEntity.class, buyerId.value()).map(BuyerEntity::toDomain);
    }

    @Override
    public Optional<Buyer> findByUserId(UserId userId) {
        return querySingle(BuyerEntity.class,
                "select b from BuyerEntity b where b.userId = :uid", Map.of("uid", userId.value()))
                .map(BuyerEntity::toDomain);
    }
}