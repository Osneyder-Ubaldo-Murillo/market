package nexus.market.domain.services;

import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Cart;
import nexus.market.domain.models.Product;
import nexus.market.domain.ports.out.BuyerRepositoryPort;
import nexus.market.domain.ports.out.BusinessConfigurationPort;
import nexus.market.domain.ports.out.CartRepositoryPort;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.specifications.ActiveBuyerSpecification;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.CartItem;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductStatus;
import nexus.market.domain.valueobjects.Quantity;

/**
 * Servicio de dominio del carrito de compras (SDD - Domain Services:
 * {@code CartService}). Solo compradores {@code ACTIVE} operan el carrito;
 * todos los ítems comparten una única moneda.
 */
public class CartService {

    private final CartRepositoryPort carts;
    private final BuyerRepositoryPort buyers;
    private final ProductRepositoryPort products;
    private final BusinessConfigurationPort config;
    private final ActiveBuyerSpecification activeBuyer;
    private final AuditService audit;

    public CartService(CartRepositoryPort carts, BuyerRepositoryPort buyers,
                       ProductRepositoryPort products, BusinessConfigurationPort config,
                       ActiveBuyerSpecification activeBuyer, AuditService audit) {
        this.carts = Objects.requireNonNull(carts, "carts es obligatorio");
        this.buyers = Objects.requireNonNull(buyers, "buyers es obligatorio");
        this.products = Objects.requireNonNull(products, "products es obligatorio");
        this.config = Objects.requireNonNull(config, "config es obligatorio");
        this.activeBuyer = Objects.requireNonNull(activeBuyer, "activeBuyer es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    /** Obtiene el carrito del comprador o lo crea (moneda fija al crearlo). */
    public Cart getOrCreateCart(BuyerId buyerId, Currency currency) {
        Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        Optional<Cart> existing = carts.findByBuyerId(buyerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Currency effectiveCurrency = currency != null ? currency : config.defaultCurrency();
        return carts.save(Cart.create(buyerId, effectiveCurrency));
    }

    public Optional<Cart> getCart(BuyerId buyerId) {
        return carts.findByBuyerId(buyerId);
    }

    /**
     * Agrega un ítem al carrito. Reglas: comprador activo, producto publicado
     * ({@code ACTIVE}) y moneda del ítem = moneda del carrito.
     */
    public void addItem(BuyerId buyerId, CartItem item) {
        Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        Objects.requireNonNull(item, "item es obligatorio");
        Buyer buyer = loadBuyer(buyerId);
        requireActive(buyer);

        Product product = products.findById(item.getProductId())
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente."));
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new BusinessException("PRODUCT_NOT_ACTIVE",
                    "No se puede agregar un producto no publicado.");
        }

        Cart cart = getOrCreateCart(buyerId, item.getUnitPrice().getCurrency());
        cart.addItem(item);
        carts.save(cart);
        audit.record(OperationType.CART_ADD_ITEM, AuditSeverity.INFO,
                buyerId.toString(), "Ítem agregado: " + item.getProductId());
    }

    /** Actualiza la cantidad de un ítem; una cantidad {@code ZERO} lo elimina. */
    public void updateQuantity(BuyerId buyerId, ProductId productId, Quantity newQuantity) {
        Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        Objects.requireNonNull(productId, "productId es obligatorio");
        Objects.requireNonNull(newQuantity, "newQuantity es obligatorio");
        Optional<Cart> optional = carts.findByBuyerId(buyerId);
        if (optional.isEmpty()) {
            return;
        }
        Cart cart = optional.get();
        cart.updateQuantity(productId, newQuantity);
        carts.save(cart);
    }

    /** Elimina un ítem del carrito. */
    public void removeItem(BuyerId buyerId, ProductId productId) {
        Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        Objects.requireNonNull(productId, "productId es obligatorio");
        Optional<Cart> optional = carts.findByBuyerId(buyerId);
        if (optional.isEmpty()) {
            return;
        }
        Cart cart = optional.get();
        cart.removeItem(productId);
        carts.save(cart);
        audit.record(OperationType.CART_REMOVE_ITEM, AuditSeverity.INFO,
                buyerId.toString(), "Ítem eliminado: " + productId);
    }

    /** Vacía el carrito. */
    public void clear(BuyerId buyerId) {
        Objects.requireNonNull(buyerId, "buyerId es obligatorio");
        Optional<Cart> optional = carts.findByBuyerId(buyerId);
        if (optional.isEmpty()) {
            return;
        }
        Cart cart = optional.get();
        cart.clear();
        carts.save(cart);
        audit.record(OperationType.CART_CLEAR, AuditSeverity.INFO,
                buyerId.toString(), "Carrito vaciado");
    }

    private Buyer loadBuyer(BuyerId buyerId) {
        return buyers.findById(buyerId)
                .orElseThrow(() -> new BusinessException("BUYER_NOT_FOUND", "Comprador inexistente."));
    }

    private void requireActive(Buyer buyer) {
        if (!activeBuyer.isSatisfiedBy(buyer)) {
            throw new BusinessException("INACTIVE_BUYER",
                    "El comprador no puede operar el carrito.");
        }
    }
}