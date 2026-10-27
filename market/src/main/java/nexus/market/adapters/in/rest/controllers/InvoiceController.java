package nexus.market.adapters.in.rest.controllers;

import nexus.market.adapters.in.rest.security.AccessPolicy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nexus.market.adapters.in.rest.mappers.RestMappers;
import nexus.market.adapters.in.rest.responses.CommerceResponses.InvoiceResponse;
import nexus.market.adapters.in.rest.responses.UserResponses.MessageResponse;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.ports.in.InvoiceUseCases;
import nexus.market.domain.valueobjects.InvoiceId;
import nexus.market.domain.valueobjects.OrderId;

/**
 * Controlador REST de facturación: emisión, anulación y consulta (por factura
 * o por pedido).
 */
@RestController
@RequestMapping("/api")
@Profile("jpa")
public class InvoiceController {

    private final InvoiceUseCases invoiceUseCases;
    private final AccessPolicy policy;

    public InvoiceController(InvoiceUseCases invoiceUseCases, AccessPolicy policy) {
        this.invoiceUseCases = invoiceUseCases;
        this.policy = policy;
    }

    @PostMapping("/orders/{orderId}/invoice")
    public ResponseEntity<InvoiceResponse> generate(@PathVariable String orderId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestMappers.invoice(invoiceUseCases.generateInvoice(OrderId.of(orderId))));
    }

    @GetMapping("/orders/{orderId}/invoice")
    public ResponseEntity<InvoiceResponse> findByOrderId(@PathVariable String orderId) {
        policy.requireOrderInvoiceReadable(orderId);
        return ResponseEntity.ok(RestMappers.invoice(invoiceUseCases.findByOrderId(OrderId.of(orderId))
                .orElseThrow(() -> new BusinessException("INVOICE_NOT_FOUND", "Factura inexistente."))));
    }

    @GetMapping("/invoices/{invoiceId}")
    public ResponseEntity<InvoiceResponse> findById(@PathVariable String invoiceId) {
        policy.requireInvoiceReadable(invoiceId);
        return ResponseEntity.ok(
                RestMappers.invoice(invoiceUseCases.findById(InvoiceId.of(invoiceId))));
    }

    @PostMapping("/invoices/{invoiceId}/cancel")
    public ResponseEntity<MessageResponse> cancel(@PathVariable String invoiceId) {
        invoiceUseCases.cancelInvoice(InvoiceId.of(invoiceId));
        return ResponseEntity.ok(new MessageResponse("Factura anulada."));
    }
}
