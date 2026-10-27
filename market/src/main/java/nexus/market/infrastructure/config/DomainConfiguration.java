package nexus.market.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import nexus.market.domain.ports.out.AuditLogRepositoryPort;
import nexus.market.domain.ports.out.BuyerRepositoryPort;
import nexus.market.domain.ports.out.BusinessConfigurationPort;
import nexus.market.domain.ports.out.CartRepositoryPort;
import nexus.market.domain.ports.out.InventoryRepositoryPort;
import nexus.market.domain.ports.out.InvoiceRepositoryPort;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.PaymentServicePort;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.ports.out.RefundRepositoryPort;
import nexus.market.domain.ports.out.ReturnRepositoryPort;
import nexus.market.domain.ports.out.SellerRepositoryPort;
import nexus.market.domain.ports.out.ShippingProviderPort;
import nexus.market.domain.ports.out.ShippingRepositoryPort;
import nexus.market.domain.ports.out.UserRepositoryPort;
import nexus.market.domain.ports.out.WarehouseRepositoryPort;
import nexus.market.domain.services.AuditService;
import nexus.market.domain.services.CartService;
import nexus.market.domain.services.InventoryService;
import nexus.market.domain.services.InvoiceService;
import nexus.market.domain.services.OrderService;
import nexus.market.domain.services.ProductCatalogService;
import nexus.market.domain.services.ReturnRefundService;
import nexus.market.domain.services.ShippingService;
import nexus.market.domain.services.UserManagementService;
import nexus.market.domain.specifications.ActiveBuyerSpecification;
import nexus.market.domain.specifications.ActiveUserSpecification;
import nexus.market.domain.specifications.AvailableInventorySpecification;
import nexus.market.domain.specifications.OrderModifiableSpecification;
import nexus.market.domain.specifications.ProductPublishableSpecification;
import nexus.market.domain.specifications.RefundEligibilitySpecification;
import nexus.market.domain.specifications.UniqueUserSpecification;
import nexus.market.domain.ports.out.BusinessConfigurationPort;
import nexus.market.domain.valueobjects.DocumentId;
import nexus.market.domain.valueobjects.Email;
import nexus.market.domain.valueobjects.ProductId;

/**
 * Configuración de infraestructura que registra como beans los servicios de
 * dominio y las especificaciones.
 *
 * <p>El dominio es libre de frameworks (sin anotaciones de Spring), por lo que
 * estos objetos se ensamblan aquí, en la capa de infraestructura. Solo se
 * activa con el perfil {@code jpa}, cuando los repositorios de salida están
 * disponibles; sin él, la aplicación arranca únicamente con el dominio puro
 * (útil para las pruebas unitarias).</p>
 */
@Configuration
@Profile("jpa")
public class DomainConfiguration {

    // ------------------------------------------------------------------
    // Especificaciones
    // ------------------------------------------------------------------

    @Bean
    public ActiveUserSpecification activeUserSpecification() {
        return new ActiveUserSpecification();
    }

    @Bean
    public ActiveBuyerSpecification activeBuyerSpecification() {
        return new ActiveBuyerSpecification();
    }

    @Bean
    public AvailableInventorySpecification availableInventorySpecification() {
        return new AvailableInventorySpecification();
    }

    @Bean
    public OrderModifiableSpecification orderModifiableSpecification() {
        return new OrderModifiableSpecification();
    }

    @Bean
    public UniqueUserSpecification uniqueUserSpecification(UserRepositoryPort users) {
        return new UniqueUserSpecification(new UniqueUserSpecification.UserLookup() {
            @Override
            public boolean existsByEmail(Email email) {
                return users.existsByEmail(email);
            }

            @Override
            public boolean existsByDocumentId(DocumentId documentId) {
                return users.existsByDocumentId(documentId);
            }
        });
    }

    @Bean
    public ProductPublishableSpecification productPublishableSpecification(
            InventoryRepositoryPort inventories) {
        return new ProductPublishableSpecification(
                (ProductId productId) -> inventories.findByProductId(productId));
    }

    @Bean
    public RefundEligibilitySpecification refundEligibilitySpecification(BusinessConfigurationPort config) {
        return new RefundEligibilitySpecification(config.refundMaxDays());
    }

    // ------------------------------------------------------------------
    // Servicios de dominio
    // ------------------------------------------------------------------

    @Bean
    public AuditService auditService(AuditLogRepositoryPort auditLog) {
        return new AuditService(auditLog);
    }

    @Bean
    public UserManagementService userManagementService(UserRepositoryPort users,
                                                       BuyerRepositoryPort buyers,
                                                       SellerRepositoryPort sellers,
                                                       UniqueUserSpecification uniqueUser,
                                                       AuditService audit) {
        return new UserManagementService(users, buyers, sellers, uniqueUser, audit);
    }

    @Bean
    public ProductCatalogService productCatalogService(ProductRepositoryPort products,
                                                       InventoryRepositoryPort inventories,
                                                       ProductPublishableSpecification publishable,
                                                       AuditService audit) {
        return new ProductCatalogService(products, inventories, publishable, audit);
    }

    @Bean
    public InventoryService inventoryService(InventoryRepositoryPort inventories,
                                             WarehouseRepositoryPort warehouses,
                                             AvailableInventorySpecification available,
                                             AuditService audit) {
        return new InventoryService(inventories, warehouses, available, audit);
    }

    @Bean
    public ShippingService shippingService(ShippingRepositoryPort shippings,
                                           OrderRepositoryPort orders,
                                           ShippingProviderPort shippingProvider,
                                           AuditService audit) {
        return new ShippingService(shippings, orders, shippingProvider, audit);
    }

    @Bean
    public InvoiceService invoiceService(InvoiceRepositoryPort invoices,
                                         OrderRepositoryPort orders,
                                         ProductRepositoryPort products,
                                         AuditService audit) {
        return new InvoiceService(invoices, orders, products, audit);
    }

    @Bean
    public CartService cartService(CartRepositoryPort carts,
                                   BuyerRepositoryPort buyers,
                                   ProductRepositoryPort products,
                                   BusinessConfigurationPort config,
                                   ActiveBuyerSpecification activeBuyer,
                                   AuditService audit) {
        return new CartService(carts, buyers, products, config, activeBuyer, audit);
    }

    @Bean
    public OrderService orderService(OrderRepositoryPort orders,
                                     CartRepositoryPort carts,
                                     BuyerRepositoryPort buyers,
                                     PaymentServicePort payments,
                                     ActiveBuyerSpecification activeBuyer,
                                     OrderModifiableSpecification orderModifiable,
                                     InventoryService inventoryService,
                                     ShippingService shippingService,
                                     InvoiceService invoiceService,
                                     AuditService audit) {
        return new OrderService(orders, carts, buyers, payments, activeBuyer, orderModifiable,
                inventoryService, shippingService, invoiceService, audit);
    }

    @Bean
    public ReturnRefundService returnRefundService(ReturnRepositoryPort returns,
                                                   RefundRepositoryPort refunds,
                                                   OrderRepositoryPort orders,
                                                   PaymentServicePort payments,
                                                   BusinessConfigurationPort config,
                                                   RefundEligibilitySpecification eligibility,
                                                   InventoryService inventoryService,
                                                   AuditService audit) {
        return new ReturnRefundService(returns, refunds, orders, payments, config, eligibility,
                inventoryService, audit);
    }
}

