package nexus.market.adapters.in.rest.controllers;

import nexus.market.application.AccessPolicy;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.requests.OrderRequests.DispatchOrderRequest;
import nexus.market.adapters.in.rest.requests.ShippingRequests.DispatchShippingRequest;
import nexus.market.adapters.in.rest.responses.CommerceResponses.ShippingResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.MessageResponse;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.ports.in.ShippingUseCases;
import nexus.market.domain.valueobjects.DeliveryInfo;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ShippingId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Controlador REST de envíos: creación (solo pedidos PAID), despacho con
 * información de guía, tránsito, entrega y consultas.
 *
 * <p>Al despachar, si no se envía número de guía, la transportadora simulada
 * lo genera (regla de {@code ShippingService.dispatchShipping}).</p>
 */
@RestController
@RequestMapping("/api")
@Profile("jpa")
public class ShippingController {

    private final ShippingUseCases shippingUseCases;
    private final AccessPolicy policy;

    public ShippingController(ShippingUseCases shippingUseCases, AccessPolicy policy) {
        this.shippingUseCases = shippingUseCases;
        this.policy = policy;
    }

    @PostMapping("/orders/{orderId}/shippings")
    public ResponseEntity<ShippingResponse> create(@PathVariable String orderId,
                                                   @Valid @RequestBody DispatchOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.shipping(
                shippingUseCases.createShipping(OrderId.of(orderId), WarehouseId.of(request.warehouseId()))));
    }

    @GetMapping("/orders/{orderId}/shipping")
    public ResponseEntity<ShippingResponse> findByOrderId(@PathVariable String orderId) {
        policy.readableOrder(orderId);
        return ResponseEntity.ok(RestMappers.shipping(shippingUseCases.findByOrderId(OrderId.of(orderId))
                .orElseThrow(() -> new BusinessException("SHIPPING_NOT_FOUND", "Envío inexistente."))));
    }

    @GetMapping("/shippings/{shippingId}")
    public ResponseEntity<ShippingResponse> findById(@PathVariable String shippingId) {
        policy.requireShippingReadable(shippingId);
        return ResponseEntity.ok(
                RestMappers.shipping(shippingUseCases.findById(ShippingId.of(shippingId))));
    }

    @PostMapping("/shippings/{shippingId}/dispatch")
    public ResponseEntity<MessageResponse> dispatch(@PathVariable String shippingId,
                                                    @Valid @RequestBody DispatchShippingRequest request) {
        // carrier es obligatorio en el VO; si no llega guía, la transportadora la genera.
        shippingUseCases.dispatchShipping(ShippingId.of(shippingId),
                DeliveryInfo.of(request.trackingNumber(), request.carrier(), null));
        return ResponseEntity.ok(new MessageResponse("Envío despachado."));
    }

    @PostMapping("/shippings/{shippingId}/in-transit")
    public ResponseEntity<MessageResponse> inTransit(@PathVariable String shippingId) {
        shippingUseCases.markInTransit(ShippingId.of(shippingId));
        return ResponseEntity.ok(new MessageResponse("Envío en tránsito."));
    }

    @PostMapping("/shippings/{shippingId}/deliver")
    public ResponseEntity<MessageResponse> deliver(@PathVariable String shippingId) {
        shippingUseCases.deliver(ShippingId.of(shippingId));
        return ResponseEntity.ok(new MessageResponse("Envío entregado."));
    }
}
