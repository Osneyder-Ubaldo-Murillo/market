package nexus.market.adapters.in.rest.controllers;

import nexus.market.application.AccessPolicy;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.requests.CartRequests.AddCartItemRequest;
import nexus.market.adapters.in.rest.requests.CommonRequests.QuantityRequest;
import nexus.market.adapters.in.rest.responses.CommerceResponses.CartResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.MessageResponse;
import nexus.market.domain.ports.in.CartUseCases;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.ProductId;

/**
 * Controlador REST del carrito: consulta/creación, agregado de ítems,
 * actualización de cantidad (0 elimina), eliminación individual y vaciado.
 */
@RestController
@RequestMapping("/api/buyers/{buyerId}/cart")
@Profile("jpa")
public class CartController {

    private final CartUseCases cartUseCases;
    private final AccessPolicy policy;

    public CartController(CartUseCases cartUseCases, AccessPolicy policy) {
        this.cartUseCases = cartUseCases;
        this.policy = policy;
    }

    /** Devuelve el carrito existente o lo crea con la moneda por defecto. */
    @GetMapping
    public ResponseEntity<CartResponse> getOrCreate(@PathVariable String buyerId) {
        policy.requireOwnBuyer(buyerId);
        return ResponseEntity.ok(RestMappers.cart(
                cartUseCases.getOrCreateCart(BuyerId.of(buyerId))));
    }

    @PostMapping("/items")
    public ResponseEntity<MessageResponse> addItem(@PathVariable String buyerId,
                                                   @Valid @RequestBody AddCartItemRequest request) {
        policy.requireOwnBuyer(buyerId);
        cartUseCases.addItem(BuyerId.of(buyerId), ProductId.of(request.productId()),
                nexus.market.domain.valueobjects.Quantity.of(request.quantity()));
        return ResponseEntity.ok(new MessageResponse("Ítem agregado al carrito."));
    }

    /** Cantidad {@code 0} elimina el ítem (regla del dominio). */
    @PutMapping("/items/{productId}")
    public ResponseEntity<MessageResponse> updateQuantity(@PathVariable String buyerId,
                                                          @PathVariable String productId,
                                                          @Valid @RequestBody QuantityRequest request) {
        policy.requireOwnBuyer(buyerId);
        cartUseCases.updateQuantity(BuyerId.of(buyerId), ProductId.of(productId), request.toDomain());
        return ResponseEntity.ok(new MessageResponse("Cantidad actualizada."));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<MessageResponse> removeItem(@PathVariable String buyerId,
                                                      @PathVariable String productId) {
        policy.requireOwnBuyer(buyerId);
        cartUseCases.removeItem(BuyerId.of(buyerId), ProductId.of(productId));
        return ResponseEntity.ok(new MessageResponse("Ítem eliminado."));
    }

    @DeleteMapping
    public ResponseEntity<MessageResponse> clear(@PathVariable String buyerId) {
        policy.requireOwnBuyer(buyerId);
        cartUseCases.clear(BuyerId.of(buyerId));
        return ResponseEntity.ok(new MessageResponse("Carrito vaciado."));
    }
}
