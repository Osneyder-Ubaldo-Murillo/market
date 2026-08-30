package nexus.market.domain.valueobjects;

/**
 * Identificador de usuario. Basado en UUID; se genera automáticamente.
 */
public final class UserId extends AbstractIdentifier {

    private UserId(String value) {
        super(value);
    }

    private UserId() {
        super();
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    public static UserId generate() {
        return new UserId();
    }
}