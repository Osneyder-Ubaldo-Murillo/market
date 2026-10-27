package nexus.market.application;

import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.in.AuthUseCases;
import nexus.market.domain.ports.in.PasswordChangeUseCases;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.services.AuditService;
import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Cambio de contraseña del administrador autenticado (ver {@code docs/CAMBIO_CONTRASENA_PROPUESTA.md}).
 *
 * <ol>
 *   <li>Solo ADMIN y solo el propio usuario (el id viene del token, nunca del cliente).</li>
 *   <li>La nueva contraseña se valida con {@link AdminPasswordPolicy} y se cifra con BCrypt ANTES de escribir.</li>
 *   <li>Se reserva un intento en {@link PasswordAttemptLimiter} (atómico) antes de verificar la contraseña actual.</li>
 *   <li>Transacción {@code READ_COMMITTED}: se bloquea la fila de credenciales ({@code FOR UPDATE}), se verifica la
 *       actual contra el hash vigente y se reemplaza el hash. Dos cambios simultáneos con la misma contraseña actual: el
 *       segundo ve el hash nuevo, su contraseña actual ya no coincide y falla.</li>
 *   <li>La auditoría y el contador se resuelven FUERA de la transacción para que el fallo de un intento (que no escribe
 *       nada) no pierda su auditoría por el descarte de los eventos de una transacción revertida.</li>
 *   <li>Se emite un token nuevo con la huella de la credencial nueva; los anteriores dejan de coincidir (401).</li>
 * </ol>
 *
 * <p>Ni las contraseñas ni los hashes ni los tokens aparecen en mensajes, logs o auditoría.</p>
 */
@Service
@Profile("jpa")
public class PasswordChangeApplicationService implements PasswordChangeUseCases {

    private enum Outcome {
        CHANGED,
        WRONG_CURRENT,
        SAME_AS_CURRENT
    }

    private final UserRepositoryPort users;
    private final CredentialStorePort credentials;
    private final PasswordServicePort passwords;
    private final JwtServicePort jwt;
    private final AuditService audit;
    private final PlatformTransactionManager transactionManager;
    private final PasswordAttemptLimiter limiter;

    public PasswordChangeApplicationService(UserRepositoryPort users, CredentialStorePort credentials,
                                            PasswordServicePort passwords, JwtServicePort jwt, AuditService audit,
                                            PlatformTransactionManager transactionManager,
                                            PasswordAttemptLimiter limiter) {
        this.users = Objects.requireNonNull(users, "users es obligatorio");
        this.credentials = Objects.requireNonNull(credentials, "credentials es obligatorio");
        this.passwords = Objects.requireNonNull(passwords, "passwords es obligatorio");
        this.jwt = Objects.requireNonNull(jwt, "jwt es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager es obligatorio");
        this.limiter = Objects.requireNonNull(limiter, "limiter es obligatorio");
    }

    @Override
    public AuthUseCases.AuthResult changeAdminPassword(UserId userId, String currentPassword, String newPassword) {
        Objects.requireNonNull(userId, "userId es obligatorio");
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new BusinessException("INVALID_CURRENT_PASSWORD", "La contraseña actual no es correcta.");
        }

        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "Usuario inexistente."));
        if (user.getRole() != SystemRole.ADMIN) { // defensa en profundidad: la regla de seguridad ya exige ADMIN
            throw new AccessDeniedException("Solo un administrador puede usar este mecanismo.");
        }

        // Validar la nueva contraseña ANTES de reservar un intento y de escribir nada.
        try {
            AdminPasswordPolicy.validate(newPassword, user.getEmail().value());
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("PASSWORD_POLICY_VIOLATION",
                    "La nueva contraseña no cumple la política: " + invalid.getMessage());
        }

        limiter.acquire(userId.value()); // atómico, antes de verificar la actual (429 si se agotó la ventana)

        String newHash = passwords.hash(newPassword); // BCrypt fuera de la transacción: el bloqueo dura menos
        Outcome outcome = inTransaction(userId, currentPassword, newPassword, newHash);

        switch (outcome) {
            case WRONG_CURRENT -> {
                audit.record(OperationType.PASSWORD_CHANGE_FAILED, AuditSeverity.WARNING, userId.value(),
                        "Intento fallido de cambio de contraseña");
                throw new BusinessException("INVALID_CURRENT_PASSWORD", "La contraseña actual no es correcta.");
            }
            case SAME_AS_CURRENT -> throw new BusinessException("NEW_PASSWORD_SAME_AS_CURRENT",
                    "La nueva contraseña debe ser distinta de la actual.");
            case CHANGED -> {
                limiter.reset(userId.value());
                audit.record(OperationType.PASSWORD_CHANGE, AuditSeverity.INFO, userId.value(),
                        "Contraseña del administrador cambiada");
                String token = jwt.issue(userId, user.getRole(), jwt.credentialVersion(newHash));
                return new AuthUseCases.AuthResult(token, userId, user.getRole());
            }
            default -> throw new IllegalStateException("Resultado inesperado");
        }
    }

    private Outcome inTransaction(UserId userId, String currentPassword, String newPassword, String newHash) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> {
            Optional<String> stored = credentials.findByUserIdForUpdate(userId);
            if (stored.isEmpty() || !passwords.verify(currentPassword, stored.get())) {
                return Outcome.WRONG_CURRENT;
            }
            if (passwords.verify(newPassword, stored.get())) {
                return Outcome.SAME_AS_CURRENT;
            }
            credentials.replaceHash(userId, newHash);
            return Outcome.CHANGED;
        });
    }
}
