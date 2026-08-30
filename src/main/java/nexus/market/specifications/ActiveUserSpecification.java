package com.nexusmarket.domain.specifications;

import com.nexusmarket.domain.models.User;
import com.nexusmarket.domain.valueobjects.UserStatus;

/**
 * Determina si un usuario está activo ({@code UserStatus.ACTIVE}).
 * Solo los usuarios activos pueden operar en la plataforma.
 */
public class ActiveUserSpecification {

    public boolean isSatisfiedBy(User user) {
        return user != null && user.getStatus() == UserStatus.ACTIVE;
    }
}