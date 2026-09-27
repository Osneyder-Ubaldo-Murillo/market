package nexus.market.domain.ports.out;

/**
 * Puerto de salida para el hash y verificación de contraseñas.
 * Implementado por {@code adapters.out.security} (BCrypt/Argon2).
 */
public interface PasswordServicePort {

    String hash(String rawPassword);

    boolean verify(String rawPassword, String hashedPassword);
}