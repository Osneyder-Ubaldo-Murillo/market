# Domain Services – NexusMarket

## Introducción

Los **servicios de dominio** orquestan agregados, Value Objects y especificaciones para ejecutar operaciones del negocio. A diferencia de los agregados —que protegen sus propios invariantes—, los servicios resuelven flujos que abarcan **varios agregados** o requieren **datos externos** (persistencia, pagos, notificaciones).

Viven en `nexus.market.domain.services` y **no tienen ninguna dependencia de frameworks**. Toda comunicación con el exterior se realiza a través de puertos (interfaces definidas en `nexus.market.domain.ports`), nunca mediante implementaciones concretas.

| Servicio                | Responsabilidad principal |
|-------------------------|---------------------------|
| `UserManagementService` | Registro y administración de usuarios, compradores y vendedores |
| `ProductCatalogService` | Ciclo de vida del catálogo de productos |
| `InventoryService`      | Existencias, reservas y movimientos de inventario |
| `CartService`           | Operaciones del carrito de compras |
| `OrderService`          | Creación, pago, despacho y entrega de pedidos |
| `ShippingService`       | Ciclo de vida de los envíos físicos |
| `ReturnRefundService`   | Devoluciones y reembolsos |
| `InvoiceService`        | Emisión y anulación de facturas |
| `AuditService`          | Registro central de auditoría |

> **Decisión de diseño**: los servicios reciben los puertos de salida y las especificaciones por constructor (inyección de dependencias). Las especificaciones que necesitan consultas (`UniqueUserSpecification`, `ProductPublishableSpecification`) definen interfaces funcionales anidadas que los adaptadores implementan, tal y como se documenta en *Domain Specifications.md*.

---

## UserManagementService

**Propósito**: registrar y administrar los usuarios de la plataforma y sus perfiles comerciales (`Buyer`, `Seller`). Solo un Administrador puede crear vendedores y aprobar su verificación.

**Dependencias**: `User`, `Buyer`, `Seller` · VOs `FullName`, `Email`, `DocumentId`, `Address`, `BusinessName`, `TaxId` · `UniqueUserSpecification`, `ActiveUserSpecification` · `UserRepositoryPort`, `BuyerRepositoryPort`, `SellerRepositoryPort` · `AuditService`.

| Método                                 | Descripción                                      | Reglas aplicadas                        | Auditoría |
|----------------------------------------|--------------------------------------------------|-----------------------------------------|-----------|
| `registerBuyer(...)`                   | Crea `User` rol `BUYER` + `Buyer` `ACTIVE`       | `UniqueUserSpecification` (email y documento únicos) | `USER_REGISTRATION`, `BUYER_REGISTRATION` |
| `registerSeller(...)`                  | Crea `User` rol `SELLER` + `Seller` `PENDING_VERIFICATION` (solo ADMIN) | Unicidad + permiso de rol | `SELLER_REGISTRATION` |
| `approveSeller(sellerId)`              | Aprueba la verificación del vendedor (solo ADMIN) | `Seller.approve()` (desde `PENDING_VERIFICATION`) | — (ver nota) |
| `blockUser(userId)` / `activateUser(userId)` / `deactivateUser(userId)` | Transiciones `ACTIVE ↔ BLOCKED`, `ACTIVE → INACTIVE` | `ActiveUserSpecification` | — (ver nota) |
| `addBuyerAddress(buyerId, address)`    | Agrega una dirección secundaria (máx. 10)        | Validación interna de `Buyer`           | — |

```java
public class UserManagementService {
    private final UserRepositoryPort users;
    private final BuyerRepositoryPort buyers;
    private final SellerRepositoryPort sellers;
    private final UniqueUserSpecification uniqueUser;
    private final AuditService audit;

    public UserManagementService(UserRepositoryPort users, BuyerRepositoryPort buyers,
                                 SellerRepositoryPort sellers, UniqueUserSpecification uniqueUser,
                                 AuditService audit) {
        this.users = users;
        this.buyers = buyers;
        this.sellers = sellers;
        this.uniqueUser = uniqueUser;
        this.audit = audit;
    }

    public Buyer registerBuyer(FullName fullName, Email email, DocumentId documentId, Address mainAddress) {
        if (!uniqueUser.isSatisfiedBy(email, documentId)) {
            throw new UserAlreadyExistsException("Ya existe un usuario con ese email o documento.");
        }
        User user = users.save(User.create(fullName, email, documentId, SystemRole.BUYER));
        Buyer buyer = buyers.save(Buyer.create(user.getUserId(), mainAddress));
        audit.record(OperationType.USER_REGISTRATION, AuditSeverity.INFO,
                user.getUserId().toString(), "Registro de comprador");
        audit.record(OperationType.BUYER_REGISTRATION, AuditSeverity.INFO,
                user.getUserId().toString(), "Perfil de comprador creado");
        return buyer;
    }
}
```

