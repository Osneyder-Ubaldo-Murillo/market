package nexus.market.domain.specifications;

import nexus.market.domain.models.User;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Determina si un usuario está activo ({@code UserStatus.ACTIVE}).
 * Solo los usuarios activos pueden operar en la plataforma.
 */
public class ActiveUserSpecification {

    public boolean isSatisfiedBy(User user) {
        return user != null && user.getStatus() == UserStatus.ACTIVE;
    }
}