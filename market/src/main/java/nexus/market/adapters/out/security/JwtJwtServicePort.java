package nexus.market.adapters.out.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import org.springframework.beans.factory.annotation.Value;
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

    private final String secret;
    private final String issuer;
    private final long expirationMillis;

    public JwtJwtServicePort(@Value("${jwt.secret}") String secret,
                             @Value("${jwt.issuer:nexusmarket}") String issuer,
                             @Value("${jwt.expiration:86400000}") long expirationMillis) {
        if (secret == null || secret.isBlank() || secret.length() < 16) {
            throw new IllegalStateException(
                    "jwt.secret debe configurarse con al menos 16 caracteres (ver application.properties).");
        }
        this.secret = secret;
        this.issuer = issuer;
        this.expirationMillis = expirationMillis;
    }

    @Override
    public String issue(UserId userId, SystemRole role) {
        Objects.requireNonNull(userId, "userId es obligatorio");
        Objects.requireNonNull(role, "role es obligatorio");
        return JWT.create()
                .withIssuer(issuer)
                .withSubject(userId.value())
                .withClaim("role", role.getCode())
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plusMillis(expirationMillis))
                .sign(Algorithm.HMAC256(secret));
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