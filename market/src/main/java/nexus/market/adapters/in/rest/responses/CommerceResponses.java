package nexus.market.adapters.in.rest.responses;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs de respuesta del flujo de compra: carrito, pedidos, pagos, facturas,
 * envíos, devoluciones y reembolsos.
 */
public final class CommerceResponses {

    private CommerceResponses() {
    }

    /** Ítem de carrito o de pedido con sus montos. */
    public record ItemResponse(String productId, String productName, int quantity,
                               UserResponses.MoneyResponse unitPrice,
                               UserResponses.MoneyResponse total, String type) {
    }

    /** Carrito con moneda y total. */
    public record CartResponse(String cartId, String buyerId, String currency,
                               List<ItemResponse> items, UserResponses.MoneyResponse total) {
    }

    /** Pedido completo con ciclo de vida. */
    public record OrderResponse(String orderId, String buyerId, List<ItemResponse> items, String status,
                                UserResponses.MoneyResponse total,
                                UserResponses.AddressResponse shippingAddress,
                                String invoiceId, LocalDateTime deliveredAt, LocalDateTime createdAt) {
    }

    /**
     * Vista de un pedido para un vendedor: solo sus ítems y el subtotal de
     * ellos; no incluye comprador, dirección de entrega ni factura.
     */
    public record SellerOrderResponse(String orderId, List<ItemResponse> items, String status,
                                      UserResponses.MoneyResponse total,
                                      LocalDateTime deliveredAt, LocalDateTime createdAt) {
    }

    /** Confirmación de cobro devuelta por la pasarela. */
    public record PaymentResponse(String transactionId, LocalDateTime date, String paymentMethod) {
    }

    /** Factura emitida. */
    public record InvoiceResponse(String invoiceId, String orderId, String buyerId, String sellerId,
                                  List<ItemResponse> items, UserResponses.MoneyResponse total,
                                  LocalDateTime issueDate, String status) {
    }

    /** Envío con número de guía y transportadora. */
    public record ShippingResponse(String shippingId, String orderId, String warehouseId,
                                   String trackingNumber, String carrier, LocalDate estimatedDate,
                                   String status) {
    }

    /** Devolución solicitada. */
    public record ReturnResponse(String returnId, String orderId, List<ItemResponse> items,
                                 String status, LocalDateTime requestDate, LocalDateTime resolutionDate) {
    }

    /** Reembolso asociado a una devolución. */
    public record RefundResponse(String refundId, String returnId, String orderId,
                                 UserResponses.MoneyResponse amount, String status,
                                 LocalDateTime requestDate, LocalDateTime processedDate) {
    }
}
