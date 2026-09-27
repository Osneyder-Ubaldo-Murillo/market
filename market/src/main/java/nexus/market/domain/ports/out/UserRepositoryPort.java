package nexus.market.domain.ports.out;

import java.util.Optional;

import nexus.market.domain.models.User;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.UserId;

/**
 * Puerto de salida para la persistencia de {@link User}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface UserRepositoryPort {

    User save(User user);

    Optional<User> findById(UserId userId);

    Optional<User> findByEmail(Email email);

    boolean existsByEmail(Email email);

    boolean existsByDocumentId(DocumentId documentId);
}