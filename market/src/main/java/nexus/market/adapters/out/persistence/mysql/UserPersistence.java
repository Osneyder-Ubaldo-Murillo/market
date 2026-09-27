package nexus.market.adapters.out.persistence.mysql;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Entidad JPA de {@link User} (tabla {@code users}).
 * Vive en el adaptador; el dominio nunca conoce esta clase.
 */
@Entity
@Table(name = "users")
class UserEntity {

    @Id
    @Column(name = "id", length = 36, updatable = false)
    String id;

    @Column(name = "full_name", length = 120, nullable = false)
    String fullName;

    @Column(name = "email", length = 160, nullable = false, unique = true)
    String email;

    @Column(name = "document_id", length = 40, nullable = false, unique = true)
    String documentId;

    @Column(name = "role", length = 30, nullable = false)
    String role;

    @Column(name = "status", length = 30, nullable = false)
    String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    LocalDateTime updatedAt;

    protected UserEntity() {
    }

    static UserEntity from(User user) {
        UserEntity entity = new UserEntity();
        entity.id = user.getUserId().value();
        entity.fullName = user.getFullName().value();
        entity.email = user.getEmail().value();
        entity.documentId = user.getDocumentId().value();
        entity.role = user.getRole().getCode();
        entity.status = user.getStatus().getCode();
        entity.createdAt = user.getCreatedAt();
        entity.updatedAt = user.getUpdatedAt();
        return entity;
    }

    User toDomain() {
        JpaCatalogCodecs catalogs = JpaCatalogCodecs.INSTANCE;
        return new User(UserId.of(id), FullName.of(fullName), Email.of(email),
                DocumentId.of(documentId), catalogs.systemRole(role),
                catalogs.userStatus(status), createdAt, updatedAt);
    }
}
/**
 * Adaptador de salida {@link UserRepositoryPort} sobre JPA (MySQL).
 * Solo se activa con el perfil {@code jpa} (ver {@code application-jpa.properties}).
 */
@Component
@Profile("jpa")
class JpaUserRepositoryPort extends JpaRepositorySupport implements UserRepositoryPort {

    JpaUserRepositoryPort(jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public User save(User user) {
        return persist(UserEntity.from(user)).toDomain();
    }

    @Override
    public Optional<User> findById(UserId userId) {
        return find(UserEntity.class, userId.value()).map(UserEntity::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return querySingle(UserEntity.class,
                "select u from UserEntity u where u.email = :email", Map.of("email", email.value()))
                .map(UserEntity::toDomain);
    }

    @Override
    public boolean existsByEmail(Email email) {
        return findByEmail(email).isPresent();
    }

    @Override
    public boolean existsByDocumentId(DocumentId documentId) {
        return querySingle(UserEntity.class,
                "select u from UserEntity u where u.documentId = :doc", Map.of("doc", documentId.value()))
                .isPresent();
    }
}