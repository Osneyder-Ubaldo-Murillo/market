package nexus.market.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.enums.NotificationChannel;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.in.EmailVerificationUseCases;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.NotificationPort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.services.AuditService;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.UserId;

/**
 * Verificación del correo con un código sin estado nuevo: un JWT firmado de 24 horas ligado al usuario. Verificar es
 * idempotente (se conserva la primera fecha) y no depende de que el código sea de un solo uso: lo único que concede es
 * poder iniciar sesión con la contraseña que ya se conoce.
 *
 * <p>El código se entrega por {@link NotificationPort} <b>después de confirmar la transacción</b> de registro (si el
 * registro se revierte no se envía nada). <b>Con el adaptador de log actual el código queda escrito en el log</b>:
 * aceptable en un entorno académico; sustitúyalo por un canal real antes de usarlo con usuarios reales.</p>
 */
@Service
@Profile("jpa")
public class EmailVerificationApplicationService implements EmailVerificationUseCases {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationApplicationService.class);

    /** Vigencia del código de verificación. */
    public static final Duration TOKEN_VALIDITY = Duration.ofHours(24);

    private final UserRepositoryPort users;
    private final JwtServicePort jwt;
    private final NotificationPort notifications;
    private final AuditService audit;

    public EmailVerificationApplicationService(UserRepositoryPort users, JwtServicePort jwt,
                                               NotificationPort notifications, AuditService audit) {
        this.users = Objects.requireNonNull(users, "users es obligatorio");
        this.jwt = Objects.requireNonNull(jwt, "jwt es obligatorio");
        this.notifications = Objects.requireNonNull(notifications, "notifications es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    @Override
    public void sendVerificationCode(UserId userId, Email email) {
        Objects.requireNonNull(userId, "userId es obligatorio");
        Objects.requireNonNull(email, "email es obligatorio");
        String token = jwt.issueEmailVerificationToken(userId, TOKEN_VALIDITY);
        AfterCommit.run(() -> {
            try {
                notifications.send(NotificationChannel.EMAIL, email.getValue(), "Verifique su correo",
                        "Use este código para verificar su correo y poder iniciar sesión. Vence en "
                                + TOKEN_VALIDITY.toHours() + " horas: " + token);
            } catch (RuntimeException ex) {
                log.warn("No se pudo enviar el código de verificación: {}", ex.getClass().getSimpleName());
            }
        });
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void verify(String token) {
        UserId userId = jwt.parseEmailVerificationToken(token == null ? "" : token)
                .orElseThrow(EmailVerificationApplicationService::invalidToken);
        User user = users.findById(userId).orElseThrow(EmailVerificationApplicationService::invalidToken);
        if (user.isEmailVerified()) {
            return; // idempotente
        }
        user.verifyEmail();
        users.save(user);
        audit.record(OperationType.EMAIL_VERIFICATION, AuditSeverity.INFO, userId.value(), "Correo verificado");
    }

    @Override
    public void resend(Email email) {
        Objects.requireNonNull(email, "email es obligatorio");
        try {
            Optional<User> pending = users.findByEmail(email).filter(user -> !user.isEmailVerified());
            pending.ifPresent(user -> sendVerificationCode(user.getUserId(), user.getEmail()));
        } catch (RuntimeException ex) {
            // La respuesta no debe distinguir un fallo interno de "el correo no existe o ya está verificado".
            log.warn("No se pudo reenviar el código de verificación: {}", ex.getClass().getSimpleName());
        }
    }

    private static BusinessException invalidToken() {
        return new BusinessException("INVALID_VERIFICATION_TOKEN",
                "El código de verificación no es válido o venció.");
    }
}
