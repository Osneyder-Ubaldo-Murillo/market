package nexus.market.adapters.out.persistence.mysql;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.application.CredentialStorePort;
import nexus.market.domain.valueobjects.UserId;

/**
 * Entidad JPA de credenciales (tabla {@code user_credentials}): almacena el
 * hash BCrypt por usuario. Vive en el adaptador; el dominio no conoce
 * contraseñas.
 */
@Entity
@Table(name = "user_credentials")
class CredentialEntity {

    @Id
    @Column(name = "user_id", length = 36, updatable = false)
    String userId;

    @Column(name = "password_hash", length = 100, nullable = false)
    String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    LocalDateTime createdAt;

    protected CredentialEntity() {
    }
}

/**
 * Adaptador de salida {@link CredentialStorePort} sobre JPA (MySQL).
 * Solo se activa con el perfil {@code jpa} (ver
 * {@code application-jpa.properties}).
 */
@Component
@Profile("jpa")
class JpaCredentialStoreAdapter extends JpaRepositorySupport implements CredentialStorePort {

    JpaCredentialStoreAdapter(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    public void save(UserId userId, String passwordHash) {
        CredentialEntity entity = new CredentialEntity();
        entity.userId = userId.value();
        entity.passwordHash = passwordHash;
        entity.createdAt = LocalDateTime.now();
        persist(entity);
    }

    @Override
    public Optional<String> findByUserIdForUpdate(UserId userId) {
        requireTransactionalEntityManager();
        return findForUpdate(CredentialEntity.class, userId.value()).map(entity -> entity.passwordHash);
    }

    @Override
    public void replaceHash(UserId userId, String newPasswordHash) {
        CredentialEntity entity = requireTransactionalEntityManager()
                .find(CredentialEntity.class, userId.value(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) {
            throw new IllegalStateException("El usuario no tiene credenciales que reemplazar.");
        }
        entity.passwordHash = newPasswordHash;
        requireTransactionalEntityManager().flush();
    }

    @Override
    public Optional<String> findByUserId(UserId userId) {
        return find(CredentialEntity.class, userId.value())
                .map(entity -> entity.passwordHash);
    }
}
