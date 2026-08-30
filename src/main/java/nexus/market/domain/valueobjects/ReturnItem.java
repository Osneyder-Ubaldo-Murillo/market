package nexus.market.domain.valueobjects;

import java.util.Objects;

/**
 * Value Object inmutable que representa un ítem devuelto en una
 * {@code Return}. El motivo de devolución es <em>por ítem</em>, no a nivel de
 * la devolución completa.
 *
 * <p><strong>Nota de diseño:</strong> el SDD referencia {@code ReturnItem} en
 * el modelo {@code Return} sin declararlo explícitamente entre los Value
 * Objects; se agrega aquí por coherencia con la regla {@code Order (1) —
 * (0..1) Return} y con {@code RefundEligibilitySpecification}.</p>
 */
public final class ReturnItem {

    private final ProductId productId;
    private final ProductName productName;
    private final Quantity quantity;
    private final ReturnReason reason;

    private ReturnItem(ProductId productId, ProductName productName, Quantity quantity, ReturnReason reason) {
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.reason = reason;
    }

    public static ReturnItem of(ProductId productId, ProductName productName,
                                Quantity quantity, ReturnReason reason) {
        if (productId == null) {
            throw new IllegalArgumentException("productId es obligatorio");
        }
        if (quantity == null || quantity.isZero()) {
            throw new IllegalArgumentException("La cantidad devuelta debe ser mayor que cero");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason es obligatorio");
        }
        return new ReturnItem(productId, productName, quantity, reason);
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

    public ReturnReason getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ReturnItem that = (ReturnItem) o;
        return productId.equals(that.productId)
                && productName.equals(that.productName)
                && quantity.equals(that.quantity)
                && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId, productName, quantity, reason);
    }

    @Override
    public String toString() {
        return productName + " x " + quantity + " (" + reason.getCode() + ")";
    }
}