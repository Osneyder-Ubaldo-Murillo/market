package nexus.market.domain.valueobjects;

/**
 * Identificador de devolución. Basado en UUID; se genera automáticamente.
 */
public final class ReturnId extends AbstractIdentifier {

    private ReturnId(String value) {
        super(value);
    }

    private ReturnId() {
        super();
    }

    public static ReturnId of(String value) {
        return new ReturnId(value);
    }

    public static ReturnId generate() {
        return new ReturnId();
    }
}