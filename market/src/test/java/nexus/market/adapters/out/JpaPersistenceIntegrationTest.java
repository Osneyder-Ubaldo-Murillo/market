package nexus.market.adapters.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Cart;
import nexus.market.domain.models.Inventory;
import nexus.market.domain.models.Invoice;
import nexus.market.domain.models.Order;
import nexus.market.domain.models.Product;
import nexus.market.domain.models.Refund;
import nexus.market.domain.models.Return;
import nexus.market.domain.models.Seller;
import nexus.market.domain.models.Shipping;
import nexus.market.domain.models.User;
import nexus.market.domain.models.Warehouse;
import nexus.market.domain.ports.out.BuyerRepositoryPort;
import nexus.market.domain.ports.out.BusinessConfigurationPort;
import nexus.market.domain.ports.out.CartRepositoryPort;
import nexus.market.domain.ports.out.InventoryRepositoryPort;
import nexus.market.domain.ports.out.InvoiceRepositoryPort;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.ports.out.RefundRepositoryPort;
import nexus.market.domain.ports.out.ReturnRepositoryPort;
import nexus.market.domain.ports.out.SellerRepositoryPort;
import nexus.market.domain.ports.out.ShippingRepositoryPort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.ports.out.WarehouseRepositoryPort;
import nexus.market.domain.valueobjects.Address;
import nexus.market.domain.valueobjects.BusinessName;
import nexus.market.domain.valueobjects.CartItem;
import nexus.market.domain.valueobjects.DeliveryInfo;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.FullName;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductStatus;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.Quantity;
import nexus.market.domain.valueobjects.ReturnItem;
import nexus.market.domain.valueobjects.ReturnReason;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.TaxId;

import static org.assertj.core.api.Assertions.assertThat;
/**
 * Prueba de integración de los adaptadores de persistencia JPA (MySQL) sobre
 * H2 en memoria usando el perfil {@code jpa}.
 *
 * <p>Valida el round-trip completo {@code dominio -> entidad JPA -> base ->
 * entidad JPA -> dominio} de los 12 agregados.</p>
 */
@ActiveProfiles("jpa")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nexusmarket;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.data.mongodb.uri=",
        "spring.data.mongodb.database=test_audit"
})
class JpaPersistenceIntegrationTest {

    @Autowired
    UserRepositoryPort users;

    @Autowired
    BuyerRepositoryPort buyers;

    @Autowired
    SellerRepositoryPort sellers;

    @Autowired
    WarehouseRepositoryPort warehouses;

    @Autowired
    ProductRepositoryPort products;

    @Autowired
    InventoryRepositoryPort inventories;

    @Autowired
    CartRepositoryPort carts;

    @Autowired
    OrderRepositoryPort orders;

    @Autowired
    InvoiceRepositoryPort invoices;

    @Autowired
    ShippingRepositoryPort shippings;

    @Autowired
    ReturnRepositoryPort returns;

    @Autowired
    RefundRepositoryPort refunds;

    @Autowired
    BusinessConfigurationPort config;

    private static Address address() {
        return Address.of("Calle 55", "12-34", "Apto 501", "Poblado",
                "Medellín", "Antioquia", "050010", "CO");
    }

    private static Money cop(String amount) {
        return Money.of(new BigDecimal(amount), "COP");
    }