> **Nota (pendiente)**: el catálogo `OperationType` no incluye códigos para aprobación de vendedores ni cambios de estado de usuario. Se recomienda agregar `SELLER_APPROVAL`, `USER_BLOCK`, `USER_ACTIVATION` y `USER_DEACTIVATION` en la siguiente iteración del catálogo.

---

## ProductCatalogService

**Propósito**: gestionar el catálogo de productos de un vendedor: creación, publicación, despublicación y cambio de precio.

**Dependencias**: `Product` · `ProductPublishableSpecification` · `ProductRepositoryPort`, `InventoryRepositoryPort` · `AuditService`.

| Método                                  | Descripción                                   | Reglas aplicadas                                    | Auditoría           |
|-----------------------------------------|-----------------------------------------------|-----------------------------------------------------|---------------------|
| `createProduct(sellerId, name, description, type, price)` | Crea el producto en `INACTIVE` | Los VOs validan nombre, descripción y precio        | `PRODUCT_CREATION`  |
| `publishProduct(productId)`             | Publica el producto                           | `ProductPublishableSpecification` (físicos requieren inventario disponible; digitales siempre publicables) | `PRODUCT_PUBLISH`   |
| `unpublishProduct(productId)`           | Oculta el producto                            | `Product.unpublish()` (solo `ACTIVE`)               | `PRODUCT_UNPUBLISH` |
| `updatePrice(productId, newPrice)`      | Actualiza el precio                           | `Product.updatePrice(newPrice)`                     | `PRODUCT_UPDATE`    |
| `findPublished()`                       | Consulta el catálogo visible                  | —                                                   | —                   |

```java
public void publishProduct(ProductId productId) {
    Product product = products.findById(productId)
            .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente."));
    if (!publishable.isSatisfiedBy(product)) {
        throw new BusinessException("NOT_PUBLISHABLE",
                "Producto físico sin inventario disponible; no puede publicarse.");
    }
    product.publish();
    products.save(product);
    audit.record(OperationType.PRODUCT_PUBLISH, AuditSeverity.INFO,
            product.getSellerId().toString(), "Producto publicado: " + productId);
}
```

---

## InventoryService

**Propósito**: administrar existencias, reservas y movimientos de inventario. Todo cambio queda registrado en `Inventory.movements` (auditoría interna del agregado) y, además, se notifica a `AuditService`.

**Dependencias**: `Inventory`, `Warehouse` · `AvailableInventorySpecification` · `InventoryRepositoryPort`, `WarehouseRepositoryPort` · `AuditService`.

| Método                                         | Descripción                        | Reglas aplicadas                                  | Auditoría               |
|------------------------------------------------|------------------------------------|---------------------------------------------------|-------------------------|
| `createInventory(productId, warehouseId)`      | Crea inventario en cero (`OUT_OF_STOCK`) | Bodega existente y `ACTIVE`; una fila por par (producto, bodega) | —                       |
| `addStock(inventoryId, quantity, metadata)`    | Ingreso de mercancía               | `Inventory.addStock(...)`; `OUT_OF_STOCK → ACTIVE` | `INVENTORY_ADD`       |
| `reserve(inventoryId, quantity, metadata)`     | Reserva para un pedido             | `AvailableInventorySpecification` → `InsufficientInventoryException`; nunca sobre `DAMAGED` | `INVENTORY_RESERVE` |
| `release(inventoryId, quantity, metadata)`     | Libera una reserva                 | `Inventory.release(...)`; no liberar más de lo reservado | `INVENTORY_RELEASE` |
| `confirmSale(inventoryId, quantity, metadata)` | Salida definitiva por venta        | `Inventory.confirmSale(...)`; agota si llega a cero | `INVENTORY_CONFIRM_SALE` |
| `adjust(inventoryId, newQuantity, metadata)`   | Ajuste físico                      | Nueva existencia ≥ cantidad reservada             | `INVENTORY_ADJUST`      |
| `markDamaged(...)` / `markActive(...)`         | Estado del inventario              | —                                                 | —                       |

