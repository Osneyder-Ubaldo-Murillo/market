package nexus.market.adapters.in.rest.mappers;

import java.util.List;

import nexus.market.adapters.in.rest.responses.CatalogResponses;
import nexus.market.adapters.in.rest.responses.CommerceResponses;
import nexus.market.adapters.in.rest.responses.UserResponses;
import nexus.market.application.IdentityDirectory;

/**
 * Mapeador estático dominio → DTO de respuesta. Toda conversión hacia/desde
 * el dominio vive en los adaptadores (restricción 4 de la SDD); los DTOs nunca
 * exponen tipos del dominio y viceversa.
 */
public final class RestMappers {

    private RestMappers() {
    }

    // ------------------------------------------------------------------
    // Dinero y usuarios
    // ------------------------------------------------------------------

    public static UserResponses.MoneyResponse money(nexus.market.domain.valueobjects.Money money) {
        if (money == null) {
            return null;
        }
        return new UserResponses.MoneyResponse(money.getAmount(), money.getCurrency().getCurrencyCode());
    }

    public static UserResponses.UserResponse user(nexus.market.domain.models.User user) {
        return user(user, IdentityDirectory.ProfileRefs.NONE);
    }

    /** Cuenta con los identificadores de su perfil (buyerId o sellerId y nombre comercial). */
    public static UserResponses.UserResponse user(nexus.market.domain.models.User user, IdentityDirectory.ProfileRefs profile) {
        return new UserResponses.UserResponse(
                user.getUserId().value(),
                user.getFullName().getValue(),
                user.getEmail().getValue(),
                user.getDocumentId().getValue(),
                user.getRole().getCode(),
                user.getStatus().getCode(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                profile.buyerId(),
                profile.sellerId(),
                profile.businessName());
    }

    public static UserResponses.BuyerResponse buyer(nexus.market.domain.models.Buyer buyer) {
        List<UserResponses.AddressResponse> additional = buyer.getAdditionalAddresses().stream()
                .map(UserResponses.AddressResponse::of)
                .toList();
        return new UserResponses.BuyerResponse(
                buyer.getBuyerId().value(),
                buyer.getUserId().value(),
                UserResponses.AddressResponse.of(buyer.getMainAddress()),
                additional,
                buyer.getCommercialStatus().getCode());
    }

    public static UserResponses.SellerResponse seller(nexus.market.domain.models.Seller seller) {
        return new UserResponses.SellerResponse(
                seller.getSellerId().value(),
                seller.getUserId().value(),
                seller.getBusinessName().getValue(),
                seller.getTaxId().getValue(),
                seller.getStatus().getCode());
    }

    // ------------------------------------------------------------------
    // Catálogo e inventario
    // ------------------------------------------------------------------

    public static CatalogResponses.ProductResponse product(nexus.market.domain.models.Product product) {
        return product(product, null);
    }

    public static CatalogResponses.ProductResponse product(nexus.market.domain.models.Product product, String sellerName) {
        return new CatalogResponses.ProductResponse(
                product.getProductId().value(),
                product.getSellerId().value(),
                product.getName().getValue(),
                product.getDescription().getValue(),
                product.getProductType().getCode(),
                money(product.getPrice()),
                product.getStatus().getCode(),
                product.getSpecifications(),
                product.getCreatedAt(),
                product.getSellerSku() == null ? null : product.getSellerSku().getValue(),
                sellerName);
    }

    public static CatalogResponses.InventoryResponse inventory(nexus.market.domain.models.Inventory inventory) {
        return new CatalogResponses.InventoryResponse(
                inventory.getInventoryId().value(),
                inventory.getProductId().value(),
                inventory.getWarehouseId().value(),
                inventory.getQuantity().getValue(),
                inventory.getReservedQuantity().getValue(),
                inventory.getAvailableQuantity().getValue(),
                inventory.getStatus().getCode(),
                inventory.getUpdatedAt());
    }

    public static CatalogResponses.WarehouseResponse warehouse(nexus.market.domain.models.Warehouse warehouse) {
        return new CatalogResponses.WarehouseResponse(
                warehouse.getWarehouseId().value(),
                warehouse.getName(),
                UserResponses.AddressResponse.of(warehouse.getAddress()),
                warehouse.getType().getCode(),
                warehouse.getSellerId() == null ? null : warehouse.getSellerId().value(),
                warehouse.getStatus().getCode());
    }

    // ------------------------------------------------------------------
    // Flujo de compra
    // ------------------------------------------------------------------

    public static CommerceResponses.CartResponse cart(nexus.market.domain.models.Cart cart) {
        return cart(cart, java.util.Map.of(), null);
    }

    /** Carrito con el vendedor y el código de cada producto y el nombre y correo del comprador (dueño del carrito). */
    public static CommerceResponses.CartResponse cart(nexus.market.domain.models.Cart cart,
                                                      java.util.Map<String, IdentityDirectory.ProductLabel> labels,
                                                      IdentityDirectory.PersonLabel buyer) {
        return new CommerceResponses.CartResponse(
                cart.getCartId().value(),
                cart.getBuyerId().value(),
                cart.getCurrency().getCurrencyCode(),
                cart.getItems().stream().map(item -> cartItem(item, labels.get(item.getProductId().value()))).toList(),
                money(cart.getTotal()),
                buyer == null ? null : buyer.fullName(),
                buyer == null ? null : buyer.email());
    }

    public static CommerceResponses.ItemResponse cartItem(nexus.market.domain.valueobjects.CartItem item) {
        return cartItem(item, null);
    }

    public static CommerceResponses.ItemResponse cartItem(nexus.market.domain.valueobjects.CartItem item,
                                                          IdentityDirectory.ProductLabel label) {
        return new CommerceResponses.ItemResponse(
                item.getProductId().value(),
                item.getProductName().getValue(),
                item.getQuantity().getValue(),
                money(item.getUnitPrice()),
                money(item.getTotal()),
                item.getProductType().getCode(),
                label == null ? null : label.sellerSku(),
                label == null ? null : label.sellerName(),
                label == null ? null : label.sellerId());
    }

    public static CommerceResponses.ItemResponse orderItem(nexus.market.domain.valueobjects.OrderItem item) {
        return orderItem(item, null);
    }

    public static CommerceResponses.ItemResponse orderItem(nexus.market.domain.valueobjects.OrderItem item,
                                                           IdentityDirectory.ProductLabel label) {
        return new CommerceResponses.ItemResponse(
                item.getProductId().value(),
                item.getProductName().getValue(),
                item.getQuantity().getValue(),
                money(item.getUnitPrice()),
                money(item.getTotal()),
                item.getProductType().getCode(),
                label == null ? null : label.sellerSku(),
                label == null ? null : label.sellerName(),
                label == null ? null : label.sellerId());
    }

    public static CommerceResponses.OrderResponse order(nexus.market.domain.models.Order order) {
        return order(order, java.util.Map.of(), null, false);
    }

    /** Pedido con vendedor y código de cada línea y el nombre del comprador; el correo solo si {@code showBuyerEmail}. */
    public static CommerceResponses.OrderResponse order(nexus.market.domain.models.Order order,
                                                        java.util.Map<String, IdentityDirectory.ProductLabel> labels,
                                                        IdentityDirectory.PersonLabel buyer, boolean showBuyerEmail) {
        return new CommerceResponses.OrderResponse(
                order.getOrderId().value(),
                order.getBuyerId().value(),
                order.getItems().stream().map(item -> orderItem(item, labels.get(item.getProductId().value()))).toList(),
                order.getStatus().getCode(),
                money(order.getTotal()),
                UserResponses.AddressResponse.of(order.getShippingAddress()),
                order.getInvoiceId() == null ? null : order.getInvoiceId().value(),
                order.getDeliveredAt(),
                order.getCreatedAt(),
                buyer == null ? null : buyer.fullName(),
                buyer == null || !showBuyerEmail ? null : buyer.email(),
                order.getOrderCode() == null ? null : order.getOrderCode().value());
    }

    /** Vista restringida del pedido: solo los ítems del vendedor y su subtotal. */
    public static CommerceResponses.SellerOrderResponse orderForSeller(
            nexus.market.domain.models.Order order,
            List<nexus.market.domain.valueobjects.OrderItem> sellerItems) {
        return orderForSeller(order, sellerItems, java.util.Map.of());
    }

    public static CommerceResponses.SellerOrderResponse orderForSeller(
            nexus.market.domain.models.Order order,
            List<nexus.market.domain.valueobjects.OrderItem> sellerItems,
            java.util.Map<String, IdentityDirectory.ProductLabel> labels) {
        nexus.market.domain.valueobjects.Money subtotal = sellerItems.stream()
                .map(nexus.market.domain.valueobjects.OrderItem::getTotal)
                .reduce(nexus.market.domain.valueobjects.Money::add)
                .orElse(null);
        return new CommerceResponses.SellerOrderResponse(
                order.getOrderId().value(),
                sellerItems.stream().map(item -> orderItem(item, labels.get(item.getProductId().value()))).toList(),
                order.getStatus().getCode(),
                money(subtotal),
                order.getDeliveredAt(),
                order.getCreatedAt(),
                order.getOrderCode() == null ? null : order.getOrderCode().value());
    }

    public static CommerceResponses.PaymentResponse payment(
            nexus.market.domain.valueobjects.PaymentConfirmation confirmation) {
        return new CommerceResponses.PaymentResponse(
                confirmation.getTransactionId(),
                confirmation.getDate(),
                confirmation.getPaymentMethod());
    }

    public static CommerceResponses.InvoiceResponse invoice(nexus.market.domain.models.Invoice invoice) {
        return new CommerceResponses.InvoiceResponse(
                invoice.getInvoiceId().value(),
                invoice.getOrderId().value(),
                invoice.getBuyerId().value(),
                invoice.getSellerId().value(),
                invoice.getItems().stream().map(RestMappers::orderItem).toList(),
                money(invoice.getTotal()),
                invoice.getIssueDate(),
                invoice.getStatus().getCode());
    }

    public static CommerceResponses.ShippingResponse shipping(nexus.market.domain.models.Shipping shipping) {
        nexus.market.domain.valueobjects.DeliveryInfo info = shipping.getDeliveryInfo();
        return new CommerceResponses.ShippingResponse(
                shipping.getShippingId().value(),
                shipping.getOrderId().value(),
                shipping.getWarehouseId().value(),
                info == null ? null : info.getTrackingNumber(),
                info == null ? null : info.getCarrier(),
                info == null ? null : info.getEstimatedDate(),
                shipping.getStatus().getCode());
    }

    public static CommerceResponses.ReturnResponse returnOrder(nexus.market.domain.models.Return aReturn) {
        List<CommerceResponses.ItemResponse> items = aReturn.getItems().stream()
                .map(item -> new CommerceResponses.ItemResponse(
                        item.getProductId().value(),
                        item.getProductName().getValue(),
                        item.getQuantity().getValue(),
                        null,
                        null,
                        item.getReason().getCode()))
                .toList();
        return new CommerceResponses.ReturnResponse(
                aReturn.getReturnId().value(),
                aReturn.getOrderId().value(),
                items,
                aReturn.getStatus().getCode(),
                aReturn.getRequestDate(),
                aReturn.getResolutionDate());
    }

    public static CommerceResponses.RefundResponse refund(nexus.market.domain.models.Refund refund) {
        return new CommerceResponses.RefundResponse(
                refund.getRefundId().value(),
                refund.getReturnId().value(),
                refund.getOrderId().value(),
                money(refund.getAmount()),
                refund.getStatus().getCode(),
                refund.getRequestDate(),
                refund.getProcessedDate());
    }
}