    @Test
    void usuariosCompradoresVendedoresBodegas() {
        // Comprador: User + Buyer
        User buyerUser = users.save(User.create(
                FullName.of("Ana Maria Lopez"), Email.of("ana@example.com"),
                DocumentId.of("CC-1000001"), SystemRole.BUYER));
        Buyer buyer = buyers.save(Buyer.create(buyerUser.getUserId(), address()));

        assertThat(users.findById(buyerUser.getUserId())).isPresent();
        assertThat(users.findByEmail(Email.of("ana@example.com"))).isPresent();
        assertThat(users.existsByEmail(Email.of("ana@example.com"))).isTrue();
        assertThat(users.existsByDocumentId(DocumentId.of("CC-1000001"))).isTrue();
        assertThat(buyers.findById(buyer.getBuyerId())).isPresent();
        assertThat(buyers.findByUserId(buyerUser.getUserId())).isPresent();

        // Vendedor: User + Seller
        User sellerUser = users.save(User.create(
                FullName.of("Carlos Ruiz"), Email.of("carlos@example.com"),
                DocumentId.of("CC-1000002"), SystemRole.SELLER));
        Seller seller = sellers.save(Seller.create(sellerUser.getUserId(),
                BusinessName.of("TecnoShop SAS"), TaxId.of("900000001")));

        assertThat(sellers.findById(seller.getSellerId())).isPresent();
        assertThat(sellers.findByStatus(seller.getStatus()))
                .extracting(Seller::getSellerId).contains(seller.getSellerId());

        // Bodegas
        Warehouse central = warehouses.save(Warehouse.createMarketplace("Bodega Central", address()));
        Warehouse sellerWh = warehouses.save(Warehouse.createSeller("Bodega Vendedor", address(), seller.getSellerId()));
        assertThat(warehouses.findById(central.getWarehouseId())).isPresent();
        assertThat(warehouses.findBySellerId(seller.getSellerId()))
                .extracting(Warehouse::getWarehouseId).contains(sellerWh.getWarehouseId());

        // Transiciones de estado con re-lectura
        buyer.block();
        Buyer blocked = buyers.save(buyer);
        assertThat(blocked.getCommercialStatus().getCode()).isEqualTo("BLOCKED");
        seller.approve();
        assertThat(sellers.save(seller).getStatus().getCode()).isEqualTo("ACTIVE");
    }
@Test
    void productoYInventarioConMovimientos() {
        User sellerUser = users.save(User.create(
                FullName.of("Diana Torres"), Email.of("diana@example.com"),
                DocumentId.of("CC-1000003"), SystemRole.SELLER));
        Seller seller = sellers.save(Seller.create(sellerUser.getUserId(),
                BusinessName.of("Distribuidora Diana"), TaxId.of("900000002")));
        Warehouse warehouse = warehouses.save(Warehouse.createSeller("Bodega Norte", address(), seller.getSellerId()));

        Product product = products.save(Product.create(seller.getSellerId(),
                ProductName.of("Laptop Neo 16"), ProductDescription.of("Laptop de 16 GB de RAM"),
                ProductType.PHYSICAL, cop("2500000")));

        Inventory inventory = inventories.save(Inventory.create(product.getProductId(), warehouse.getWarehouseId()));
        inventory.addStock(Quantity.of(10), Map.of("lote", "L-2026"));
        inventory.reserve(Quantity.of(3), Map.of("order", "demo"));
        Inventory persisted = inventories.save(inventory);

        assertThat(products.findById(product.getProductId())).isPresent();
        assertThat(products.findBySellerId(seller.getSellerId()))
                .extracting(Product::getProductId).containsExactly(product.getProductId());
        assertThat(products.findByStatus(ProductStatus.INACTIVE))
                .extracting(Product::getProductId).contains(product.getProductId());
        assertThat(products.findPublished())
                .extracting(Product::getProductId).doesNotContain(product.getProductId());

        assertThat(persisted.getQuantity()).isEqualTo(Quantity.of(10));
        assertThat(persisted.getReservedQuantity()).isEqualTo(Quantity.of(3));
        assertThat(persisted.getAvailableQuantity()).isEqualTo(Quantity.of(7));
        assertThat(persisted.getMovements()).hasSize(2);
        assertThat(inventories.findByProductId(product.getProductId())).hasSize(1);
        assertThat(inventories.findByProductIdAndWarehouseId(product.getProductId(), warehouse.getWarehouseId()))
                .isPresent();
        assertThat(persisted.getMovements().get(0).getMetadata())
                .containsEntry("lote", "L-2026");
    }

    @Test
    void carritoPersisteItemsYTotal() {
        User user = users.save(User.create(FullName.of("Felipe Ortiz"),
                Email.of("felipe@example.com"), DocumentId.of("CC-1000004"), SystemRole.BUYER));
        Buyer buyer = buyers.save(Buyer.create(user.getUserId(), address()));
        Warehouse warehouse = warehouses.save(Warehouse.createMarketplace("Bodega Central", address()));
        Seller seller = sellers.save(Seller.create(user.getUserId(),
                BusinessName.of("MercadoLibreLocal"), TaxId.of("900000003")));
        Product product = products.save(Product.create(seller.getSellerId(),
                ProductName.of("Audifonos Pro"), ProductDescription.of("Audifonos inalambricos"),
                ProductType.PHYSICAL, cop("250000")));

        Cart cart = carts.save(Cart.create(buyer.getBuyerId(), Currency.getInstance("COP")));
        cart.addItem(CartItem.of(product.getProductId(), product.getName(),
                Quantity.of(2), product.getPrice(), product.getProductType()));
        Cart saved = carts.save(cart);

        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getTotal()).isEqualTo(cop("500000"));