```java
public void confirmSale(InventoryId inventoryId, Quantity sold, Map<String, Object> metadata) {
    Inventory inventory = inventories.findById(inventoryId)
            .orElseThrow(() -> new BusinessException("INVENTORY_NOT_FOUND", "Inventario inexistente."));
    if (!available.isSatisfiedBy(inventory, sold)) {
        throw new InsufficientInventoryException("No hay existencias disponibles para vender " + sold);
    }
    inventory.confirmSale(sold, metadata);
    inventories.save(inventory);
    audit.record(OperationType.INVENTORY_CONFIRM_SALE, AuditSeverity.INFO,
            inventory.getProductId().toString(), "Venta confirmada: " + sold);
}
```

---

## CartService

**Propósito**: gestionar el carrito de compras de un comprador activo. Solo compradores `ACTIVE` operan el carrito; todos los ítems comparten una única moneda.

**Dependencias**: `Cart`, `Product`, `Buyer` · `ActiveBuyerSpecification` · `CartRepositoryPort`, `BuyerRepositoryPort`, `ProductRepositoryPort` · `AuditService`.

| Método                                        | Descripción                                    | Reglas aplicadas                                             | Auditoría           |
|-----------------------------------------------|------------------------------------------------|--------------------------------------------------------------|---------------------|
| `getOrCreateCart(buyerId, currency)`          | Obtiene o crea el carrito del comprador        | La moneda se fija al crear si no existe                      | —                   |
| `addItem(buyerId, cartItem)`                  | Agrega un ítem (suma cantidades si ya existe)  | `ActiveBuyerSpecification`; producto `ACTIVE`; moneda del ítem = moneda del carrito | `CART_ADD_ITEM`     |
| `updateQuantity(buyerId, productId, newQuantity)` | Actualiza la cantidad; `ZERO` elimina el ítem | —                                                            | —                   |
| `removeItem(buyerId, productId)`              | Elimina un ítem                                | —                                                            | `CART_REMOVE_ITEM`  |
| `clear(buyerId)`                              | Vacía el carrito                               | —                                                            | `CART_CLEAR`        |

```java
public void addItem(BuyerId buyerId, CartItem item) {
    Buyer buyer = buyers.findById(buyerId)
            .orElseThrow(() -> new BusinessException("BUYER_NOT_FOUND", "Comprador inexistente."));
    if (!activeBuyer.isSatisfiedBy(buyer)) {
        throw new BusinessException("INACTIVE_BUYER", "El comprador no puede operar el carrito.");
    }
    Cart cart = getOrCreateCart(buyerId, item.getUnitPrice().getCurrency());
    cart.addItem(item);
    carts.save(cart);
    audit.record(OperationType.CART_ADD_ITEM, AuditSeverity.INFO,
            buyerId.toString(), "Ítem agregado: " + item.getProductId());
}
```

---

## OrderService

**Propósito**: núcleo de la compra: crear el pedido desde el carrito, confirmar el pago, despachar, entregar y cancelar. Coordina carrito, inventario, factura y envío.

**Dependencias**: `Order`, `Cart`, `Buyer` · `ActiveBuyerSpecification`, `OrderModifiableSpecification` · `OrderRepositoryPort`, `CartRepositoryPort`, `BuyerRepositoryPort` · `InventoryService`, `InvoiceService`, `ShippingService` · `PaymentServicePort` · `AuditService`.

| Método                                    | Descripción                                             | Reglas aplicadas                                                        | Auditoría             |
|-------------------------------------------|---------------------------------------------------------|-------------------------------------------------------------------------|-----------------------|
| `createOrder(buyerId, shippingAddress)`   | Congela los ítems del carrito (`OrderItem.from`) y crea el pedido `PENDING_PAYMENT`; reserva inventario de los ítems físicos | `ActiveBuyerSpecification`; carrito no vacío; `InventoryService.reserve` por producto físico | `ORDER_CREATION`      |
| `confirmPayment(orderId, confirmation)`   | `PENDING_PAYMENT → PAID`; confirma la venta del inventario reservado; genera la factura | `OrderModifiableSpecification`; `PaymentServicePort.charge` → `PaymentConfirmation` | `ORDER_PAYMENT`       |
| `dispatch(orderId)`                       | `PAID → DISPATCHED`; crea el envío                       | —                                                                       | `ORDER_DISPATCH`      |
| `deliver(orderId)`                        | `DISPATCHED → DELIVERED`; asigna `deliveredAt`           | `Order.deliver()`                                                       | `ORDER_DELIVERY`      |
| `cancel(orderId)`                         | `PENDING_PAYMENT o PAID → CANCELLED`; libera reservas; anula la factura si existe | `OrderModifiableSpecification`                                          | `ORDER_CANCELLATION`  |

