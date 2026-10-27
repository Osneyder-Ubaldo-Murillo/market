package nexus.market.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import nexus.market.domain.models.User;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Aprovisionamiento del administrador inicial (ver {@code docs/ADMIN_INICIAL_PROPUESTA.md}). Es la
 * ÚNICA ruta de código que crea administradores: cualquier otro procedimiento debe pasar por aquí o usar el
 * mismo candado ({@link AdminProvisioningLockPort#LOCK_NAME}).
 *
 * <p>Capas de protección:</p>
 * <ol>
 *   <li>comprobación rápida sin candado: si ya existe un ADMIN no se hace nada ni se lee la contraseña;</li>
 *   <li>contraseña validada y cifrada ANTES de tomar el candado y de abrir la transacción;</li>
 *   <li>candado entre instancias ({@code GET_LOCK}) tomado antes de la transacción y liberado siempre;</li>
 *   <li>transacción {@code REPEATABLE_READ} explícita (nunca la {@code READ_COMMITTED} de pedidos e
 *       inventario: la sentencia condicional NO es segura bajo READ COMMITTED) con una sola sentencia
 *       {@code INSERT ... SELECT ... WHERE NOT EXISTS}, seguida de las credenciales;</li>
 *   <li>identificador determinista y únicos de correo/documento como barreras adicionales;</li>
 *   <li>un deadlock (1213) o conflicto de bloqueo se interpreta como "intento concurrente": se vuelve a
 *       comprobar y, si ya hay administrador, se sale sin error.</li>
 * </ol>
 */
public class InitialAdminService {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminService.class);

    /** Id determinista del administrador inicial. */
    public static final UserId INITIAL_ADMIN_ID = UserId.of(
            UUID.nameUUIDFromBytes("nexusmarket:initial-admin".getBytes(StandardCharsets.UTF_8)).toString());

    public enum Result {
        CREATED,
        ALREADY_EXISTS
    }

    private final InitialAdminStorePort store;
    private final AdminProvisioningLockPort lock;
    private final PasswordServicePort passwords;
    private final PlatformTransactionManager transactionManager;

    public InitialAdminService(InitialAdminStorePort store, AdminProvisioningLockPort lock,
                               PasswordServicePort passwords, PlatformTransactionManager transactionManager) {
        this.store = Objects.requireNonNull(store, "store es obligatorio");
        this.lock = Objects.requireNonNull(lock, "lock es obligatorio");
        this.passwords = Objects.requireNonNull(passwords, "passwords es obligatorio");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager es obligatorio");
    }

    /**
     * @param password proveedor de la contraseña, invocado solo si hace falta (no se conserva)
     */
    public Result provision(FullName fullName, Email email, DocumentId documentId, Supplier<String> password) {
        Objects.requireNonNull(fullName, "fullName es obligatorio");
        Objects.requireNonNull(email, "email es obligatorio");
        Objects.requireNonNull(documentId, "documentId es obligatorio");
        Objects.requireNonNull(password, "password es obligatorio");

        // 1. Ya hay un administrador (cualquier estado): no se hace nada, ni siquiera se lee la contraseña.
        if (store.existsAnyAdmin()) {
            return Result.ALREADY_EXISTS;
        }
        // 2. Validar y cifrar ANTES de tomar el candado y de abrir la transacción.
        String rawPassword = password.get();
        try {
            AdminPasswordPolicy.validate(rawPassword, email.value());
        } catch (IllegalArgumentException invalid) {
            throw new InitialAdminException("La contraseña del administrador inicial no cumple la política: "
                    + invalid.getMessage());
        }
        String hash = passwords.hash(rawPassword);

        // 3. Candado entre instancias; la transacción se abre DENTRO del candado.
        try {
            return lock.withLock(() -> insertInTransaction(fullName, email, documentId, hash));
        } catch (RuntimeException failure) {
            if (isConcurrencyConflict(failure) && store.existsAnyAdmin()) {
                log.info("Otro intento concurrente ya creó un administrador; no se crea otro.");
                return Result.ALREADY_EXISTS;
            }
            throw failure;
        }
    }

    private Result insertInTransaction(FullName fullName, Email email, DocumentId documentId, String hash) {
        LocalDateTime now = LocalDateTime.now();
        User admin = new User(INITIAL_ADMIN_ID, fullName, email, documentId,
                SystemRole.ADMIN, UserStatus.ACTIVE, now, now);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        // REPEATABLE_READ explícito: la sentencia condicional no es segura bajo READ_COMMITTED.
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        InitialAdminStorePort.Outcome outcome = tx.execute(status -> store.insertIfNoAdmin(admin, hash));
        return outcome == InitialAdminStorePort.Outcome.CREATED ? Result.CREATED : Result.ALREADY_EXISTS;
    }

    /** Deadlock (1213 / 40001) o espera de bloqueo agotada (1205) en la cadena de causas. */
    static boolean isConcurrencyConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException sql
                    && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205 || "40001".equals(sql.getSQLState()))) {
                return true;
            }
            if (t instanceof jakarta.persistence.PessimisticLockException
                    || t instanceof jakarta.persistence.LockTimeoutException
                    || t instanceof org.hibernate.exception.LockAcquisitionException) {
                return true;
            }
        }
        return false;
    }
}