        Optional<Cart> reloaded = carts.findByBuyerId(buyer.getBuyerId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getItems()).hasSize(1);
        assertThat(reloaded.get().getItems().get(0).getProductName()).isEqualTo(product.getName());
        assertThat(reloaded.get().getTotal()).isEqualTo(cop("500000"));
    }
@Test
    void pedidoFacturaEnvioDevolucionReembolso() {
        User user = users.save(User.create(FullName.of("Lucia Paredes"),
                Email.of("lucia@example.com"), DocumentId.of("CC-1000005"), SystemRole.BUYER));
        Buyer buyer = buyers.save(Buyer.create(user.getUserId(), address()));
        User sellerUser = users.save(User.create(FullName.of("Miguel Angel"),
                Email.of("miguel@example.com"), DocumentId.of("CC-1000006"), SystemRole.SELLER));
        Seller seller = sellers.save(Seller.create(sellerUser.getUserId(),
                BusinessName.of("TechNexus"), TaxId.of("900000004")));
        Warehouse warehouse = warehouses.save(Warehouse.createSeller("Bodega Sur", address(), seller.getSellerId()));
        Product product = products.save(Product.create(seller.getSellerId(),
                ProductName.of("Teclado Mecanico"), ProductDescription.of("Switch red"),
                ProductType.PHYSICAL, cop("320000")));

        OrderItem item = OrderItem.of(product.getProductId(), product.getName(),
                Quantity.of(1), product.getPrice(), product.getProductType());
        Order order = orders.save(Order.create(buyer.getBuyerId(), List.of(item), address()));
        assertThat(order.getStatus().getCode()).isEqualTo("PENDING_PAYMENT");

        // Factura
        Invoice invoice = invoices.save(Invoice.issue(order.getOrderId(), order.getBuyerId(),
                seller.getSellerId(), order.getItems(), order.getTotal()));
        order.assignInvoice(invoice.getInvoiceId());
        orders.save(order);
        assertThat(invoices.findByOrderId(order.getOrderId())).isPresent();
        assertThat(orders.findById(order.getOrderId()).orElseThrow().getInvoiceId())
                .isEqualTo(invoice.getInvoiceId());

        // Envío
        Shipping shipping = shippings.save(Shipping.create(order.getOrderId(), warehouse.getWarehouseId()));
        shipping.dispatch(DeliveryInfo.of("TRK-999", "SimulCarrier", LocalDate.now().plusDays(5)));
        Shipping dispatched = shippings.save(shipping);
        assertThat(dispatched.getDeliveryInfo()).isNotNull();
        assertThat(dispatched.getDeliveryInfo().getCarrier()).isEqualTo("SimulCarrier");
        assertThat(shippings.findByOrderId(order.getOrderId())).isPresent();

        // Devolución y reembolso
        Return aReturn = returns.save(Return.request(order.getOrderId(), List.of(
                ReturnItem.of(product.getProductId(), product.getName(), Quantity.of(1), ReturnReason.DAMAGED))));
        assertThat(aReturn.getStatus().getCode()).isEqualTo("REQUESTED");
        assertThat(returns.findByOrderId(order.getOrderId())).isPresent();

        Refund refund = refunds.save(Refund.createFromReturn(aReturn.getReturnId(),
                order.getOrderId(), order.getTotal()));
        refund.process();
        Refund processed = refunds.save(refund);
        assertThat(processed.getReturnId()).isEqualTo(aReturn.getReturnId());
        assertThat(processed.getStatus().getCode()).isEqualTo("PROCESSED");
        assertThat(refunds.findByOrderId(order.getOrderId())).extracting(Refund::getRefundId)
                .contains(processed.getRefundId());
    }

    @Test
    void configuracionDeNegocioPorPropiedades() {
        assertThat(config.refundMaxDays()).isEqualTo(15);
        assertThat(config.defaultCurrency()).isEqualTo(Currency.getInstance("COP"));
        assertThat(config.maxAdditionalAddresses()).isEqualTo(10);
        assertThat(config.maxWarehousesPerSeller()).isEqualTo(10);
    }
}