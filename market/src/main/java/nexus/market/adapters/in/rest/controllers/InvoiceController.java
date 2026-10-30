package nexus.market.adapters.in.rest.controllers;

import nexus.market.application.AccessPolicy;
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

    /** Facturas del comprador autenticado, paginadas (más recientes primero). Total en X-Total-Count. */
    @GetMapping("/buyers/{buyerId}/invoices")
    public ResponseEntity<java.util.List<InvoiceResponse>> listByBuyer(
            @PathVariable String buyerId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        policy.requireOwnBuyer(buyerId);
        nexus.market.adapters.in.rest.PageParams params = nexus.market.adapters.in.rest.PageParams.of(page, size);
        nexus.market.domain.valueobjects.BuyerId id = nexus.market.domain.valueobjects.BuyerId.of(buyerId);
        return ResponseEntity.ok()
                .header(nexus.market.adapters.in.rest.PageParams.TOTAL_COUNT_HEADER,
                        String.valueOf(invoiceUseCases.countByBuyer(id)))
                .body(invoiceUseCases.findByBuyer(id, params.offset(), params.size()).stream()
                        .map(RestMappers::invoice).toList());
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
