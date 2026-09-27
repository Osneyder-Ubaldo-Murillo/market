package nexus.market.domain.ports.out;

import java.util.Optional;

import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Puerto de salida para la emisión y validación de tokens JWT.
 * Implementado por {@code adapters.out.security}.
 */
public interface JwtServicePort {

    String issue(UserId userId, SystemRole role);

    boolean isValid(String token);

    Optional<UserId> getUserId(String token);
}