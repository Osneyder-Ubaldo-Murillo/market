package nexus.market.domain.valueobjects;

/**
 * Identificador de carrito. Basado en UUID; se genera automáticamente.
 */
public final class CartId extends AbstractIdentifier {

    private CartId(String value) {
        super(value);
    }

    private CartId() {
        super();
    }

    public static CartId of(String value) {
        return new CartId(value);
    }

    public static CartId generate() {
        return new CartId();
    }
}