package nexus.market.application;

import nexus.market.domain.models.User;

/**
 * Almacén del administrador inicial. Solo INSERCIÓN (nunca {@code merge}): no puede sobrescribir un
 * usuario ni unas credenciales existentes.
 */
public interface InitialAdminStorePort {

    enum Outcome {
        /** Se insertaron el usuario y sus credenciales. */
        CREATED,
        /** Ya existía un usuario con rol ADMIN (cualquier estado): no se hizo nada. */
        ADMIN_ALREADY_EXISTS
    }

    /** {@code true} si existe cualquier usuario con rol ADMIN, activo, inactivo o bloqueado. */
    boolean existsAnyAdmin();

    /**
     * Inserta usuario y credenciales con una sola sentencia condicional ({@code INSERT ... SELECT ... WHERE NOT
     * EXISTS (... role = 'ADMIN')}) y, solo si se insertó, la credencial. Debe invocarse dentro de una
     * transacción del llamador (si falla algo, no queda nada).
     *
     * @throws InitialAdminException si el correo o el documento pertenecen a un usuario que no es administrador
     */
    Outcome insertIfNoAdmin(User admin, String passwordHash);
}
