package nexus.market.adapters.in.rest.controllers;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Envíos", description = "Envío de un pedido. Lo gestiona el operador logístico.")
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

    @Operation(summary = "Crear el envío de un pedido", description = "Solo el OPERADOR logístico asignado. Un pedido tiene un solo envío y exige todo lo físico recogido y sin novedades abiertas.")
    @ApiResponse(responseCode = "201", description = "Envío creado.")
    @PostMapping("/orders/{orderId}/shippings")
    public ResponseEntity<ShippingResponse> create(@PathVariable String orderId,
                                                   @Valid @RequestBody DispatchOrderRequest request) {
        policy.requireAssignedOperatorOfOrder(orderId);
        return ResponseEntity.status(HttpStatus.CREATED).body(RestMappers.shipping(
                shippingUseCases.createShipping(OrderId.of(orderId), WarehouseId.of(request.warehouseId()))));
    }

    @Operation(summary = "Consultar el envío de un pedido", description = "COMPRADOR, VENDEDOR, OPERADOR, ADMIN y SUPERVISOR.")
    @GetMapping("/orders/{orderId}/shipping")
    public ResponseEntity<ShippingResponse> findByOrderId(@PathVariable String orderId) {
        policy.readableOrder(orderId);
        return ResponseEntity.ok(RestMappers.shipping(shippingUseCases.findByOrderId(OrderId.of(orderId))
                .orElseThrow(() -> new BusinessException("SHIPPING_NOT_FOUND", "Envío inexistente."))));
    }

    @Operation(summary = "Consultar un envío", description = "COMPRADOR, VENDEDOR, OPERADOR, ADMIN y SUPERVISOR.")
    @GetMapping("/shippings/{shippingId}")
    public ResponseEntity<ShippingResponse> findById(@PathVariable String shippingId) {
        policy.requireShippingReadable(shippingId);
        return ResponseEntity.ok(
                RestMappers.shipping(shippingUseCases.findById(ShippingId.of(shippingId))));
    }

    @Operation(summary = "Despachar un envío con guía", description = "Solo el OPERADOR logístico asignado. Exige todo lo físico recogido y sin novedades abiertas.")
    @PostMapping("/shippings/{shippingId}/dispatch")
    public ResponseEntity<MessageResponse> dispatch(@PathVariable String shippingId,
                                                    @Valid @RequestBody DispatchShippingRequest request) {
        policy.requireAssignedOperatorOfShipping(shippingId);
        // carrier es obligatorio en el VO; si no llega guía, la transportadora la genera.
        shippingUseCases.dispatchShipping(ShippingId.of(shippingId),
                DeliveryInfo.of(request.trackingNumber(), request.carrier(), null));
        return ResponseEntity.ok(new MessageResponse("Envío despachado."));
    }

    @Operation(summary = "Marcar el envío en tránsito", description = "Solo OPERADOR logístico. Cuerpo vacío ({}).")
    @PostMapping("/shippings/{shippingId}/in-transit")
    public ResponseEntity<MessageResponse> inTransit(@PathVariable String shippingId) {
        policy.requireAssignedOperatorOfShipping(shippingId);
        shippingUseCases.markInTransit(ShippingId.of(shippingId));
        return ResponseEntity.ok(new MessageResponse("Envío en tránsito."));
    }

    @Operation(summary = "Entregar el envío", description = "Solo el OPERADOR logístico asignado al pedido. Marca también el pedido como "
            + "entregado. Cuerpo opcional con observaciones de la entrega ({\"notes\": \"...\"}). No avanza si hay novedades sin "
            + "resolver. Queda en el historial con el operador y la hora; no se guarda evidencia digital (foto, firma, ubicación).")
    @PostMapping("/shippings/{shippingId}/deliver")
    public ResponseEntity<MessageResponse> deliver(@PathVariable String shippingId,
                                                   @Valid @RequestBody(required = false) nexus.market.adapters.in.rest.requests.LogisticsRequests.DeliveryRequest request) {
        policy.requireAssignedOperatorOfShipping(shippingId);
        shippingUseCases.deliver(ShippingId.of(shippingId), request == null ? null : request.notes());
        return ResponseEntity.ok(new MessageResponse("Envío entregado."));
    }
}
