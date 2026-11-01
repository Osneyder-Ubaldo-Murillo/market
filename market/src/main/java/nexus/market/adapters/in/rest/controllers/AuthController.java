package nexus.market.adapters.in.rest.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.requests.AuthRequests.ChangePasswordRequest;
import nexus.market.adapters.in.rest.security.ClientAddressResolver;
import nexus.market.adapters.in.rest.requests.AuthRequests.ForgotPasswordRequest;
import nexus.market.adapters.in.rest.requests.AuthRequests.LoginRequest;
import nexus.market.adapters.in.rest.requests.AuthRequests.ResendVerificationRequest;
import nexus.market.adapters.in.rest.requests.AuthRequests.ResetPasswordRequest;
import nexus.market.adapters.in.rest.requests.AuthRequests.VerifyEmailRequest;
import nexus.market.adapters.in.rest.responses.UserResponses.MessageResponse;
import nexus.market.adapters.in.rest.requests.AuthRequests.RegisterBuyerRequest;
import nexus.market.adapters.in.rest.requests.AuthRequests.RegisterSellerRequest;
import nexus.market.adapters.in.rest.responses.UserResponses.BuyerResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.SellerResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.TokenResponse;
import nexus.market.application.EmailVerificationLimiter;
import nexus.market.application.LoginAttemptLimiter;
import nexus.market.domain.ports.in.EmailVerificationUseCases;
import nexus.market.application.PasswordRecoveryLimiter;
import nexus.market.application.RegistrationAttemptLimiter;
import nexus.market.domain.ports.in.PasswordRecoveryUseCases;
import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Seller;
import nexus.market.domain.ports.in.AuthUseCases;
import nexus.market.domain.ports.in.PasswordChangeUseCases;
import nexus.market.domain.valueobjects.BusinessName;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.TaxId;
import nexus.market.domain.valueobjects.UserId;

/**
 * Controlador REST de autenticación: registro de compradores/vendedores con
 * contraseña y login (devuelve token JWT).
 */
@RestController
@RequestMapping("/api/auth")
@Profile("jpa")
public class AuthController {

    private final AuthUseCases authUseCases;
    private final PasswordChangeUseCases passwordChangeUseCases;
    private final LoginAttemptLimiter loginLimiter;
    private final RegistrationAttemptLimiter registrationLimiter;
    private final PasswordRecoveryLimiter recoveryLimiter;
    private final PasswordRecoveryUseCases recoveryUseCases;
    private final EmailVerificationLimiter verificationLimiter;
    private final EmailVerificationUseCases verificationUseCases;
    private final ClientAddressResolver clientAddresses;

    public AuthController(AuthUseCases authUseCases, PasswordChangeUseCases passwordChangeUseCases,
                          LoginAttemptLimiter loginLimiter, RegistrationAttemptLimiter registrationLimiter,
                          PasswordRecoveryLimiter recoveryLimiter, PasswordRecoveryUseCases recoveryUseCases,
                          EmailVerificationLimiter verificationLimiter,
                          EmailVerificationUseCases verificationUseCases, ClientAddressResolver clientAddresses) {
        this.clientAddresses = clientAddresses;
        this.verificationLimiter = verificationLimiter;
        this.verificationUseCases = verificationUseCases;
        this.authUseCases = authUseCases;
        this.passwordChangeUseCases = passwordChangeUseCases;
        this.loginLimiter = loginLimiter;
        this.registrationLimiter = registrationLimiter;
        this.recoveryLimiter = recoveryLimiter;
        this.recoveryUseCases = recoveryUseCases;
    }

    /** Registra un comprador con contraseña. Devuelve el perfil creado. */
    @PostMapping("/register/buyer")
    public ResponseEntity<BuyerResponse> registerBuyer(@Valid @RequestBody RegisterBuyerRequest request,
                                                       HttpServletRequest httpRequest) {
        // Registro público: limitado por email y por cliente (429) contra la creación masiva de cuentas.
        registrationLimiter.acquire(clientAddresses.resolve(httpRequest), request.email());
        Buyer buyer = authUseCases.registerBuyer(
                FullName.of(request.fullName()),
                Email.of(request.email()),
                DocumentId.of(request.documentId()),
                request.address().toDomain(),
                request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.buyer(buyer));
    }

