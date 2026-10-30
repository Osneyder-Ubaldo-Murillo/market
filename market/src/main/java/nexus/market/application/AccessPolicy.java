package nexus.market.application;

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Buyer;
import nexus.market.domain.models.Inventory;
import nexus.market.domain.models.Invoice;
import nexus.market.domain.models.Order;
import nexus.market.domain.models.Product;
import nexus.market.domain.models.Return;
import nexus.market.domain.models.Seller;
import nexus.market.domain.models.Shipping;
import nexus.market.domain.models.Warehouse;
import nexus.market.domain.ports.out.BuyerRepositoryPort;
import nexus.market.domain.ports.out.InventoryRepositoryPort;
import nexus.market.domain.ports.out.InvoiceRepositoryPort;
import nexus.market.domain.ports.out.OrderRepositoryPort;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.ports.out.ReturnRepositoryPort;
import nexus.market.domain.ports.out.SellerRepositoryPort;
import nexus.market.domain.ports.out.ShippingRepositoryPort;
import nexus.market.domain.ports.out.WarehouseRepositoryPort;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.InventoryId;
import nexus.market.domain.valueobjects.InvoiceId;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.OrderItem;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ReturnId;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.ShippingId;
import nexus.market.domain.valueobjects.SystemRole;
import nexus.market.domain.valueobjects.UserId;
import nexus.market.domain.valueobjects.WarehouseId;

/**
 * Política de autorización por <b>propiedad del recurso</b>, complementaria a
 * las reglas por rol de {@code SecurityConfiguration}.
 *
 * <p>La identidad sale siempre del token (nunca de la URL). Para los roles que
 * solo pueden operar sobre recursos propios (BUYER, SELLER) cada método
 * comprueba que el recurso pertenezca al usuario autenticado; si no puede
 * demostrarse la propiedad, incluso cuando el recurso no existe, lanza
 * {@link AccessDeniedException} (403) para no revelar su existencia. Los roles
 * sin restricción de propiedad (ADMIN, SUPERVISOR, LOGISTICS_OPERATOR, donde la
 * matriz lo permite) pasan y el caso de uso responde 404 si falta el recurso.</p>
 */
@Component
@Profile("jpa")
public class AccessPolicy {

    private final BuyerRepositoryPort buyers;
    private final SellerRepositoryPort sellers;
    private final ProductRepositoryPort products;
    private final WarehouseRepositoryPort warehouses;
    private final InventoryRepositoryPort inventories;
    private final OrderRepositoryPort orders;
    private final ShippingRepositoryPort shippings;
    private final InvoiceRepositoryPort invoices;
    private final ReturnRepositoryPort returns;

    public AccessPolicy(BuyerRepositoryPort buyers, SellerRepositoryPort sellers,
                        ProductRepositoryPort products, WarehouseRepositoryPort warehouses,
                        InventoryRepositoryPort inventories, OrderRepositoryPort orders,
                        ShippingRepositoryPort shippings, InvoiceRepositoryPort invoices,
                        ReturnRepositoryPort returns) {
        this.buyers = buyers;
        this.sellers = sellers;
        this.products = products;
        this.warehouses = warehouses;
        this.inventories = inventories;
        this.orders = orders;
        this.shippings = shippings;
        this.invoices = invoices;
        this.returns = returns;
    }

    // ------------------------------------------------------------------
    // Identidad actual
    // ------------------------------------------------------------------

