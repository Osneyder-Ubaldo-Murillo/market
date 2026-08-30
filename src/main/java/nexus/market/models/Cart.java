package com.nexusmarket.domain.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

import com.nexusmarket.domain.exceptions.BusinessException;
import com.nexusmarket.domain.valueobjects.BuyerId;
import com.nexusmarket.domain.valueobjects.CartId;
import com.nexusmarket.domain.valueobjects.CartItem;
import com.nexusmarket.domain.valueobjects.Money;
import com.nexusmarket.domain.valueobjects.ProductId;
import com.nexusmarket.domain.valueobjects.ProductName;
import com.nexusmarket.domain.valueobjects.ProductType;
import com.nexusmarket.domain.valueobjects.Quantity;

/**
 * Agregado raíz que representa el carrito de compras de un {@link Buyer}.
 * Todos los ítems comparten una única moneda. El total se recalcula
 * automáticamente en cada operación.
 */
public class Cart {

    private final CartId cartId;
    private final BuyerId buyerId;
    private final Currency currency;
    private final List<CartItem> items;
    private Money total;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Cart(CartId cartId, BuyerId buyerId, Currency currency, List<CartItem> items,
                Money total, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.cartId = Objects.requireNonNull(cartId, "cartId es obligatorio");
        this.buyerId = Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        this.currency = Objects.requireNonNull(currency, "currency es obligatorio");
        this.items = new ArrayList<>();
        if (items != null) {
            for (CartItem item : items) {
                requireCurrency(item);
                this.items.add(item);
            }
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
        recalculateTotal();
    }

    /**
     * Crea un carrito vacío.
     */
    public static Cart create(BuyerId buyerId, Currency currency) {
        LocalDateTime now = LocalDateTime.now();
        return new Cart(CartId.generate(), buyerId, currency, List.of(),
                Money.zero(currency), now, now);
    }

    /**
     * Agrega un ítem. Si el producto ya existe en el carrito, se suman las
     * cantidades en un único ítem. El total se recalcula.
     */
    public void addItem(CartItem item) {
        Objects.requireNonNull(item, "item es obligatorio");
        requireCurrency(item);
        CartItem existing = findItem(item.getProductId());
        if (existing == null) {
            items.add(item);
        } else {
            Quantity combined = existing.getQuantity().add(item.getQuantity());
            items.set(items.indexOf(existing), existing.withQuantity(combined));
        }
        recalculateTotal();
    }

    /**
     * Actualiza la cantidad de un ítem. Una cantidad {@code ZERO} elimina el
     * ítem del carrito. El total se recalcula.
     */
    public void updateQuantity(ProductId productId, Quantity newQuantity) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        Objects.requireNonNull(newQuantity, "newQuantity es obligatorio");
        CartItem existing = findItem(productId);
        if (existing == null) {
            return;
        }
        if (newQuantity.isZero()) {
            items.remove(existing);
        } else {
            items.set(items.indexOf(existing), existing.withQuantity(newQuantity));
        }
        recalculateTotal();
    }

    /**
     * Elimina un ítem del carrito.
     */
    public void removeItem(ProductId productId) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        CartItem existing = findItem(productId);
        if (existing != null) {
            items.remove(existing);
            recalculateTotal();
        }
    }

    /**
     * Vacía el carrito y recalcula el total a cero.
     */
    public void clear() {
        items.clear();
        recalculateTotal();
    }

    private CartItem findItem(ProductId productId) {
        for (CartItem item : items) {
            if (item.getProductId().equals(productId)) {
                return item;
            }
        }
        return null;
    }

    private void requireCurrency(CartItem item) {
        if (!currency.equals(item.getUnitPrice().getCurrency())) {
            throw new BusinessException("CURRENCY_MISMATCH",
                    "Todos los ítems deben estar en la moneda del carrito: " + currency);
        }
    }

    private void recalculateTotal() {
        Money sum = Money.zero(currency);
        for (CartItem item : items) {
            sum = sum.add(item.getTotal());
        }
        this.total = sum;
        this.updatedAt = LocalDateTime.now();
    }

    public CartId getCartId() {
        return cartId;
    }

    public BuyerId getBuyerId() {
        return buyerId;
    }

    public Currency getCurrency() {
        return currency;
    }

    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Money getTotal() {
        return total;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}