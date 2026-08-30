package nexus.market.domain.valueobjects;

/**
 * Identificador de inventario. Basado en UUID; se genera automáticamente.
 */
public final class InventoryId extends AbstractIdentifier {

    private InventoryId(String value) {
        super(value);
    }

    private InventoryId() {
        super();
    }

    public static InventoryId of(String value) {
        return new InventoryId(value);
    }

    public static InventoryId generate() {
        return new InventoryId();
    }
}