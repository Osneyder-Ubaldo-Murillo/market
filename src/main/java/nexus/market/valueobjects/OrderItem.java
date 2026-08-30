package com.nexusmarket.domain.valueobjects;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Value Object inmutable que representa un ítem del {@code Order}. Es una
 * copia congelada de {@link CartItem}: similar en estructura pero sin métodos
 * de modificación.
 */
public final class OrderItem {

    private final ProductId productId;
    private final ProductName productName;
    private final Quantity quantity;
    private final Money unitPrice;
    private final Money total;
    private final ProductType productType;

    private OrderItem(ProductId productId, ProductName productName, Quantity quantity,
                      Money unitPrice, Money total, ProductType productType) {
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.total = total;
        this.productType = productType;
    }

    public static OrderItem of(ProductId productId, ProductName productName, Quantity quantity,
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
        return new OrderItem(productId, productName, quantity, unitPrice, total, productType);
    }

    /**
     * Convierte un {@link CartItem} en un {@link OrderItem} (copia congelada).
     */
    public static OrderItem from(CartItem cartItem) {
        Objects.requireNonNull(cartItem, "cartItem es obligatorio");
        return of(cartItem.getProductId(), cartItem.getProductName(),
                cartItem.getQuantity(), cartItem.getUnitPrice(), cartItem.getProductType());
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
        OrderItem orderItem = (OrderItem) o;
        return productId.equals(orderItem.productId)
                && productName.equals(orderItem.productName)
                && quantity.equals(orderItem.quantity)
                && unitPrice.equals(orderItem.unitPrice)
                && total.equals(orderItem.total)
                && productType.equals(orderItem.productType);
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