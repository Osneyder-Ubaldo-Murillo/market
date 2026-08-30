package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: estado del {@code Seller}. El estado inicial de un
 * vendedor es {@code PENDING_VERIFICATION}; solo un Administrador puede
 * aprobarlo.
 */
public final class SellerStatus extends DomainCatalog {

    public static final SellerStatus ACTIVE = new SellerStatus("ACTIVE", "Activo", "Vendedor habilitado");
    public static final SellerStatus BLOCKED = new SellerStatus("BLOCKED", "Bloqueado", "Vendedor suspendido");
    public static final SellerStatus INACTIVE = new SellerStatus("INACTIVE", "Inactivo", "Vendedor inactivo");
    public static final SellerStatus PENDING_VERIFICATION = new SellerStatus("PENDING_VERIFICATION", "Pendiente de Verificación", "En espera de aprobación");

    private SellerStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static SellerStatus active() {
        return ACTIVE;
    }

    public static SellerStatus blocked() {
        return BLOCKED;
    }

    public static SellerStatus inactive() {
        return INACTIVE;
    }

    public static SellerStatus pendingVerification() {
        return PENDING_VERIFICATION;
    }
}