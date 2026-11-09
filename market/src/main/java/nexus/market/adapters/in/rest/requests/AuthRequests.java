package nexus.market.adapters.in.rest.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import nexus.market.adapters.in.rest.requests.CommonRequests.AddressRequest;

/**
 * DTOs de petición de autenticación y registro.
 */
public final class AuthRequests {

    private AuthRequests() {
    }

    /** Registro de comprador con contraseña. */
    public record RegisterBuyerRequest(
            @NotBlank(message = "fullName es obligatorio") @Size(max = 100, message = "fullName no puede exceder 100 caracteres") @Schema(description = "Nombre completo", example = "Ana Pérez") String fullName,
            @NotBlank(message = "email es obligatorio") @Size(max = 160, message = "email no puede exceder 160 caracteres") @Email(message = "email debe tener un formato válido") @Schema(description = "Correo electrónico (único)", example = "ana.perez@demo.test") String email,
            @NotBlank(message = "documentId es obligatorio") @Size(max = 20, message = "documentId no puede exceder 20 caracteres") @Schema(description = "Documento de identidad (único, de 4 a 20 caracteres)", example = "CC-1020304050") String documentId,
            @NotBlank(message = "password es obligatorio") @Size(min = 8, message = "password debe tener al menos 8 caracteres") @MaxUtf8Bytes(value = 72, message = "password no puede superar 72 bytes en UTF-8 (límite de BCrypt)") @Schema(description = "Contraseña (mínimo 8 caracteres, máximo 72 bytes)", example = "<clave-de-al-menos-8-caracteres>", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password,
            @NotNull(message = "address es obligatoria") @Valid @Schema(description = "Dirección") AddressRequest address) {
    }

    /** Registro de vendedor con contraseña (queda PENDING_VERIFICATION). */
    public record RegisterSellerRequest(
            @NotBlank(message = "fullName es obligatorio") @Size(max = 100, message = "fullName no puede exceder 100 caracteres") @Schema(description = "Nombre completo", example = "Ana Pérez") String fullName,
            @NotBlank(message = "email es obligatorio") @Size(max = 160, message = "email no puede exceder 160 caracteres") @Email(message = "email debe tener un formato válido") @Schema(description = "Correo electrónico (único)", example = "ana.perez@demo.test") String email,
            @NotBlank(message = "documentId es obligatorio") @Size(max = 20, message = "documentId no puede exceder 20 caracteres") @Schema(description = "Documento de identidad (único, de 4 a 20 caracteres)", example = "CC-1020304050") String documentId,
            @NotBlank(message = "password es obligatorio") @Size(min = 8, message = "password debe tener al menos 8 caracteres") @MaxUtf8Bytes(value = 72, message = "password no puede superar 72 bytes en UTF-8 (límite de BCrypt)") @Schema(description = "Contraseña (mínimo 8 caracteres, máximo 72 bytes)", example = "<clave-de-al-menos-8-caracteres>", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password,
            @NotBlank(message = "businessName es obligatorio") @Size(max = 100, message = "businessName no puede exceder 100 caracteres") @Schema(description = "Razón social del vendedor", example = "Comercial Demo S.A.S.") String businessName,
            @NotBlank(message = "taxId es obligatorio") @Size(max = 20, message = "taxId no puede exceder 20 caracteres") @Schema(description = "NIT o identificación tributaria", example = "900123456") String taxId) {
    }

    /**
     * Alta de personal interno por un administrador. {@code role} solo admite LOGISTICS_OPERATOR o SUPERVISOR; cualquier otro
     * valor (incluido ADMIN) se rechaza con 400. {@code toString()} oculta la contraseña.
     */
    public record RegisterStaffRequest(
            @NotBlank(message = "fullName es obligatorio") @Size(max = 100, message = "fullName no puede exceder 100 caracteres") @Schema(description = "Nombre completo", example = "Ana Pérez") String fullName,
            @NotBlank(message = "email es obligatorio") @Size(max = 160, message = "email no puede exceder 160 caracteres") @Email(message = "email debe tener un formato válido") @Schema(description = "Correo electrónico (único)", example = "ana.perez@demo.test") String email,
            @NotBlank(message = "documentId es obligatorio") @Size(max = 20, message = "documentId no puede exceder 20 caracteres") @Schema(description = "Documento de identidad (único, de 4 a 20 caracteres)", example = "CC-1020304050") String documentId,
            @NotBlank(message = "password es obligatorio") @Size(min = 8, message = "password debe tener al menos 8 caracteres") @MaxUtf8Bytes(value = 72, message = "password no puede superar 72 bytes en UTF-8 (límite de BCrypt)") @Schema(description = "Contraseña (mínimo 8 caracteres, máximo 72 bytes)", example = "<clave-de-al-menos-8-caracteres>", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password,
            @NotBlank(message = "role es obligatorio") @Pattern(regexp = "LOGISTICS_OPERATOR|SUPERVISOR", message = "role debe ser LOGISTICS_OPERATOR o SUPERVISOR") @Schema(description = "Rol del personal interno", example = "LOGISTICS_OPERATOR", allowableValues = {"LOGISTICS_OPERATOR", "SUPERVISOR"}) String role) {

        @Override
        public String toString() {
            return "RegisterStaffRequest[fullName=" + fullName + ", email=" + email + ", documentId=" + documentId
                    + ", password=***, role=" + role + "]";
        }
    }

    /** Login con email + contraseña. */
    /**
     * Cambio de la contraseña del administrador autenticado. NO lleva identificador de usuario: el destino es siempre el
     * usuario del token (un campo {@code userId} que llegue en el JSON se ignora). {@code toString()} oculta ambas
     * contraseñas para que no se filtren si el objeto llega a un log o a un mensaje de error.
     */
    public record ChangePasswordRequest(
            @NotBlank(message = "currentPassword es obligatorio")
            @MaxUtf8Bytes(value = 72, message = "currentPassword no puede superar 72 bytes en UTF-8") @Schema(description = "Contraseña actual", example = "<contrasena-actual>", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String currentPassword,
            @NotBlank(message = "newPassword es obligatorio")
            @Size(min = 12, message = "newPassword debe tener al menos 12 caracteres")
            @MaxUtf8Bytes(value = 72, message = "newPassword no puede superar 72 bytes en UTF-8 (límite de BCrypt)") @Schema(description = "Contraseña nueva (mínimo 12 caracteres)", example = "<clave-nueva-de-al-menos-12-caracteres>", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
        }
    }

    /** Solicitud de código de recuperación de contraseña. */
    public record ForgotPasswordRequest(
            @NotBlank(message = "email es obligatorio") @Size(max = 160, message = "email no puede exceder 160 caracteres") @Email(message = "email debe tener un formato válido") @Schema(description = "Correo electrónico (único)", example = "ana.perez@demo.test") String email) {
    }

    /** Canje del código de recuperación. {@code toString()} oculta el código y la contraseña. */
    public record ResetPasswordRequest(
            @NotBlank(message = "token es obligatorio") @Size(max = 2048, message = "token no puede exceder 2048 caracteres") @Schema(description = "Código recibido por correo", example = "<codigo-recibido-por-correo>") String token,
            @NotBlank(message = "newPassword es obligatorio") @Size(min = 8, message = "newPassword debe tener al menos 8 caracteres") @MaxUtf8Bytes(value = 72, message = "newPassword no puede superar 72 bytes en UTF-8 (límite de BCrypt)") @Schema(description = "Contraseña nueva (mínimo 12 caracteres)", example = "<clave-nueva-de-al-menos-12-caracteres>", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {
        @Override
        public String toString() {
            return "ResetPasswordRequest[token=***, newPassword=***]";
        }
    }

    /** Canje del código de verificación de correo. */
    public record VerifyEmailRequest(
            @NotBlank(message = "token es obligatorio") @Size(max = 2048, message = "token no puede exceder 2048 caracteres") @Schema(description = "Código recibido por correo", example = "<codigo-recibido-por-correo>") String token) {
        @Override
        public String toString() {
            return "VerifyEmailRequest[token=***]";
        }
    }

    /** Reenvío del código de verificación. */
    public record ResendVerificationRequest(
            @NotBlank(message = "email es obligatorio") @Size(max = 160, message = "email no puede exceder 160 caracteres") @Email(message = "email debe tener un formato válido") @Schema(description = "Correo electrónico (único)", example = "ana.perez@demo.test") String email) {
    }

    public record LoginRequest(
            @NotBlank(message = "email es obligatorio") @Email(message = "email debe tener un formato válido") @Schema(description = "Correo electrónico (único)", example = "ana.perez@demo.test") String email,
            @NotBlank(message = "password es obligatorio") @Schema(description = "Contraseña (mínimo 8 caracteres, máximo 72 bytes)", example = "<clave-de-al-menos-8-caracteres>", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
    }
}
