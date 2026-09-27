package nexus.market.adapters.out.security;

import java.util.Objects;

import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;

import nexus.market.domain.ports.out.PasswordServicePort;

/**
 * Adaptador de salida {@link PasswordServicePort} con BCrypt
 * (Spring Security Crypto).
 */
@Component
public class BcryptPasswordServicePort implements PasswordServicePort {

    @Override
    public String hash(String rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword es obligatorio");
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    @Override
    public boolean verify(String rawPassword, String hashedPassword) {
        if (rawPassword == null || hashedPassword == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, hashedPassword);
        } catch (IllegalArgumentException ex) {
            // Hash malformado: se trata como contraseña no válida.
            return false;
        }
    }
}