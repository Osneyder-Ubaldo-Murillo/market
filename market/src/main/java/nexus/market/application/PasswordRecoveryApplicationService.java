package nexus.market.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.enums.NotificationChannel;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.in.PasswordRecoveryUseCases;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.NotificationPort;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.services.AuditService;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Recuperación de contraseña sin estado nuevo en la base de datos: el código es un JWT firmado, de 30 minutos, ligado a la
 * huella de la credencial vigente ({@code pwv}). Al restablecer la contraseña la huella cambia, así que el mismo código
 * no se puede usar dos veces y todos los tokens de acceso anteriores quedan invalidados.
 *
 * <ul>
 *   <li>No revela si un email existe: la solicitud termina igual en todos los casos.</li>
 *   <li>Excluye al ADMIN (su recuperación es un procedimiento manual) y a cuentas no activas.</li>
 *   <li>El código se entrega por {@link NotificationPort}. <b>Con el adaptador de log actual el código queda escrito en el
 *       log de la aplicación</b>: es aceptable en un entorno académico y debe sustituirse por un canal real (correo/SMS)
 *       antes de usarlo con usuarios reales.</li>
 *   <li>La contraseña nueva se cifra con BCrypt fuera de la transacción; la fila de credenciales se bloquea
 *       ({@code FOR UPDATE}) mientras se compara la huella y se reemplaza el hash.</li>
 * </ul>
 */
@Service
@Profile("jpa")
public class PasswordRecoveryApplicationService implements PasswordRecoveryUseCases {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryApplicationService.class);

    /** Vigencia del código de recuperación. */
    public static final Duration TOKEN_VALIDITY = Duration.ofMinutes(30);
    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_BYTES = AuthApplicationService.MAX_PASSWORD_BYTES;

    private final UserRepositoryPort users;
    private final CredentialStorePort credentials;
    private final PasswordServicePort passwords;
    private final JwtServicePort jwt;
    private final NotificationPort notifications;
    private final AuditService audit;
    private final PlatformTransactionManager transactionManager;

    public PasswordRecoveryApplicationService(UserRepositoryPort users, CredentialStorePort credentials,
                                              PasswordServicePort passwords, JwtServicePort jwt,
                                              NotificationPort notifications, AuditService audit,
                                              PlatformTransactionManager transactionManager) {
        this.users = Objects.requireNonNull(users, "users es obligatorio");
        this.credentials = Objects.requireNonNull(credentials, "credentials es obligatorio");
        this.passwords = Objects.requireNonNull(passwords, "passwords es obligatorio");
        this.jwt = Objects.requireNonNull(jwt, "jwt es obligatorio");
        this.notifications = Objects.requireNonNull(notifications, "notifications es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager es obligatorio");
    }

    @Override
    public void requestReset(Email email) {
        Objects.requireNonNull(email, "email es obligatorio");
        try {
            Optional<User> user = users.findByEmail(email).filter(PasswordRecoveryApplicationService::eligible);
            Optional<String> hash = user.flatMap(u -> credentials.findByUserId(u.getUserId()));
            if (user.isEmpty() || hash.isEmpty()) {
                return; // misma respuesta que si hubiera enviado el código
            }
            String token = jwt.issuePasswordResetToken(user.get().getUserId(), jwt.credentialVersion(hash.get()),
                    TOKEN_VALIDITY);
            notifications.send(NotificationChannel.EMAIL, email.getValue(), "Recuperación de contraseña",
                    "Use este código para restablecer su contraseña. Vence en " + TOKEN_VALIDITY.toMinutes()
                            + " minutos y sirve una sola vez: " + token);
            audit.record(OperationType.PASSWORD_RESET_REQUEST, AuditSeverity.INFO, user.get().getUserId().value(),
                    "Código de recuperación de contraseña emitido");
        } catch (RuntimeException ex) {
            // La respuesta no debe distinguir un fallo interno de "el email no existe"; el detalle va solo al log.
            log.warn("No se pudo procesar una solicitud de recuperación de contraseña: {}", ex.getClass().getSimpleName());
        }
    }

    @Override
    public void resetPassword(String token, String newPassword) {
        String valid = requireValidPassword(newPassword);
        JwtServicePort.PasswordResetClaims claims = jwt.parsePasswordResetToken(token == null ? "" : token)
                .orElseThrow(PasswordRecoveryApplicationService::invalidToken);
        User user = users.findById(claims.userId()).filter(PasswordRecoveryApplicationService::eligible)
                .orElseThrow(PasswordRecoveryApplicationService::invalidToken);

        String newHash = passwords.hash(valid); // BCrypt fuera de la transacción: el bloqueo dura menos
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        boolean changed = Boolean.TRUE.equals(tx.execute(status -> {
            Optional<String> stored = credentials.findByUserIdForUpdate(user.getUserId());
            if (stored.isEmpty() || !sameVersion(claims.credentialVersion(), jwt.credentialVersion(stored.get()))) {
                return false; // la contraseña ya cambió desde que se emitió el código
            }
            credentials.replaceHash(user.getUserId(), newHash);
            return true;
        }));
        if (!changed) {
            throw invalidToken();
        }
        audit.record(OperationType.PASSWORD_RESET, AuditSeverity.INFO, user.getUserId().value(),
                "Contraseña restablecida con código de recuperación");
    }

    /** Cuentas que pueden recuperarse por este camino: activas y que no sean ADMIN. */
    private static boolean eligible(User user) {
        return user.getStatus() == UserStatus.ACTIVE && user.getRole() != SystemRole.ADMIN;
    }

    private static boolean sameVersion(String fromToken, String current) {
        return MessageDigest.isEqual(fromToken.getBytes(StandardCharsets.UTF_8),
                current.getBytes(StandardCharsets.UTF_8));
    }

    private static String requireValidPassword(String password) {
        if (password == null || password.isBlank()) {
            throw new BusinessException("PASSWORD_REQUIRED", "La contraseña es obligatoria.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("La contraseña debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException(
                    "La contraseña no puede superar " + MAX_PASSWORD_BYTES + " bytes en UTF-8.");
        }
        return password;
    }

    private static BusinessException invalidToken() {
        return new BusinessException("INVALID_RESET_TOKEN",
                "El código de recuperación no es válido, venció o ya se usó.");
    }
}
