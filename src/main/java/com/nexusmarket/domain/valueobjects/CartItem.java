package com.nexusmarket.domain.valueobjects;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Value Object inmutable que representa un ítem del {@code Cart}.
 * El total se calcula como {@code unitPrice * quantity}. Las modificaciones de
 * cantidad se resuelven con {@link #withQuantity(Quantity)}, que devuelve un
 * ítem nuevo (nunca se muta esta instancia).
 */
public final class CartItem {

    private final ProductId productId;
    private final ProductName productName;
    private final Quantity quantity;
    private final Money unitPrice;
    private final Money total;
    private final ProductType productType;

    private CartItem(ProductId productId, ProductName productName, Quantity quantity,
                     Money unitPrice, Money total, ProductType productType) {
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.total = total;
        this.productType = productType;
    }

    public static CartItem of(ProductId productId, ProductName productName, Quantity quantity,
                              Money unitPrice, ProductType productType) {
        if (productId == null) {
            throw new IllegalArgumentException("productId es obligatorio");
        }
        if (productName == null) {
            throw new IllegalArgumentException("productName es obligatorio");
        }
        if (quantity == null || quantity.isZero()) {
            throw new IllegalArgumentException("La cantidad del ítem debe ser mayor que cero");
        }
        if (unitPrice == null) {
            throw new IllegalArgumentException("unitPrice es obligatorio");
        }
        if (productType == null) {
            throw new IllegalArgumentException("productType es obligatorio");
        }
        Money total = unitPrice.multiply(BigDecimal.valueOf(quantity.value()));
        return new CartItem(productId, productName, quantity, unitPrice, total, productType);
    }

    /**
     * Devuelve una nueva instancia de {@link CartItem} con la cantidad indicada
     * y el total recalculado. Solo acepta cantidades positivas.
     */
    public CartItem withQuantity(Quantity newQuantity) {
        if (newQuantity == null || newQuantity.isZero()) {
            throw new IllegalArgumentException("La cantidad del ítem debe ser mayor que cero");
        }
        return of(productId, productName, newQuantity, unitPrice, productType);
    }

    public ProductId getProductId() {
        return productId;
    }

    public ProductName getProductName() {
        return productName;
    }

    public Quantity getQuantity() {
        return quantity;
    }

    public Money getUnitPrice() {
        return unitPrice;
    }

    public Money getTotal() {
        return total;
    }

    public ProductType getProductType() {
        return productType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CartItem cartItem = (CartItem) o;
        return productId.equals(cartItem.productId)
                && productName.equals(cartItem.productName)
                && quantity.equals(cartItem.quantity)
                && unitPrice.equals(cartItem.unitPrice)
                && total.equals(cartItem.total)
                && productType.equals(cartItem.productType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId, productName, quantity, unitPrice, total, productType);
    }

    @Override
    public String toString() {
        return productName + " x " + quantity + " = " + total;
    }
}