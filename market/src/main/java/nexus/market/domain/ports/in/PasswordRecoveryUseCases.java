package nexus.market.domain.ports.in;

import nexus.market.domain.valueobjects.Email;

/**
 * Recuperación de contraseña por el propio usuario (compradores, vendedores y demás personal excepto ADMIN, cuya
 * recuperación es un procedimiento aparte: ver {@code docs/ROTACION_Y_RECUPERACION_ADMIN.md}).
 */
public interface PasswordRecoveryUseCases {

    /**
     * Solicita un código de recuperación. <b>Nunca revela si el email existe</b>: termina igual exista o no, esté activa o
     * no la cuenta. Si procede, el código se entrega por {@code NotificationPort}.
     */
    void requestReset(Email email);

    /**
     * Restablece la contraseña con el código recibido. Un código inválido, vencido o ya usado produce siempre el mismo
     * error ({@code INVALID_RESET_TOKEN}). Al cambiar la contraseña dejan de valer todos los tokens anteriores del usuario.
     */
    void resetPassword(String token, String newPassword);
}
