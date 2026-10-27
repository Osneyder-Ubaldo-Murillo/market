package nexus.market.application;

import java.util.Optional;

import nexus.market.domain.valueobjects.UserId;

/**
 * Puerto de salida de la capa de aplicación para el almacén de credenciales
 * (hash BCrypt de la contraseña por usuario).
 *
 * <p>Las contraseñas no viven en el dominio ({@code User} no tiene campos de
 * credenciales): este puerto pertenece a la capa de aplicación para mantener
 * el núcleo de negocio libre de aspectos de autenticación. Implementado por
 * {@code adapters.out.persistence.mysql} (perfil {@code jpa}).</p>
 */
public interface CredentialStorePort {

    void save(UserId userId, String passwordHash);

    Optional<String> findByUserId(UserId userId);

    /**
     * Hash vigente leyéndolo con <b>bloqueo de la fila</b> ({@code SELECT ... FOR UPDATE}) hasta el fin de la transacción
     * del llamador: dos cambios de contraseña del mismo usuario se serializan. Exige una transacción en curso.
     */
    Optional<String> findByUserIdForUpdate(UserId userId);

    /**
     * Sustituye el hash de una credencial EXISTENTE (actualización de la fila ya bloqueada con
     * {@link #findByUserIdForUpdate}; nunca crea una credencial). Solo cambia {@code password_hash}.
     *
     * @throws IllegalStateException si el usuario no tiene credencial
     */
    void replaceHash(UserId userId, String newPasswordHash);
}
