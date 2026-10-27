package nexus.market.adapters.in.rest.controllers;

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
import nexus.market.adapters.in.rest.requests.AuthRequests.LoginRequest;
import nexus.market.adapters.in.rest.requests.AuthRequests.RegisterBuyerRequest;
import nexus.market.adapters.in.rest.requests.AuthRequests.RegisterSellerRequest;
import nexus.market.adapters.in.rest.responses.UserResponses.BuyerResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.SellerResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.TokenResponse;
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

    public AuthController(AuthUseCases authUseCases, PasswordChangeUseCases passwordChangeUseCases) {
        this.authUseCases = authUseCases;
        this.passwordChangeUseCases = passwordChangeUseCases;
    }

    /** Registra un comprador con contraseña. Devuelve el perfil creado. */
    @PostMapping("/register/buyer")
    public ResponseEntity<BuyerResponse> registerBuyer(@Valid @RequestBody RegisterBuyerRequest request) {
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

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthUseCases.AuthResult result = authUseCases.login(Email.of(request.email()), request.password());
        return ResponseEntity.ok(new TokenResponse(
                result.token(), result.userId().value(), result.role().getCode()));
    }
}
