package nexus.market.domain.valueobjects;

/**
 * Identificador de producto. Basado en UUID; se genera automáticamente.
 */
public final class ProductId extends AbstractIdentifier {

    private ProductId(String value) {
        super(value);
    }

    private ProductId() {
        super();
    }

    public static ProductId of(String value) {
        return new ProductId(value);
    }

    public static ProductId generate() {
        return new ProductId();
    }
}