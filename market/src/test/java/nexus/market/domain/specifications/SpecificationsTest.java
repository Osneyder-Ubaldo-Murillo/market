package nexus.market.domain.specifications;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Inventory;
import nexus.market.domain.models.Order;
import nexus.market.domain.models.Product;
import nexus.market.domain.models.User;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.OrderStatus;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.ReturnItem;
import nexus.market.domain.valueobjects.ReturnReason;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.WarehouseId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba las reglas declarativas (specifications) más relevantes:
 * inventario disponible, elegibilidad de devoluciones, publicación de
 * productos y unicidad de usuarios.
 */
class SpecificationsTest {

    private static final Address ADDRESS = Address.of(
            "Av. Principal", "1", null, "Centro", "Ciudad", "Provincia",
            "11011", "País");

    @Test
    void inventarioDisponibleCumpleConLaCantidadRequerida() {
        Inventory inventory = Inventory.create(ProductId.generate(), WarehouseId.generate());
        inventory.addStock(Quantity.of(10), Map.of());
        inventory.reserve(Quantity.of(2), Map.of());

        AvailableInventorySpecification spec = new AvailableInventorySpecification();
        assertTrue(spec.isSatisfiedBy(inventory, Quantity.of(8)));
        assertFalse(spec.isSatisfiedBy(inventory, Quantity.of(9)));
    }

    @Test
    void inventarioDanadoNoEsVendible() {
        Inventory inventory = Inventory.create(ProductId.generate(), WarehouseId.generate());
        inventory.markDamaged();

        AvailableInventorySpecification spec = new AvailableInventorySpecification();
        assertFalse(spec.isSatisfiedBy(inventory, Quantity.of(1)));
    }

    @Test
    void productoDigitalSiempreEsPublicable() {
        Product product = Product.create(
                SellerId.generate(), ProductName.of("Curso Online"),
                ProductDescription.of("Curso en línea de programación con certificado"),
                ProductType.DIGITAL, Money.of(50.00, "USD"));

        ProductPublishableSpecification spec =
                new ProductPublishableSpecification(productId -> List.of());
        assertTrue(spec.isSatisfiedBy(product));
    }

    @Test
    void productoFisicoRequiereInventarioActivoConStock() {
        Product product = Product.create(
                SellerId.generate(), ProductName.of("Libro Físico"),
                ProductDescription.of("Libro impreso de ingeniería de software"),
                ProductType.PHYSICAL, Money.of(30.00, "USD"));
        ProductId productId = product.getProductId();

        Inventory withStock = Inventory.create(productId, WarehouseId.generate());
        withStock.addStock(Quantity.of(3), Map.of());
        ProductPublishableSpecification ok = new ProductPublishableSpecification(
                id -> List.of(withStock));
        assertTrue(ok.isSatisfiedBy(product));

        Inventory empty = Inventory.create(productId, WarehouseId.generate());
        ProductPublishableSpecification ko = new ProductPublishableSpecification(
                id -> List.of(empty));
        assertFalse(ko.isSatisfiedBy(product));
    }

    @Test
    void devolucionElegibleDentroDelPlazo() {
        Order order = deliveredOrder(LocalDateTime.now().minusDays(5));
        RefundEligibilitySpecification spec = new RefundEligibilitySpecification(30);
        assertTrue(spec.isSatisfiedBy(order, returnItem()));
    }

    @Test
    void devolucionRechazadaFueraDelPlazo() {
        Order order = deliveredOrder(LocalDateTime.now().minusDays(31));
        RefundEligibilitySpecification spec = new RefundEligibilitySpecification(30);
        assertFalse(spec.isSatisfiedBy(order, returnItem()));
    }

    @Test
    void devolucionRechazadaSiElPedidoNoEstaEntregado() {
        Order pending = Order.create(BuyerId.generate(), List.of(oneItem()), ADDRESS);
        RefundEligibilitySpecification spec = new RefundEligibilitySpecification(30);
        assertFalse(spec.isSatisfiedBy(pending, returnItem()));
    }

    @Test
    void unicidadDeUsuarioSeApoyaEnElLookup() {
        UniqueUserSpecification spec = new UniqueUserSpecification(new UniqueUserSpecification.UserLookup() {
            @Override
            public boolean existsByEmail(Email email) {
                return email.getValue().equals("ocupado@example.com");
            }

            @Override
            public boolean existsByDocumentId(DocumentId documentId) {
                return false;
            }
        });

        assertFalse(spec.isSatisfiedBy(Email.of("ocupado@example.com"), DocumentId.of("99999999")));
        assertTrue(spec.isSatisfiedBy(Email.of("nuevo@example.com"), DocumentId.of("88888888")));
    }

    @Test
    void soloUsuariosYCompradoresActivosOperan() {
        User active = User.create(FullName.of("Luis Mora"), Email.of("luis.mora@example.com"),
                DocumentId.of("11112222"), SystemRole.BUYER);
        assertTrue(new ActiveUserSpecification().isSatisfiedBy(active));

        User blocked = User.create(FullName.of("Luis Mora"), Email.of("luis.mora2@example.com"),
                DocumentId.of("22223333"), SystemRole.BUYER);
        blocked.block();
        assertFalse(new ActiveUserSpecification().isSatisfiedBy(blocked));

        Buyer buyer = Buyer.create(active.getUserId(), ADDRESS);
        assertTrue(new ActiveBuyerSpecification().isSatisfiedBy(buyer));
    }

    private static OrderItem oneItem() {
        return OrderItem.of(ProductId.generate(), ProductName.of("Artículo"),
                Quantity.of(1), Money.of(10.00, "USD"), ProductType.PHYSICAL);
    }

    private static ReturnItem returnItem() {
        OrderItem item = oneItem();
        return ReturnItem.of(item.getProductId(), item.getProductName(),
                Quantity.of(1), ReturnReason.DAMAGED);
    }

    private static Order deliveredOrder(LocalDateTime deliveredAt) {
        BuyerId buyer = BuyerId.generate();
        Order order = Order.create(buyer, List.of(oneItem()), ADDRESS);
        order.confirmPayment();
        order.dispatch();
        order.deliver();
        // Fecha de entrega simulada para controlar el plazo de la devolución.
        return new Order(order.getOrderId(), buyer, order.getItems(), OrderStatus.DELIVERED,
                ADDRESS, order.getInvoiceId(), deliveredAt, order.getCreatedAt(), order.getUpdatedAt());
    }
}