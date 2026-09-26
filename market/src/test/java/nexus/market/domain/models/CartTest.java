package nexus.market.domain.models;

import java.math.BigDecimal;
import java.util.Currency;

import org.junit.jupiter.api.Test;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.CartItem;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.Quantity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba el agregado {@code Cart}: alta/baja de ítems, fusión de cantidades,
 * recálculo de total y unicidad de moneda.
 */
class CartTest {

    private static final Currency USD = Currency.getInstance("USD");

    private static CartItem item(String name, double price, int quantity) {
        return CartItem.of(ProductId.generate(), ProductName.of(name),
                Quantity.of(quantity), Money.of(price, "USD"), ProductType.PHYSICAL);
    }

    @Test
    void carritoNuevoTieneTotalCero() {
        Cart cart = Cart.create(BuyerId.generate(), USD);
        assertTrue(cart.getTotal().compareTo(Money.zero(USD)) == 0);
        assertTrue(cart.getItems().isEmpty());
    }

    @Test
    void agregarItemsRecalculaElTotal() {
        Cart cart = Cart.create(BuyerId.generate(), USD);
        cart.addItem(item("Teclado", 25.50, 2));   // 51.00
        cart.addItem(item("Ratón", 12.25, 1));     // 12.25
        assertEquals(0, cart.getTotal().compareTo(Money.of(new BigDecimal("63.25"), USD)));
    }

    @Test
    void agregarElMismoProductoFusionaCantidades() {
        Cart cart = Cart.create(BuyerId.generate(), USD);
        ProductId productId = ProductId.generate();
        cart.addItem(CartItem.of(productId, ProductName.of("Monitor"), Quantity.of(1),
                Money.of(100.00, "USD"), ProductType.PHYSICAL));
        cart.addItem(CartItem.of(productId, ProductName.of("Monitor"), Quantity.of(2),
                Money.of(100.00, "USD"), ProductType.PHYSICAL));

        assertEquals(1, cart.getItems().size());
        assertEquals(3, cart.getItems().get(0).getQuantity().value());
        assertEquals(0, cart.getTotal().compareTo(Money.of(300.00, "USD")));
    }

    @Test
    void actualizarCantidadACeroEliminaElItem() {
        Cart cart = Cart.create(BuyerId.generate(), USD);
        ProductId productId = ProductId.generate();
        cart.addItem(CartItem.of(productId, ProductName.of("Cable"), Quantity.of(2),
                Money.of(5.00, "USD"), ProductType.PHYSICAL));

        cart.updateQuantity(productId, Quantity.ZERO);
        assertTrue(cart.getItems().isEmpty());
    }

    @Test
    void eliminarItemRecalculaTotal() {
        Cart cart = Cart.create(BuyerId.generate(), USD);
        ProductId cable = ProductId.generate();
        cart.addItem(CartItem.of(cable, ProductName.of("Cable"), Quantity.of(2),
                Money.of(5.00, "USD"), ProductType.PHYSICAL));
        cart.addItem(item("Hub", 30.00, 1));

        cart.removeItem(cable);
        assertEquals(1, cart.getItems().size());
        assertEquals(0, cart.getTotal().compareTo(Money.of(30.00, "USD")));
    }

    @Test
    void vaciarCarritoDejaTotalEnCero() {
        Cart cart = Cart.create(BuyerId.generate(), USD);
        cart.addItem(item("Teclado", 25.50, 2));
        cart.clear();
        assertTrue(cart.getItems().isEmpty());
        assertTrue(cart.getTotal().compareTo(Money.zero(USD)) == 0);
    }

    @Test
    void noSePermiteItemEnMonedaDistintaALaDelCarrito() {
        Cart cart = Cart.create(BuyerId.generate(), USD);
        CartItem item = CartItem.of(ProductId.generate(), ProductName.of("Servicio"),
                Quantity.of(1), Money.of(100000, "COP"), ProductType.DIGITAL);
        assertThrows(BusinessException.class, () -> cart.addItem(item));
    }
}