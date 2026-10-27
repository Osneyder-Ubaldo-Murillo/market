package nexus.market.application;

import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Cart;
import nexus.market.domain.models.Product;
import nexus.market.domain.ports.in.CartUseCases;
import nexus.market.domain.ports.out.BusinessConfigurationPort;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.services.CartService;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.CartItem;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.Quantity;

/**
 * Servicio de aplicación del carrito: delega en {@code CartService} y resuelve
 * el ítem del dominio (nombre, precio y tipo) desde el producto publicado,
 * porque el cliente solo envía {@code productId} y cantidad.
 */
@Service
@Profile("jpa")
public class CartApplicationService implements CartUseCases {

    private final CartService delegate;
    private final ProductRepositoryPort products;
    private final BusinessConfigurationPort config;

    public CartApplicationService(CartService delegate, ProductRepositoryPort products,
                                  BusinessConfigurationPort config) {
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
        this.products = Objects.requireNonNull(products, "products es obligatorio");
        this.config = Objects.requireNonNull(config, "config es obligatorio");
    }

    @Override
    public Cart getOrCreateCart(BuyerId buyerId) {
        return delegate.getOrCreateCart(buyerId, config.defaultCurrency());
    }

    @Override
    public Optional<Cart> getCart(BuyerId buyerId) {
        return delegate.getCart(buyerId);
    }

    @Override
    public void addItem(BuyerId buyerId, ProductId productId, Quantity quantity) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente."));
        CartItem item = CartItem.of(product.getProductId(), product.getName(),
                quantity, product.getPrice(), product.getProductType());
        delegate.addItem(buyerId, item);
    }

    @Override
    public void updateQuantity(BuyerId buyerId, ProductId productId, Quantity newQuantity) {
        delegate.updateQuantity(buyerId, productId, newQuantity);
    }

    @Override
    public void removeItem(BuyerId buyerId, ProductId productId) {
        delegate.removeItem(buyerId, productId);
    }

    @Override
    public void clear(BuyerId buyerId) {
        delegate.clear(buyerId);
    }
}
