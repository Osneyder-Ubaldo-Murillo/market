package nexus.market.domain.ports.in;

import nexus.market.domain.valueobjects.UserId;

/**
 * Cambio de contraseña del administrador (solo con la contraseña actual). La recuperación sin la contraseña actual NO
 * forma parte de este puerto: es un procedimiento operativo fuera de línea (ver docs/CAMBIO_CONTRASENA_PROPUESTA.md).
 */
public interface PasswordChangeUseCases {

    /**
     * Cambia la contraseña del administrador {@code userId} (siempre el usuario autenticado, nunca un destino indicado por
     * el cliente) y devuelve un token NUEVO ligado a la credencial nueva; los tokens anteriores dejan de valer.
     *
     * @throws nexus.market.domain.exceptions.BusinessException {@code INVALID_CURRENT_PASSWORD},
     *         {@code PASSWORD_POLICY_VIOLATION}, {@code NEW_PASSWORD_SAME_AS_CURRENT} o {@code TOO_MANY_ATTEMPTS}
     */
    AuthUseCases.AuthResult changeAdminPassword(UserId userId, String currentPassword, String newPassword);
}