```java
public Order createOrder(BuyerId buyerId, Address shippingAddress) {
    Buyer buyer = buyers.findById(buyerId)
            .orElseThrow(() -> new BusinessException("BUYER_NOT_FOUND", "Comprador inexistente."));
    if (!activeBuyer.isSatisfiedBy(buyer)) {
        throw new BusinessException("INACTIVE_BUYER", "El comprador no puede crear pedidos.");
    }
    Cart cart = carts.findByBuyerId(buyerId)
            .orElseThrow(() -> new BusinessException("EMPTY_CART", "El comprador no tiene carrito."));
    if (cart.getItems().isEmpty()) {
        throw new BusinessException("EMPTY_CART", "No se puede crear un pedido desde un carrito vacío.");
    }
    Order order = orders.save(Order.create(buyerId,
            cart.getItems().stream().map(OrderItem::from).toList(), shippingAddress));
    for (CartItem item : cart.getItems()) {
        if (item.getProductType() == ProductType.PHYSICAL) {
            inventoryService.reserveForProduct(item.getProductId(), item.getQuantity(), order.getOrderId());
        }
    }
    audit.record(OperationType.ORDER_CREATION, AuditSeverity.INFO,
            buyerId.toString(), "Pedido creado: " + order.getOrderId());
    return order;
}
```

> **Decisión de diseño**: en la versión actual un pedido consolida productos de **un único vendedor** (por ello `Invoice` exige `sellerId` y `Order` posee una sola `invoiceId`). La expansión multi-vendedor se documentará como evolución futura del modelo.

---

## ShippingService

**Propósito**: gestionar el ciclo de vida de los envíos físicos en sincronía con el estado del pedido.

**Dependencias**: `Shipping`, `Order` · `ShippingRepositoryPort`, `OrderRepositoryPort` · `ShippingProviderPort` · `AuditService`.

| Método                                       | Descripción                                        | Reglas aplicadas                                      | Auditoría           |
|----------------------------------------------|----------------------------------------------------|-------------------------------------------------------|---------------------|
| `createShipping(orderId, warehouseId)`       | Crea el envío en `PREPARING`                       | Solo pedidos `PAID` (lo invoca `OrderService.dispatch`) | `SHIPPING_CREATION` |
| `dispatchShipping(shippingId, deliveryInfo)` | `PREPARING → DISPATCHED`; obtiene tracking de la transportadora | `ShippingProviderPort.createShipment`; `DeliveryInfo` obligatorio | `SHIPPING_UPDATE` |
| `markInTransit(shippingId)`                  | `DISPATCHED → IN_TRANSIT`                          | —                                                     | `SHIPPING_UPDATE`   |
| `deliver(shippingId)`                        | `IN_TRANSIT → DELIVERED`; marca el pedido entregado | `OrderService.deliver` (asigna `deliveredAt`)        | `SHIPPING_UPDATE`   |

---

## ReturnRefundService

**Propósito**: gestionar devoluciones de pedidos entregados y sus reembolsos asociados. La elegibilidad depende del plazo configurado.

**Dependencias**: `Return`, `Refund`, `Order` · `RefundEligibilitySpecification` · `ReturnRepositoryPort`, `RefundRepositoryPort`, `OrderRepositoryPort` · `PaymentServicePort`, `BusinessConfigurationPort` · `InventoryService` · `AuditService`.

| Método                              | Descripción                                     | Reglas aplicadas                                                       | Auditoría           |
|-------------------------------------|-------------------------------------------------|------------------------------------------------------------------------|---------------------|
| `requestReturn(orderId, items)`     | Crea la devolución en `REQUESTED`               | `RefundEligibilitySpecification` (pedido `DELIVERED`, dentro del plazo, cantidades > 0) | `RETURN_REQUEST`    |
| `approveReturn(returnId)`           | `REQUESTED → APPROVED`; crea el `Refund` `PENDING` | `Return.approve()`; `Refund.createFromReturn(...)`                   | `RETURN_APPROVAL`   |
| `rejectReturn(returnId)`            | `REQUESTED → REJECTED`                          | `Return.reject()`                                                      | `RETURN_REJECTION`  |
| `processRefund(refundId)`           | Procesa el reembolso contra la pasarela         | `PaymentServicePort.refund`; `Refund.process()` / `Refund.fail()`     | `REFUND_PROCESS`    |
| `processReturn(returnId)`           | `APPROVED → PROCESSED`; repone inventario       | `InventoryService.receiveReturn` por cada ítem físico                 | —                   |

