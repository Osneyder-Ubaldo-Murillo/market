package nexus.market.domain.ports.out;

import java.util.Optional;

import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Puerto de salida para la emisión y validación de tokens JWT.
 * Implementado por {@code adapters.out.security}.
 */
public interface JwtServicePort {

    /** Emite un token SIN huella de credencial (solo para usuarios sin credenciales, p. ej. personal creado fuera del registro). */
    String issue(UserId userId, SystemRole role);

    /**
     * Emite un token que lleva la huella de la credencial vigente (claim {@code pwv}, ver
     * {@link #credentialVersion(String)}): al cambiar la contraseña, los tokens anteriores dejan de coincidir.
     */
    String issue(UserId userId, SystemRole role, String credentialVersion);

    /**
     * Huella de una credencial: HMAC-SHA256 del hash de contraseña con el secreto JWT, truncada. No es reversible ni
     * revela el hash; cambia siempre que cambia el hash (BCrypt incluye una sal nueva en cada cifrado).
     */
    String credentialVersion(String passwordHash);

    /** Claim {@code pwv} de un token válido, si lo lleva. */
    Optional<String> getCredentialVersion(String token);

    boolean isValid(String token);

    Optional<UserId> getUserId(String token);
}