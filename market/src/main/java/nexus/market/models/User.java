package nexus.market.domain.models;

import java.time.LocalDateTime;
import java.util.Objects;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Agregado raíz que representa a cualquier persona autorizada para
 * interactuar con el sistema. Cada usuario tiene un único rol inmutable
 * (RG-02).
 *
 * <p>Transiciones de {@code status}:
 * {@code ACTIVE ↔ BLOCKED}, {@code ACTIVE → INACTIVE}.</p>
 */
public class User {

    private final UserId userId;
    private final FullName fullName;
    private final Email email;
    private final DocumentId documentId;
    private final SystemRole role;
    private UserStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public User(UserId userId, FullName fullName, Email email, DocumentId documentId,
                SystemRole role, UserStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.userId = Objects.requireNonNull(userId, "userId es obligatorio");
        this.fullName = Objects.requireNonNull(fullName, "fullName es obligatorio");
        this.email = Objects.requireNonNull(email, "email es obligatorio");
        this.documentId = Objects.requireNonNull(documentId, "documentId es obligatorio");
        this.role = Objects.requireNonNull(role, "role es obligatorio");
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
    }

    /**
     * Crea un usuario nuevo en estado {@code ACTIVE} con identificador generado.
     */
    public static User create(FullName fullName, Email email, DocumentId documentId, SystemRole role) {
        LocalDateTime now = LocalDateTime.now();
        return new User(UserId.generate(), fullName, email, documentId, role,
                UserStatus.ACTIVE, now, now);
    }

    /**
     * {@code ACTIVE → BLOCKED}. Solo un usuario activo puede ser bloqueado.
     */
    public void block() {
        requireActive();
        this.status = UserStatus.BLOCKED;
        touch();
    }

    /**
     * {@code BLOCKED → ACTIVE}. Solo un usuario bloqueado puede reactivarse.
     */
    public void activate() {
        if (status != UserStatus.BLOCKED) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Solo un usuario BLOCKED puede volver al estado ACTIVE.");
        }
        this.status = UserStatus.ACTIVE;
        touch();
    }

    /**
     * {@code ACTIVE → INACTIVE}.
     */
    public void deactivate() {
        requireActive();
        this.status = UserStatus.INACTIVE;
        touch();
    }

    private void requireActive() {
        if (status != UserStatus.ACTIVE) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "La operación requiere un usuario en estado ACTIVE.");
        }
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public UserId getUserId() {
        return userId;
    }

    public FullName getFullName() {
        return fullName;
    }

    public Email getEmail() {
        return email;
    }

    public DocumentId getDocumentId() {
        return documentId;
    }

    public SystemRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}