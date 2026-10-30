package nexus.market.adapters.in.rest.controllers;

import nexus.market.application.AccessPolicy;
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
import nexus.market.domain.ports.in.UserUseCases;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.UserId;

/**
 * Controlador REST de administración de usuarios: consulta, bloqueo,
 * activación, desactivación, aprobación de vendedores y direcciones de
 * compradores. La autorización por rol llegará con la tarea 5 (JWT).
 */
@RestController
@RequestMapping("/api")
@Profile("jpa")
public class UserController {

    private final UserUseCases userUseCases;
    private final AccessPolicy policy;

    public UserController(UserUseCases userUseCases, AccessPolicy policy) {
        this.userUseCases = userUseCases;
        this.policy = policy;
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<UserResponse> findUser(@PathVariable String userId) {
        policy.requireSelfOrAdminOrSupervisor(userId);
        return ResponseEntity.ok(RestMappers.user(userUseCases.findUser(UserId.of(userId))));
    }

    @PatchMapping("/users/{userId}/block")
    public ResponseEntity<MessageResponse> block(@PathVariable String userId) {
        userUseCases.blockUser(UserId.of(userId));
        return ResponseEntity.ok(new MessageResponse("Usuario bloqueado."));
    }

    @PatchMapping("/users/{userId}/activate")
    public ResponseEntity<MessageResponse> activate(@PathVariable String userId) {
        userUseCases.activateUser(UserId.of(userId));
        return ResponseEntity.ok(new MessageResponse("Usuario activado."));
    }

    @PatchMapping("/users/{userId}/deactivate")
    public ResponseEntity<MessageResponse> deactivate(@PathVariable String userId) {
        userUseCases.deactivateUser(UserId.of(userId));
        return ResponseEntity.ok(new MessageResponse("Usuario desactivado."));
    }

    @PostMapping("/sellers/{sellerId}/approve")
    public ResponseEntity<MessageResponse> approveSeller(@PathVariable String sellerId) {
        userUseCases.approveSeller(SellerId.of(sellerId));
        return ResponseEntity.ok(new MessageResponse("Vendedor aprobado."));
    }

    @PostMapping("/buyers/{buyerId}/addresses")
    public ResponseEntity<MessageResponse> addAddress(@PathVariable String buyerId,
                                                      @Valid @RequestBody AddressRequest address) {
        policy.requireOwnBuyer(buyerId);
        userUseCases.addBuyerAddress(BuyerId.of(buyerId), address.toDomain());
        return ResponseEntity.ok(new MessageResponse("Dirección agregada."));
    }
}
