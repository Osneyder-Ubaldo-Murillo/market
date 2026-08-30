package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: motivo de devolución, definido por ítem devuelto
 * ({@code ReturnItem}).
 */
public final class ReturnReason extends DomainCatalog {

    public static final ReturnReason DAMAGED = new ReturnReason("DAMAGED", "Producto Dañado", "El producto llegó dañado o defectuoso");
    public static final ReturnReason WRONG_ITEM = new ReturnReason("WRONG_ITEM", "Producto Incorrecto", "Se recibió un producto distinto al solicitado");
    public static final ReturnReason NOT_AS_DESCRIBED = new ReturnReason("NOT_AS_DESCRIBED", "No coincide con la descripción", "El producto no coincide con lo publicado por el vendedor");
    public static final ReturnReason OTHER = new ReturnReason("OTHER", "Otro motivo", "Cualquier otro motivo de devolución");

    private ReturnReason(String code, String name, String description) {
        super(code, name, description);
    }

    public static ReturnReason damaged() {
        return DAMAGED;
    }

    public static ReturnReason wrongItem() {
        return WRONG_ITEM;
    }

    public static ReturnReason notAsDescribed() {
        return NOT_AS_DESCRIBED;
    }

    public static ReturnReason other() {
        return OTHER;
    }
}