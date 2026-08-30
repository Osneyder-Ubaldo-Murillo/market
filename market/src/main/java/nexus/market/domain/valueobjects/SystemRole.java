package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: rol del usuario dentro del sistema.
 * El rol de un {@code User} es inmutable; si se requiere otro rol se crea un
 * nuevo usuario.
 */
public final class SystemRole extends DomainCatalog {

    public static final SystemRole BUYER = new SystemRole("BUYER", "Comprador", "Usuario que realiza compras");
    public static final SystemRole SELLER = new SystemRole("SELLER", "Vendedor", "Usuario que vende productos");
    public static final SystemRole LOGISTICS_OPERATOR = new SystemRole("LOGISTICS_OPERATOR", "Operador Logístico", "Encargado de bodegas y despachos");
    public static final SystemRole ADMIN = new SystemRole("ADMIN", "Administrador", "Gestiona vendedores y bodegas");
    public static final SystemRole SUPERVISOR = new SystemRole("SUPERVISOR", "Supervisor", "Consulta y seguimiento operativo");

    private SystemRole(String code, String name, String description) {
        super(code, name, description);
    }

    public static SystemRole buyer() {
        return BUYER;
    }

    public static SystemRole seller() {
        return SELLER;
    }

    public static SystemRole logisticsOperator() {
        return LOGISTICS_OPERATOR;
    }

    public static SystemRole admin() {
        return ADMIN;
    }

    public static SystemRole supervisor() {
        return SUPERVISOR;
    }
}