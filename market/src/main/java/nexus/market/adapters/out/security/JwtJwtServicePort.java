package nexus.market.adapters.out.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Adaptador de salida {@link JwtServicePort} con JWT HS256 (librería
 * {@code com.auth0:java-jwt}). El secreto se configura con
 * {@code jwt.secret} (ver {@code application.properties}).
 */
@Component
public class JwtJwtServicePort implements JwtServicePort {

    /** Mínimo para HS256 (RFC 7518 §3.2): 256 bits = 32 bytes del secreto en UTF-8. */
    static final int MIN_SECRET_BYTES = 32;

    /** Fragmentos de valores de ejemplo/respaldo que nunca deben usarse en producción. */
    private static final List<String> KNOWN_WEAK_FRAGMENTS = List.of(
            "change-me", "changeme", "dev-secret", "tu_secreto", "your_super_secret", "your-secret",
            "example", "ejemplo", "password", "default");

    private final String secret;
    private final String issuer;
    private final long expirationMillis;

    @Autowired
    public JwtJwtServicePort(@Value("${jwt.secret}") String secret,
                             @Value("${jwt.issuer:nexusmarket}") String issuer,
                             @Value("${jwt.expiration:86400000}") long expirationMillis,
                             Environment environment) {
        this(secret, issuer, expirationMillis, environment.acceptsProfiles(Profiles.of("prod")));
    }

    /** Sin perfil de producción (desarrollo y pruebas): solo se exige longitud y no vacío. */
    public JwtJwtServicePort(String secret, String issuer, long expirationMillis) {
        this(secret, issuer, expirationMillis, false);
    }

    private JwtJwtServicePort(String secret, String issuer, long expirationMillis, boolean production) {
        validateSecret(secret, production);
        this.secret = secret;
        this.issuer = issuer;
        this.expirationMillis = expirationMillis;
    }

    /**
     * El secreto se usa como texto UTF-8 (así lo interpreta {@code Algorithm.HMAC256(String)}),
     * por lo que la longitud se mide en bytes. En producción además se rechazan valores de
     * ejemplo conocidos y secretos de baja variedad. Los mensajes no incluyen el secreto.
     */
    static void validateSecret(String secret, boolean production) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret es obligatorio y no puede estar vacío.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret debe tener al menos " + MIN_SECRET_BYTES + " bytes (UTF-8) para HS256.");
        }
        if (production) {
            String lower = secret.toLowerCase(Locale.ROOT);
            if (KNOWN_WEAK_FRAGMENTS.stream().anyMatch(lower::contains)) {
                throw new IllegalStateException(
                        "jwt.secret contiene un valor de ejemplo conocido: genere un secreto aleatorio propio "
                                + "(p. ej. openssl rand -base64 48) y defínalo en JWT_SECRET.");
            }
            if (secret.chars().distinct().count() < 12) {
                throw new IllegalStateException("jwt.secret es demasiado predecible: use un secreto aleatorio.");
            }
        }
    }

    /** Nombre del claim con la huella de la credencial. */
    static final String CREDENTIAL_VERSION_CLAIM = "pwv";
    /** Caracteres Base64url de la huella (22 = 132 bits). */
    private static final int CREDENTIAL_VERSION_LENGTH = 22;

    @Override
    public String issue(UserId userId, SystemRole role) {
        return issue(userId, role, null);
    }

    @Override
    public String issue(UserId userId, SystemRole role, String credentialVersion) {
        Objects.requireNonNull(userId, "userId es obligatorio");
        Objects.requireNonNull(role, "role es obligatorio");
        var builder = JWT.create()
                .withIssuer(issuer)
                .withSubject(userId.value())
                .withClaim("role", role.getCode())
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusMillis(expirationMillis));
        if (credentialVersion != null) {
            builder.withClaim(CREDENTIAL_VERSION_CLAIM, credentialVersion);
        }
        return builder.sign(Algorithm.HMAC256(secret));
    }

    @Override
    public String credentialVersion(String passwordHash) {
        Objects.requireNonNull(passwordHash, "passwordHash es obligatorio");
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = hmac.doFinal(passwordHash.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, CREDENTIAL_VERSION_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no está disponible en esta JVM.", e);
        }
    }

    @Override
    public Optional<String> getCredentialVersion(String token) {
        try {
            DecodedJWT decoded = JWT.require(Algorithm.HMAC256(secret)).withIssuer(issuer).build().verify(token);
            return Optional.ofNullable(decoded.getClaim(CREDENTIAL_VERSION_CLAIM).asString());
        } catch (JWTVerificationException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isValid(String token) {
        return getUserIdInternal(token) != null;
    }

    @Override
    public Optional<UserId> getUserId(String token) {
        return Optional.ofNullable(getUserIdInternal(token));
    }

    private UserId getUserIdInternal(String token) {
        try {
            DecodedJWT decoded = JWT.require(Algorithm.HMAC256(secret))
                    .withIssuer(issuer)
                    .build()
                    .verify(token);
            return UserId.of(decoded.getSubject());
        } catch (JWTVerificationException | IllegalArgumentException ex) {
            return null;
        }
    }
}