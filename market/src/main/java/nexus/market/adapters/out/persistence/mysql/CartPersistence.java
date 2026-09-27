package nexus.market.adapters.out.persistence.mysql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Currency;
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

import nexus.market.domain.models.Cart;
import nexus.market.domain.ports.out.CartRepositoryPort;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.CartId;
import nexus.market.domain.valueobjects.CartItem;

/**
 * Entidad JPA de {@link Cart} (tabla {@code carts}) con sus ítems
 * ({@code cart_items}). El total se guarda como instantánea para consultas.
 */
@Entity
@Table(name = "carts")
class CartEntity {

    @Id
    @Column(name = "cart_id", length = 36, updatable = false)
    String cartId;

    @Column(name = "buyer_id", length = 36, nullable = false, unique = true)
    String buyerId;

    @Column(name = "currency", length = 3, nullable = false)
    String currency;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cart_items", joinColumns = @JoinColumn(name = "cart_id"))
    @OrderColumn(name = "position")
    List<JpaValueTypes.ItemValue> items = new ArrayList<>();

    @Column(name = "total_amount", precision = 15, scale = 2, nullable = false)
    BigDecimal totalAmount;

    @Column(name = "total_currency", length = 3, nullable = false)
    String totalCurrency;

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected CartEntity() {
    }

    static CartEntity from(Cart cart) {
        CartEntity entity = new CartEntity();
        entity.cartId = cart.getCartId().value();
        entity.buyerId = cart.getBuyerId().value();
        entity.currency = cart.getCurrency().getCurrencyCode();
        entity.items = cart.getItems().stream()
                .map(JpaValueTypes.ItemValue::fromCartItem)
                .collect(Collectors.toCollection(ArrayList::new));
        entity.totalAmount = cart.getTotal().getAmount();
        entity.totalCurrency = cart.getTotal().getCurrency().getCurrencyCode();
        entity.createdAt = cart.getCreatedAt();
        entity.updatedAt = cart.getUpdatedAt();
        return entity;
    }

    Cart toDomain() {
        List<CartItem> domainItems = items.stream()
                .map(JpaValueTypes.ItemValue::toCartItem)
                .collect(Collectors.toList());
        return new Cart(CartId.of(cartId), BuyerId.of(buyerId),
                Currency.getInstance(currency), domainItems, createdAt, updatedAt);
    }
}

/**
 * Adaptador de salida {@link CartRepositoryPort} sobre JPA (MySQL).
 */
@Component
@Profile("jpa")
class JpaCartRepositoryPort extends JpaRepositorySupport implements CartRepositoryPort {

    JpaCartRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public Cart save(Cart cart) {
        return persist(CartEntity.from(cart)).toDomain();
    }

    @Override
    public Optional<Cart> findById(CartId cartId) {
        return find(CartEntity.class, cartId.value()).map(CartEntity::toDomain);
    }

    @Override
    public Optional<Cart> findByBuyerId(BuyerId buyerId) {
        return querySingle(CartEntity.class,
                "select c from CartEntity c where c.buyerId = :buyerId",
                Map.of("buyerId", buyerId.value()))
                .map(CartEntity::toDomain);
    }
}