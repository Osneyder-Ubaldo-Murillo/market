package nexus.market.adapters.in.rest.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nexus.market.application.AccessPolicy;
import nexus.market.application.IdentityDirectory;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.requests.CommonRequests.AddressRequest;
import nexus.market.adapters.in.rest.responses.UserResponses.MessageResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.UserResponse;
import nexus.market.domain.models.User;
import nexus.market.domain.ports.in.UserUseCases;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;

/**
 * Controlador REST de administración de usuarios: consulta, bloqueo,
 * activación, desactivación, aprobación de vendedores y direcciones de
 * compradores. La autorización por rol llegará con la tarea 5 (JWT).
 */
@Tag(name = "Usuarios", description = "Consulta y estado de las cuentas, aprobación de vendedores y direcciones del comprador.")
@RestController
@RequestMapping("/api")
@Profile("jpa")
public class UserController {

    private static final String REFERENCE_HELP = "{userId} acepta el UUID, el correo electrónico o el número de documento "
            + "de la cuenta (por ejemplo juan@ejemplo.com o 1020304050).";

    private final UserUseCases userUseCases;
    private final AccessPolicy policy;
    private final IdentityDirectory directory;

    public UserController(UserUseCases userUseCases, AccessPolicy policy, IdentityDirectory directory) {
        this.userUseCases = userUseCases;
        this.policy = policy;
        this.directory = directory;
    }

    /** ADMIN y SUPERVISOR localizan cualquier cuenta; el resto, solo la propia (sin revelar si otras existen). */
    private User readableUser(String reference) {
        if (policy.hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR)) {
            return userUseCases.findUserByReference(reference);
        }
        String current = policy.currentUserId().orElseThrow(
                () -> new org.springframework.security.access.AccessDeniedException("Acceso denegado."));
        User own = userUseCases.findUser(UserId.of(current));
        policy.requireOwnAccountReference(reference, own);
        return own;
    }

    @Operation(summary = "Consultar un usuario", description = "Cada usuario puede consultar su propia cuenta; ADMIN y SUPERVISOR, cualquiera. "
            + REFERENCE_HELP + " La respuesta incluye buyerId o sellerId (y businessName) para continuar con las demás operaciones.")
    @GetMapping("/users/{userId}")
    public ResponseEntity<UserResponse> findUser(@PathVariable String userId) {
        User user = readableUser(userId);
        return ResponseEntity.ok(RestMappers.user(user, directory.profile(user)));
    }

    @Operation(summary = "Bloquear una cuenta", description = "Solo ADMIN. " + REFERENCE_HELP)
    @PatchMapping("/users/{userId}/block")
    public ResponseEntity<MessageResponse> block(@PathVariable String userId) {
        userUseCases.blockUser(userUseCases.findUserByReference(userId).getUserId());
        return ResponseEntity.ok(new MessageResponse("Usuario bloqueado."));
    }

    @Operation(summary = "Reactivar (desbloquear) una cuenta", description = "Solo ADMIN. Es la única forma de reactivar una cuenta bloqueada. "
            + REFERENCE_HELP)
    @PatchMapping("/users/{userId}/activate")
    public ResponseEntity<MessageResponse> activate(@PathVariable String userId) {
        userUseCases.activateUser(userUseCases.findUserByReference(userId).getUserId());
        return ResponseEntity.ok(new MessageResponse("Usuario activado."));
    }

    @Operation(summary = "Desactivar una cuenta", description = "Solo ADMIN. " + REFERENCE_HELP)
    @PatchMapping("/users/{userId}/deactivate")
    public ResponseEntity<MessageResponse> deactivate(@PathVariable String userId) {
        userUseCases.deactivateUser(userUseCases.findUserByReference(userId).getUserId());
        return ResponseEntity.ok(new MessageResponse("Usuario desactivado."));
    }

    @Operation(summary = "Aprobar un vendedor", description = "Solo ADMIN. Cuerpo vacío ({}).")
    @PostMapping("/sellers/{sellerId}/approve")
    public ResponseEntity<MessageResponse> approveSeller(@PathVariable String sellerId) {
        userUseCases.approveSeller(SellerId.of(sellerId));
        return ResponseEntity.ok(new MessageResponse("Vendedor aprobado."));
    }

    @Operation(summary = "Agregar una dirección al comprador", description = "Solo COMPRADOR dueño.")
    @PostMapping("/buyers/{buyerId}/addresses")
    public ResponseEntity<MessageResponse> addAddress(@PathVariable String buyerId,
                                                      @Valid @RequestBody AddressRequest address) {
        policy.requireOwnBuyer(buyerId);
        userUseCases.addBuyerAddress(BuyerId.of(buyerId), address.toDomain());
        return ResponseEntity.ok(new MessageResponse("Dirección agregada."));
    }
}