    /** Id del usuario autenticado, o vacío si la petición es anónima. */
    public Optional<String> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(auth.getPrincipal()));
    }

    public boolean hasRole(SystemRole... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || currentUserId().isEmpty()) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            for (SystemRole role : roles) {
                if (authority.getAuthority().equals("ROLE_" + role.getCode())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Optional<Buyer> currentBuyer() {
        if (!hasRole(SystemRole.BUYER)) {
            return Optional.empty();
        }
        return currentUserId().flatMap(id -> buyers.findByUserId(UserId.of(id)));
    }

    /** Vendedor del usuario autenticado (solo con rol SELLER). */
    public Optional<SellerId> currentSellerId() {
        if (!hasRole(SystemRole.SELLER)) {
            return Optional.empty();
        }
        return currentUserId()
                .flatMap(id -> sellers.findByUserId(UserId.of(id)))
                .map(Seller::getSellerId);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Acceso denegado.");
    }

    // ------------------------------------------------------------------
    // Usuarios, compradores y vendedores
    // ------------------------------------------------------------------

    /** Un usuario solo se consulta a sí mismo; ADMIN y SUPERVISOR consultan a cualquiera. */
    public void requireSelfOrAdminOrSupervisor(String userId) {
        if (hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR)) {
            return;
        }
        if (currentUserId().filter(userId::equals).isEmpty()) {
            throw denied();
        }
    }

    /** El path {@code buyerId} debe ser el comprador del usuario autenticado (rol BUYER). */
    public void requireOwnBuyer(String buyerId) {
        boolean own = currentBuyer()
                .map(b -> b.getBuyerId().value().equals(buyerId))
                .orElse(false);
        if (!own) {
            throw denied();
        }
    }

    /** El path {@code sellerId} debe ser el vendedor del usuario autenticado (rol SELLER). */
    public void requireOwnSeller(String sellerId) {
        boolean own = currentSellerId().map(s -> s.value().equals(sellerId)).orElse(false);
        if (!own) {
            throw denied();
        }
    }

    // ------------------------------------------------------------------
    // Catálogo, bodegas e inventario (SELLER)
    // ------------------------------------------------------------------

    private boolean sellerOwnsProduct(Product product) {
        return currentSellerId().map(s -> s.equals(product.getSellerId())).orElse(false);
    }

    private boolean sellerOwnsWarehouse(Warehouse warehouse) {
        return warehouse.getSellerId() != null
                && currentSellerId().map(s -> s.equals(warehouse.getSellerId())).orElse(false);
    }

    public void requireOwnProduct(String productId) {
        if (products.findById(ProductId.of(productId)).filter(this::sellerOwnsProduct).isEmpty()) {
            throw denied();
        }
    }

    /** ADMIN y SUPERVISOR ven cualquier producto; el vendedor, solo los propios. */
    public boolean canSeeUnpublished(Product product) {
        return hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR) || sellerOwnsProduct(product);
    }

    /** Lectura de una bodega: ADMIN/SUPERVISOR/LOGISTICS_OPERATOR cualquiera; SELLER solo las suyas. */
    public void requireWarehouseReadable(String warehouseId) {
        if (hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR, SystemRole.LOGISTICS_OPERATOR)) {
            return;
        }
        if (warehouses.findById(WarehouseId.of(warehouseId)).filter(this::sellerOwnsWarehouse).isEmpty()) {
            throw denied();
        }
    }

    /** Lectura de las bodegas de un vendedor. */
    public void requireSellerWarehousesReadable(String sellerId) {
        if (hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR, SystemRole.LOGISTICS_OPERATOR)) {
            return;
        }
        requireOwnSeller(sellerId);
    }

    /** Crear inventario: producto y bodega deben ser del vendedor autenticado. */
    public void requireOwnProductAndWarehouse(String productId, String warehouseId) {
        boolean ok = products.findById(ProductId.of(productId)).filter(this::sellerOwnsProduct).isPresent()
                && warehouses.findById(WarehouseId.of(warehouseId)).filter(this::sellerOwnsWarehouse).isPresent();
        if (!ok) {
            throw denied();
        }
    }

    /** Operar sobre un inventario existente: su producto y su bodega deben ser propios. */
    public void requireOwnInventory(String inventoryId) {
        Optional<Inventory> inventory = inventories.findById(InventoryId.of(inventoryId));
        boolean ok = inventory.isPresent()
                && products.findById(inventory.get().getProductId()).filter(this::sellerOwnsProduct).isPresent()
                && warehouses.findById(inventory.get().getWarehouseId()).filter(this::sellerOwnsWarehouse).isPresent();
        if (!ok) {
            throw denied();
        }
    }

    /** Consulta de inventario: ADMIN/SUPERVISOR cualquiera; SELLER solo el propio. */
    public void requireInventoryReadable(String inventoryId) {
        if (hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR)) {
            return;
        }
        requireOwnInventory(inventoryId);
    }

    // ------------------------------------------------------------------
    // Pedidos y recursos derivados
    // ------------------------------------------------------------------

    private boolean buyerOwnsOrder(Order order) {
        return currentBuyer().map(b -> b.getBuyerId().equals(order.getBuyerId())).orElse(false);
    }

    /** Ítems del pedido que pertenecen al vendedor autenticado (vacío si no es vendedor). */
    public List<OrderItem> sellerItems(Order order) {
        Optional<SellerId> seller = currentSellerId();
        if (seller.isEmpty()) {
            return List.of();
        }
        return order.getItems().stream()
                .filter(item -> products.findById(item.getProductId())
                        .map(p -> p.getSellerId().equals(seller.get())).orElse(false))
                .toList();
    }

    /**
     * Pedido legible por el usuario: BUYER dueño, SELLER con ítems propios y
     * ADMIN/SUPERVISOR/LOGISTICS_OPERATOR. Devuelve el pedido ya cargado.
     */
    public Order readableOrder(String orderId) {
        Optional<Order> found = orders.findById(OrderId.of(orderId));
        if (hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR, SystemRole.LOGISTICS_OPERATOR)) {
            return found.orElseThrow(
                    () -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
        }
        Order order = found.orElseThrow(AccessPolicy::denied);
        boolean ok = (hasRole(SystemRole.BUYER) && buyerOwnsOrder(order))
                || (hasRole(SystemRole.SELLER) && !sellerItems(order).isEmpty());
        if (!ok) {
            throw denied();
        }
        return order;
    }

    /** Pedido que pertenece al comprador autenticado (pago, cancelación, devolución). */
    public void requireOwnOrder(String orderId) {
        if (orders.findById(OrderId.of(orderId)).filter(this::buyerOwnsOrder).isEmpty()) {
            throw denied();
        }
    }

    public void requireShippingReadable(String shippingId) {
        Optional<Shipping> shipping = shippings.findById(ShippingId.of(shippingId));
        if (hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR, SystemRole.LOGISTICS_OPERATOR)) {
            return;
        }
        readableOrder(shipping.orElseThrow(AccessPolicy::denied).getOrderId().value());
    }

    /** Factura legible: BUYER dueño de la factura; ADMIN y SUPERVISOR. El vendedor queda excluido. */
    public void requireInvoiceReadable(String invoiceId) {
        if (hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR)) {
            return;
        }
        Optional<Invoice> invoice = invoices.findById(InvoiceId.of(invoiceId));
        BuyerId invoiceBuyer = invoice.orElseThrow(AccessPolicy::denied).getBuyerId();
        if (!hasRole(SystemRole.BUYER)
                || currentBuyer().filter(b -> b.getBuyerId().equals(invoiceBuyer)).isEmpty()) {
            throw denied();
        }
    }

    /** Factura de un pedido: BUYER dueño del pedido; ADMIN y SUPERVISOR. */
    public void requireOrderInvoiceReadable(String orderId) {
        if (hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR)) {
            return;
        }
        requireOwnOrder(orderId);
    }

    public void requireReturnReadable(String returnId) {
        if (hasRole(SystemRole.ADMIN, SystemRole.SUPERVISOR, SystemRole.LOGISTICS_OPERATOR)) {
            return;
        }
        Optional<Return> found = returns.findById(ReturnId.of(returnId));
        requireOwnOrder(found.orElseThrow(AccessPolicy::denied).getOrderId().value());
    }
}
