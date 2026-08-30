package nexus.market.domain.valueobjects;

/**
 * Identificador de vendedor. Basado en UUID; se genera automáticamente.
 */
public final class SellerId extends AbstractIdentifier {

    private SellerId(String value) {
        super(value);
    }

    private SellerId() {
        super();
    }

    public static SellerId of(String value) {
        return new SellerId(value);
    }

    public static SellerId generate() {
        return new SellerId();
    }
}