```java
public Return requestReturn(OrderId orderId, List<ReturnItem> items) {
    Order order = orders.findById(orderId)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Pedido inexistente."));
    for (ReturnItem item : items) {
        if (!eligibility.isSatisfiedBy(order, item)) {
            throw new BusinessException("RETURN_NOT_ELIGIBLE",
                    "El ítem no es elegible para devolución dentro del plazo vigente.");
        }
    }
    Return aReturn = returns.save(Return.request(orderId, items));
    audit.record(OperationType.RETURN_REQUEST, AuditSeverity.INFO,
            order.getBuyerId().toString(), "Devolución solicitada: " + aReturn.getReturnId());
    return aReturn;
}
```

---

## InvoiceService

**Propósito**: emitir facturas al confirmar el pago y anularlas si el pedido se cancela.

**Dependencias**: `Invoice`, `Order`, `Product` · `InvoiceRepositoryPort`, `OrderRepositoryPort`, `ProductRepositoryPort` · `AuditService`.

| Método                            | Descripción                                   | Reglas aplicadas                                     | Auditoría               |
|-----------------------------------|-----------------------------------------------|------------------------------------------------------|-------------------------|
| `generateInvoice(orderId)`        | Emite la factura `ISSUED` y asigna `order.invoiceId` | Pedido `PAID`; suma de ítems coincide con el total  | `INVOICE_GENERATION`    |
| `cancelInvoice(invoiceId)`        | Anula la factura `ISSUED → CANCELLED`         | Solo facturas `ISSUED`; dispone el `Refund` si ya hubo pago | `INVOICE_CANCELLATION`  |

> **Nota (pendiente)**: `Invoice` requiere `sellerId` pero `Order` no lo expone. Como el pedido es mono-vendedor, el `sellerId` se resuelve consultando el producto del primer ítem (`ProductRepositoryPort.findById(productId).getSellerId()`).

---

## AuditService

**Propósito**: registro central de auditoría. Todos los servicios de dominio delegan aquí el registro de sus operaciones.

**Dependencias**: `AuditLogRepositoryPort` · `OperationType`, `AuditSeverity`.

```java
public class AuditService {
    private final AuditLogRepositoryPort auditLog;

    public AuditService(AuditLogRepositoryPort auditLog) {
        this.auditLog = auditLog;
    }

    public void record(OperationType operation, AuditSeverity severity,
                       String actorId, String description) {
        auditLog.append(operation, severity, LocalDateTime.now(),
                actorId, description, Map.of());
    }
}
```

> **Decisión de diseño**: el adaptador MongoDB (`adapters.out.persistence.mongodb`) será el implementador natural de `AuditLogRepositoryPort`.

---

## Matriz Servicios → Puertos de Salida

| Servicio | Puertos de salida utilizados |
|----------|------------------------------|
| `UserManagementService` | `UserRepositoryPort`, `BuyerRepositoryPort`, `SellerRepositoryPort` |
| `ProductCatalogService` | `ProductRepositoryPort`, `InventoryRepositoryPort` |
| `InventoryService` | `InventoryRepositoryPort`, `WarehouseRepositoryPort` |
| `CartService` | `CartRepositoryPort`, `BuyerRepositoryPort`, `ProductRepositoryPort` |
| `OrderService` | `OrderRepositoryPort`, `CartRepositoryPort`, `BuyerRepositoryPort`, `InventoryRepositoryPort`, `InvoiceRepositoryPort`, `PaymentServicePort` |
| `ShippingService` | `ShippingRepositoryPort`, `OrderRepositoryPort`, `ShippingProviderPort` |
| `ReturnRefundService` | `ReturnRepositoryPort`, `RefundRepositoryPort`, `OrderRepositoryPort`, `PaymentServicePort`, `BusinessConfigurationPort` |
| `InvoiceService` | `InvoiceRepositoryPort`, `OrderRepositoryPort`, `ProductRepositoryPort` |
| `AuditService` | `AuditLogRepositoryPort` |

---

## Reglas Transversales

- Los servicios **no** definen reglas de negocio nuevas: orquestan los invariantes protegidos por los agregados y las especificaciones.
- Cada operación de escritura persiste el estado devuelto por el agregado y registra auditoría.
- Las transacciones multi-agregado (pago → confirmación de venta → factura) son responsabilidad de la capa de aplicación (adaptador de entrada), que encadena las operaciones atómicas del dominio.
- El registro de auditoría es **no transaccional** respecto a las escrituras principales: si el registro falla, la operación de negocio no debe revertirse (ver *Output Ports.md*).

---

## Referencias Cruzadas

- *Output Ports.md* — contratos de los puertos de salida utilizados por estos servicios.
- *Domain Model.md* — agregados y VOs que estos servicios orquestan.
- *Domain Specifications.md* — reglas declarativas aplicadas dentro de los servicios.
- *Software Architecture.md* — ubicación del paquete `domain.services` y restricciones de dependencia.