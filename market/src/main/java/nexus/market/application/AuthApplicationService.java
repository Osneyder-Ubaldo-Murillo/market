package nexus.market.application;

import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Seller;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.in.AuthUseCases;
import nexus.market.domain.ports.out.JwtServicePort;
import nexus.market.domain.ports.out.PasswordServicePort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.services.UserManagementService;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BusinessName;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.TaxId;
import nexus.market.domain.valueobjects.UserStatus;

/**
 * Servicio de aplicación de autenticación: orquesta el registro (dominio) con
 * el almacén de credenciales (hash BCrypt) y la emisión de tokens JWT.
 *
 * <p>Esta capa es la única que conoce simultáneamente el dominio y los
 * puertos de seguridad; el dominio sigue sin saber nada de contraseñas.</p>
 */
@Service
@Profile("jpa")
public class AuthApplicationService implements AuthUseCases {

    /** Límite de BCrypt (bytes UTF-8). */
    static final int MAX_PASSWORD_BYTES = 72;

    private final UserManagementService userManagement;
    private final UserRepositoryPort users;
    private final CredentialStorePort credentials;
    private final PasswordServicePort passwordService;
    private final JwtServicePort jwtService;

    public AuthApplicationService(UserManagementService userManagement,
                                  UserRepositoryPort users,
                                  CredentialStorePort credentials,
                                  PasswordServicePort passwordService,
                                  JwtServicePort jwtService) {
        this.userManagement = Objects.requireNonNull(userManagement, "userManagement es obligatorio");
        this.users = Objects.requireNonNull(users, "users es obligatorio");
        this.credentials = Objects.requireNonNull(credentials, "credentials es obligatorio");
        this.passwordService = Objects.requireNonNull(passwordService, "passwordService es obligatorio");
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService es obligatorio");
    }

    @Override
    @Transactional
    public Buyer registerBuyer(FullName fullName, Email email, DocumentId documentId,
                               Address mainAddress, String rawPassword) {
        // Se valida y cifra la contraseña ANTES de escribir nada: una entrada inválida no toca la base.
        String hash = passwordService.hash(requireHashablePassword(rawPassword));
        // Usuario + perfil + credenciales: una sola transacción (todo o nada).
        Buyer buyer = userManagement.registerBuyer(fullName, email, documentId, mainAddress);
        credentials.save(buyer.getUserId(), hash);
        return buyer;
    }

    @Override
    @Transactional
    public Seller registerSeller(FullName fullName, Email email, DocumentId documentId,
                                 BusinessName businessName, TaxId taxId, String rawPassword) {
        String hash = passwordService.hash(requireHashablePassword(rawPassword));
        Seller seller = userManagement.registerSeller(fullName, email, documentId, businessName, taxId);
        credentials.save(seller.getUserId(), hash);
        return seller;
    }

    @Override
    public AuthResult login(Email email, String rawPassword) {
        Objects.requireNonNull(email, "email es obligatorio");
        User user = users.findByEmail(email)
                .orElseThrow(() -> invalidCredentials());
        String storedHash = credentials.findByUserId(user.getUserId())
                .orElseThrow(() -> invalidCredentials());
        if (!passwordService.verify(requirePassword(rawPassword), storedHash)) {
            throw invalidCredentials();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException("USER_NOT_ACTIVE",
                    "El usuario no está activo; contacte al administrador.");
        }
        // El token lleva la huella de la credencial vigente: al cambiar la contraseña, los anteriores dejan de valer.
        String token = jwtService.issue(user.getUserId(), user.getRole(), jwtService.credentialVersion(storedHash));
        return new AuthResult(token, user.getUserId(), user.getRole());
    }

    /** BCrypt solo procesa 72 bytes: se rechaza (400), nunca se trunca en silencio. */
    private static String requireHashablePassword(String rawPassword) {
        String password = requirePassword(rawPassword);
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException(
                    "La contraseña no puede superar " + MAX_PASSWORD_BYTES + " bytes en UTF-8.");
        }
        return password;
    }

    private static String requirePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException("PASSWORD_REQUIRED", "La contraseña es obligatoria.");
        }
        return rawPassword;
    }

    /** Un mismo mensaje evita filtrar si el email existe o no. */
    private static BusinessException invalidCredentials() {
        return new BusinessException("INVALID_CREDENTIALS", "Credenciales inválidas.");
    }
}
