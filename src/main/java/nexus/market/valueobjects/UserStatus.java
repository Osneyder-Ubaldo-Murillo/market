package com.nexusmarket.domain.valueobjects;

/**
 * Catálogo de negocio: estado operativo de un {@code User}.
 */
public final class UserStatus extends DomainCatalog {

    public static final UserStatus ACTIVE = new UserStatus("ACTIVE", "Activo", "Usuario puede operar normalmente");
    public static final UserStatus BLOCKED = new UserStatus("BLOCKED", "Bloqueado", "Acceso suspendido");
    public static final UserStatus INACTIVE = new UserStatus("INACTIVE", "Inactivo", "Usuario existente pero sin operaciones");

    private UserStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static UserStatus active() {
        return ACTIVE;
    }

    public static UserStatus blocked() {
        return BLOCKED;
    }

    public static UserStatus inactive() {
        return INACTIVE;
    }
}