    /** Registra un vendedor con contraseña (queda PENDING_VERIFICATION). */
    @PostMapping("/register/seller")
    public ResponseEntity<SellerResponse> registerSeller(@Valid @RequestBody RegisterSellerRequest request) {
        Seller seller = authUseCases.registerSeller(
                FullName.of(request.fullName()),
                Email.of(request.email()),
                DocumentId.of(request.documentId()),
                BusinessName.of(request.businessName()),
                TaxId.of(request.taxId()),
                request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.seller(seller));
    }

    /** Autentica y devuelve un token JWT. */
    /**
     * Rotación de la contraseña del administrador AUTENTICADO. El destino sale del token (nunca del cuerpo ni de la
     * ruta). Devuelve un token nuevo; los emitidos antes del cambio dejan de valer.
     */
    @PostMapping("/password")
    public ResponseEntity<TokenResponse> changePassword(Authentication authentication,
                                                        @Valid @RequestBody ChangePasswordRequest request) {
        AuthUseCases.AuthResult result = passwordChangeUseCases.changeAdminPassword(
                UserId.of(authentication.getName()), request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(new TokenResponse(
                result.token(), result.userId().value(), result.role().getCode()));
    }

    /**
     * Solicita un código de recuperación de contraseña. Responde siempre 202 con el mismo mensaje, exista o no el email.
     * Limitado por email y por cliente (429).
     */
    @PostMapping("/password/forgot")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                                          HttpServletRequest httpRequest) {
        recoveryLimiter.acquire(clientAddresses.resolve(httpRequest), request.email());
        recoveryUseCases.requestReset(Email.of(request.email()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MessageResponse(
                "Si el correo corresponde a una cuenta que puede recuperarse, recibirá un código de recuperación."));
    }

    /** Restablece la contraseña con el código recibido (válido 30 minutos y de un solo uso). */
    @PostMapping("/password/reset")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                                         HttpServletRequest httpRequest) {
        recoveryLimiter.acquireClient(clientAddresses.resolve(httpRequest));
        recoveryUseCases.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(new MessageResponse("Contraseña restablecida. Inicie sesión con la nueva contraseña."));
    }

    /** Verifica el correo con el código recibido; sin verificar no se puede iniciar sesión. */
    @PostMapping("/email/verify")
    public ResponseEntity<MessageResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
                                                       HttpServletRequest httpRequest) {
        verificationLimiter.acquireClient(clientAddresses.resolve(httpRequest));
        verificationUseCases.verify(request.token());
        return ResponseEntity.ok(new MessageResponse("Correo verificado. Ya puede iniciar sesión."));
    }

    /** Reenvía el código de verificación. Responde siempre 202 con el mismo mensaje. */
    @PostMapping("/email/resend")
    public ResponseEntity<MessageResponse> resendVerification(@Valid @RequestBody ResendVerificationRequest request,
                                                              HttpServletRequest httpRequest) {
        verificationLimiter.acquire(clientAddresses.resolve(httpRequest), request.email());
        verificationUseCases.resend(Email.of(request.email()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MessageResponse(
                "Si el correo corresponde a una cuenta pendiente de verificar, recibirá un código nuevo."));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        // La misma dirección para el límite, el bloqueo de cuentas y el reinicio del contador.
        String client = clientAddresses.resolve(httpRequest);
        // Intento reservado ANTES de verificar la contraseña (429 si se agotó el cupo del cliente o del email).
        loginLimiter.acquire(client, request.email());
        AuthUseCases.AuthResult result = authUseCases.login(Email.of(request.email()), request.password(), client);
        loginLimiter.reset(client, request.email());
        return ResponseEntity.ok(new TokenResponse(
                result.token(), result.userId().value(), result.role().getCode()));
    }
}
