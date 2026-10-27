package nexus.market.adapters.in.rest.requests;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
            @NotBlank(message = "fullName es obligatorio") @Size(max = 100, message = "fullName no puede exceder 100 caracteres") String fullName,
            @NotBlank(message = "email es obligatorio") @Size(max = 160, message = "email no puede exceder 160 caracteres") @Email(message = "email debe tener un formato válido") String email,
            @NotBlank(message = "documentId es obligatorio") @Size(max = 20, message = "documentId no puede exceder 20 caracteres") String documentId,
            @NotBlank(message = "password es obligatorio") @Size(min = 8, message = "password debe tener al menos 8 caracteres") @MaxUtf8Bytes(value = 72, message = "password no puede superar 72 bytes en UTF-8 (límite de BCrypt)") String password,
            @NotNull(message = "address es obligatoria") @Valid AddressRequest address) {
    }

    /** Registro de vendedor con contraseña (queda PENDING_VERIFICATION). */
    public record RegisterSellerRequest(
            @NotBlank(message = "fullName es obligatorio") @Size(max = 100, message = "fullName no puede exceder 100 caracteres") String fullName,
            @NotBlank(message = "email es obligatorio") @Size(max = 160, message = "email no puede exceder 160 caracteres") @Email(message = "email debe tener un formato válido") String email,
            @NotBlank(message = "documentId es obligatorio") @Size(max = 20, message = "documentId no puede exceder 20 caracteres") String documentId,
            @NotBlank(message = "password es obligatorio") @Size(min = 8, message = "password debe tener al menos 8 caracteres") @MaxUtf8Bytes(value = 72, message = "password no puede superar 72 bytes en UTF-8 (límite de BCrypt)") String password,
            @NotBlank(message = "businessName es obligatorio") @Size(max = 100, message = "businessName no puede exceder 100 caracteres") String businessName,
            @NotBlank(message = "taxId es obligatorio") @Size(max = 20, message = "taxId no puede exceder 20 caracteres") String taxId) {
    }

    /** Login con email + contraseña. */
    /**
     * Cambio de la contraseña del administrador autenticado. NO lleva identificador de usuario: el destino es siempre el
     * usuario del token (un campo {@code userId} que llegue en el JSON se ignora). {@code toString()} oculta ambas
     * contraseñas para que no se filtren si el objeto llega a un log o a un mensaje de error.
     */
    public record ChangePasswordRequest(
            @NotBlank(message = "currentPassword es obligatorio")
            @MaxUtf8Bytes(value = 72, message = "currentPassword no puede superar 72 bytes en UTF-8") String currentPassword,
            @NotBlank(message = "newPassword es obligatorio")
            @Size(min = 12, message = "newPassword debe tener al menos 12 caracteres")
            @MaxUtf8Bytes(value = 72, message = "newPassword no puede superar 72 bytes en UTF-8 (límite de BCrypt)") String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
        }
    }

    public record LoginRequest(
            @NotBlank(message = "email es obligatorio") @Email(message = "email debe tener un formato válido") String email,
            @NotBlank(message = "password es obligatorio") String password) {
    }
}
