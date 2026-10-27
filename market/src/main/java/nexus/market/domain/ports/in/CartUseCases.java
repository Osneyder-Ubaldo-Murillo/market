package nexus.market.domain.ports.in;

import java.util.Optional;

import nexus.market.domain.models.Cart;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;

/**
 * Puerto de entrada (caso de uso) del carrito de compras. Delega en
 * {@code CartService}. El precio y el nombre del ítem se resuelven desde el
 * producto publicado (el cliente solo envía {@code productId} y cantidad).
 */
public interface CartUseCases {

    /** Devuelve el carrito existente o lo crea con la moneda por defecto. */
    Cart getOrCreateCart(BuyerId buyerId);

    Optional<Cart> getCart(BuyerId buyerId);

    void addItem(BuyerId buyerId, ProductId productId, Quantity quantity);

    void updateQuantity(BuyerId buyerId, ProductId productId, Quantity newQuantity);

    void removeItem(BuyerId buyerId, ProductId productId);

    void clear(BuyerId buyerId);
}
