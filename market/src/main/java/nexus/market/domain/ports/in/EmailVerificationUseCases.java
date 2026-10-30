package nexus.market.domain.ports.in;

import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.UserId;

/** Verificación del correo electrónico de las cuentas de registro público. Sin verificar no se puede iniciar sesión. */
public interface EmailVerificationUseCases {

    /** Emite y entrega (por {@code NotificationPort}) el código de verificación de la cuenta recién registrada. */
    void sendVerificationCode(UserId userId, Email email);

    /**
     * Verifica el correo con el código recibido. Es idempotente y un código inválido o vencido produce siempre el mismo
     * error ({@code INVALID_VERIFICATION_TOKEN}).
     */
    void verify(String token);

    /** Reenvía el código. Nunca revela si el correo existe ni si ya estaba verificado. */
    void resend(Email email);
}
