package nexus.market.domain.ports.in;

import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Seller;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BusinessName;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.TaxId;

/**
 * Puerto de entrada (caso de uso) de autenticación y registro con credenciales.
 *
 * <p>La capa de aplicación almacena el hash de la contraseña (BCrypt) y emite
 * el token JWT tras verificarla. El dominio no conoce contraseñas ni tokens:
 * solo recibe y devuelve tipos del dominio.</p>
 */
public interface AuthUseCases {

    Buyer registerBuyer(FullName fullName, Email email, DocumentId documentId,
                        Address mainAddress, String rawPassword);

    Seller registerSeller(FullName fullName, Email email, DocumentId documentId,
                          BusinessName businessName, TaxId taxId, String rawPassword);

    /**
     * Autentica al usuario: verifica la contraseña, exige estado
     * {@code ACTIVE} y devuelve un token JWT firmado.
     */
    AuthResult login(Email email, String rawPassword);

    /** Resultado del login: token JWT, usuario autenticado y su rol. */
    record AuthResult(String token, nexus.market.domain.valueobjects.UserId userId,
                      nexus.market.domain.valueobjects.SystemRole role) {
    }
}
