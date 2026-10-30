package nexus.market.adapters.in.rest.controllers;

import nexus.market.application.AccessPolicy;
import java.util.ArrayList;
import java.util.List;

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
import nexus.market.adapters.in.rest.requests.ReturnRequests.CreateReturnRequest;
import nexus.market.adapters.in.rest.responses.CommerceResponses.RefundResponse;
import nexus.market.adapters.in.rest.responses.CommerceResponses.ReturnResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.MessageResponse;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.ports.in.ReturnRefundUseCases;
import nexus.market.domain.ports.in.ReturnRefundUseCases.ReturnRequestLine;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.RefundId;
import nexus.market.domain.valueobjects.ReturnId;
import nexus.market.domain.valueobjects.ReturnReason;

/**
 * Controlador REST de devoluciones y reembolsos: solicitud (pedido DELIVERED
 * dentro del plazo), aprobación/rechazo, procesamiento de la devolución y
 * proceso del reembolso contra la pasarela.
 */
@RestController
@RequestMapping("/api")
@Profile("jpa")
public class ReturnController {

    private final ReturnRefundUseCases returnUseCases;
    private final AccessPolicy policy;

    public ReturnController(ReturnRefundUseCases returnUseCases, AccessPolicy policy) {
        this.returnUseCases = returnUseCases;
        this.policy = policy;
    }

    @PostMapping("/orders/{orderId}/returns")
    public ResponseEntity<ReturnResponse> request(@PathVariable String orderId,
                                                  @Valid @RequestBody CreateReturnRequest request) {
        policy.requireOwnOrder(orderId);
        List<ReturnRequestLine> lines = new ArrayList<>();
        if (request.items() != null) {
            for (var line : request.items()) {
                lines.add(new ReturnRequestLine(ProductId.of(line.productId()),
                        Quantity.of(line.quantity()), returnReason(line.reason())));
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestMappers.returnOrder(returnUseCases.requestReturn(OrderId.of(orderId), lines)));
    }

    @GetMapping("/returns/{returnId}")
    public ResponseEntity<ReturnResponse> findById(@PathVariable String returnId) {
        policy.requireReturnReadable(returnId);
        return ResponseEntity.ok(
                RestMappers.returnOrder(returnUseCases.findById(ReturnId.of(returnId))));
    }

    /** Aprueba la devolución y crea el reembolso (devuelve el reembolso). */
    @PostMapping("/returns/{returnId}/approve")
    public ResponseEntity<RefundResponse> approve(@PathVariable String returnId) {
        return ResponseEntity.ok(RestMappers.refund(
                returnUseCases.approveReturn(ReturnId.of(returnId))));
    }

    @PostMapping("/returns/{returnId}/reject")
    public ResponseEntity<MessageResponse> reject(@PathVariable String returnId) {
        returnUseCases.rejectReturn(ReturnId.of(returnId));
        return ResponseEntity.ok(new MessageResponse("Devolución rechazada."));
    }

    @PostMapping("/returns/{returnId}/process")
    public ResponseEntity<MessageResponse> process(@PathVariable String returnId) {
        returnUseCases.processReturn(ReturnId.of(returnId));
        return ResponseEntity.ok(new MessageResponse("Devolución procesada."));
    }

    @PostMapping("/refunds/{refundId}/process")
    public ResponseEntity<MessageResponse> processRefund(@PathVariable String refundId) {
        returnUseCases.processRefund(RefundId.of(refundId));
        return ResponseEntity.ok(new MessageResponse("Reembolso procesado."));
    }

    private static ReturnReason returnReason(String code) {
        if (code == null) {
            throw new BusinessException("INVALID_RETURN_REASON",
                    "El motivo de devolución es obligatorio.");
        }
        return switch (code.trim().toUpperCase()) {
            case "DAMAGED" -> ReturnReason.damaged();
            case "WRONG_ITEM" -> ReturnReason.wrongItem();
            case "NOT_AS_DESCRIBED" -> ReturnReason.notAsDescribed();
            case "OTHER" -> ReturnReason.other();
            default -> throw new BusinessException("INVALID_RETURN_REASON",
                    "Motivo de devolución desconocido: " + code);
        };
    }
}
