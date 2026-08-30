# Domain Model – NexusMarket

## Introducción

El modelo de dominio captura las entidades, agregados, objetos de valor y relaciones que representan el negocio de NexusMarket. Se basa en la especificación funcional y sigue los principios de Domain-Driven Design. Todos los modelos viven en `com.nexusmarket.domain.models` y **no tienen ninguna dependencia de frameworks** (Spring, JPA, etc.).

---

## Jerarquía de Clases del Dominio

### Roles del Sistema (SystemRole)

Los roles `BUYER`, `SELLER`, `LOGISTICS_OPERATOR`, `ADMIN` y `SUPERVISOR` se modelan **solo** mediante el atributo `role` de la entidad `User` (catálogo `SystemRole`). **No existen clases `LogisticsOperator`, `Admin` ni `Supervisor`** en el dominio; el comportamiento diferenciado se resuelve por rol. El perfil comercial de un `User` con rol `BUYER` o `SELLER` se materializa en los agregados `Buyer` y `Seller`.

> **Corrección**: la versión anterior listaba `LogisticsOperator`, `Admin` y `Supervisor` como agregados independientes. No tienen estado propio ni comportamiento diferenciado, por lo que se representan como roles sobre `User`.

### Agregados Raíz

- `User` (Agregado Raíz)
- `Buyer` (Agregado Raíz, rol = BUYER)
- `Seller` (Agregado Raíz, rol = SELLER)
- `Warehouse` (Agregado Raíz)
- `Product` (Agregado Raíz)
- `Inventory` (Agregado Raíz)
- `Cart` (Agregado Raíz)
- `Order` (Agregado Raíz)
- `Invoice` (Agregado Raíz)
- `Shipping` (Agregado Raíz)
- `Return` (Agregado Raíz)
- `Refund` (Agregado Raíz)

---

## Relaciones entre Agregados

```
User (1) ──── (0..1) Buyer          // si role = BUYER
User (1) ──── (0..1) Seller         // si role = SELLER
Seller (1) ──── (0..*) Warehouse    // bodegas asociadas (máx. 10)
Seller (1) ──── (0..*) Product
Product (1) ──── (0..*) Inventory   // distribuido por bodega
Warehouse (1) ──── (0..*) Inventory
Buyer (1) ──── (1) Cart
Buyer (1) ──── (0..*) Order
Order (1) ──── (0..1) Invoice
Order (1) ──── (0..1) Shipping
Order (1) ──── (1..*) OrderItem
Order (1) ──── (0..1) Return
Return (1) ──── (0..1) Refund
```

---

## Descripción Detallada de Agregados

### 1. User (Agregado Raíz)

Representa a cualquier persona autorizada para interactuar con el sistema. Cada usuario tiene un único rol (RG‑02).

| Atributo     | Tipo             | Descripción |
|--------------|------------------|-------------|
| `userId`     | `UserId`         | Identificador único (UUID) |
| `fullName`   | `FullName`       | Nombre completo |
| `email`      | `Email`          | Correo electrónico (único en la plataforma) |
| `documentId` | `DocumentId`     | Documento de identidad (único) |
| `role`       | `SystemRole`     | Rol del usuario |
| `status`     | `UserStatus`     | ACTIVE, BLOCKED, INACTIVE |
| `createdAt`  | `LocalDateTime`  | Fecha de creación |
| `updatedAt`  | `LocalDateTime`  | Última actualización |

**Reglas**:
- `email` y `documentId` deben ser únicos (`UniqueUserSpecification`).
- El `role` es **inmutable**; si se requiere otro rol se crea un nuevo `User`.
- Transiciones de `status`: `ACTIVE ↔ BLOCKED`, `ACTIVE → INACTIVE` (métodos `block()`, `activate()`, `deactivate()`).

---

### 2. Buyer (Agregado Raíz)

Extiende la información de un `User` con rol BUYER.

| Atributo               | Tipo              | Descripción |
|------------------------|-------------------|-------------|
| `buyerId`              | `BuyerId`         | Identificador (UUID) |
| `userId`               | `UserId`          | Referencia al `User` |
| `mainAddress`          | `Address`         | Dirección principal de entrega |
| `additionalAddresses`  | `List<Address>`   | Direcciones secundarias (máx. 10) |
| `commercialStatus`     | `CommercialStatus`| ACTIVE, BLOCKED, INACTIVE |
| `createdAt`            | `LocalDateTime`   | |
| `updatedAt`            | `LocalDateTime`   | |

**Reglas**:
- El comprador nunca puede administrar información de otros compradores.
- `addAddress()` valida el límite de 10 direcciones adicionales.
- Solo un comprador con `commercialStatus = ACTIVE` puede crear pedidos (`ActiveBuyerSpecification`).

---

### 3. Seller (Agregado Raíz)

Extiende la información de un `User` con rol SELLER.

| Atributo       | Tipo                | Descripción |
|----------------|---------------------|-------------|
| `sellerId`     | `SellerId`          | Identificador (UUID) |
| `userId`       | `UserId`            | Referencia al `User` |
| `businessName` | `BusinessName`      | Razón social |
| `taxId`        | `TaxId`             | NIT o RUT (único) |
| `status`       | `SellerStatus`      | ACTIVE, BLOCKED, INACTIVE, PENDING_VERIFICATION |
| `warehouses`   | `List<WarehouseId>` | Bodegas asociadas (máx. 10) |
| `createdAt`    | `LocalDateTime`     | |
| `updatedAt`    | `LocalDateTime`     | |

**Reglas**:
- Los vendedores **no** pueden auto‑registrarse; solo el Administrador puede crearlos.
- Estado inicial `PENDING_VERIFICATION`; `approve()` lo lleva a `ACTIVE`.
- `addWarehouse()` valida el límite de 10 bodegas.

---

### 4. Warehouse (Agregado Raíz)

| Atributo      | Tipo              | Descripción |
|---------------|-------------------|-------------|
| `warehouseId` | `WarehouseId`     | Identificador (UUID) |
| `name`        | `String`          | Nombre de la bodega (3–120 caracteres) |
| `address`     | `Address`         | Ubicación |
| `type`        | `WarehouseType`   | MARKETPLACE o SELLER |
| `sellerId`    | `SellerId`        | Propietario: obligatorio si `type = SELLER`; nulo si `type = MARKETPLACE` |
| `status`      | `WarehouseStatus` | ACTIVE, INACTIVE |
| `createdAt`   | `LocalDateTime`   | |
| `updatedAt`   | `LocalDateTime`   | |

**Reglas**: solo las bodegas `ACTIVE` pueden recibir inventario.

---

### 5. Product (Agregado Raíz)

| Atributo         | Tipo                 | Descripción |
|------------------|----------------------|-------------|
| `productId`      | `ProductId`          | Identificador (UUID) |
| `sellerId`       | `SellerId`           | Vendedor propietario |
| `name`           | `ProductName`        | Nombre (3–100 caracteres) |
| `description`    | `ProductDescription` | Descripción (10–500 caracteres) |
| `productType`    | `ProductType`        | PHYSICAL o DIGITAL |
| `price`          | `Money`              | Precio unitario |
| `status`         | `ProductStatus`      | ACTIVE, INACTIVE, OUT_OF_STOCK |
| `specifications` | `Map<String, Object>`| Atributos técnicos adicionales (copia inmutable) |
| `createdAt`      | `LocalDateTime`      | |
| `updatedAt`      | `LocalDateTime`      | |

**Reglas**:
- Un producto `DIGITAL` se entrega inmediatamente después del pago y **no requiere inventario ni despacho**.
- Un producto `PHYSICAL` solo puede publicarse (`publish()`) si cumple `ProductPublishableSpecification`.

---

### 6. Inventory (Agregado Raíz)

| Atributo           | Tipo                      | Descripción |
|--------------------|---------------------------|-------------|
| `inventoryId`      | `InventoryId`             | Identificador (UUID) |
| `productId`        | `ProductId`               | Producto almacenado |
| `warehouseId`      | `WarehouseId`             | Bodega de almacenamiento |
| `quantity`         | `Quantity`                | Existencia total |
| `reservedQuantity` | `Quantity`                | Existencia reservada para pedidos en curso |
| `status`           | `InventoryStatus`         | ACTIVE, OUT_OF_STOCK, DAMAGED |
| `movements`        | `List<InventoryMovement>` | Historial de movimientos (inmutable) |
| `updatedAt`        | `LocalDateTime`           | |

**Reglas**:
- Invariante: `reservedQuantity ≤ quantity`, sin existencias negativas.
- `availableQuantity() = quantity − reservedQuantity` (nunca negativa).
- Solo inventario `ACTIVE` con `availableQuantity() ≥ requerida` puede reservarse (`AvailableInventorySpecification`).
- No se puede reservar ni vender inventario `DAMAGED`.
- Movimientos autorizados: `INCOME`, `RESERVATION`, `RELEASE`, `SALE`, `ADJUSTMENT`, `RETURN`.

---

### 7. Cart (Agregado Raíz)

| Atributo   | Tipo             | Descripción |
|------------|------------------|-------------|
| `cartId`   | `CartId`         | Identificador (UUID) |
| `buyerId`  | `BuyerId`        | Comprador propietario |
| `currency` | `Currency`       | Moneda única de los ítems (nuevo) |
| `items`    | `List<CartItem>` | Productos seleccionados (copia de datos del producto) |
| `total`    | `Money`          | Suma de subtotales (se recalcula automáticamente) |
| `createdAt`| `LocalDateTime`  | |
| `updatedAt`| `LocalDateTime`  | |

**Reglas**: `addItem()` agrupa por producto (suma cantidades), `updateQuantity()` con cantidad cero elimina el ítem, `clear()` vacía el carrito. El total se recalcula en cada operación.

> **Corrección**: se agrega `currency` porque `Money` exige una moneda y los subtotales deben compartir la misma moneda para poder sumarse.
---

### 8. Order (Agregado Raíz)

| Atributo          | Tipo              | Descripción |
|-------------------|-------------------|-------------|
| `orderId`         | `OrderId`         | Identificador (UUID) |
| `buyerId`         | `BuyerId`         | Comprador |
| `items`           | `List<OrderItem>` | Copia congelada de `CartItem` (inmutable) |
| `status`          | `OrderStatus`     | PENDING_PAYMENT, PAID, DISPATCHED, DELIVERED, CANCELLED |
| `total`           | `Money`           | Suma de los subtotales |
| `shippingAddress` | `Address`         | Dirección de entrega |
| `invoiceId`       | `InvoiceId`       | Opcional; se asigna al emitir la factura |
| `deliveredAt`     | `LocalDateTime`   | Opcional; se establece al entregar (nuevo) |
| `createdAt`       | `LocalDateTime`   | |
| `updatedAt`       | `LocalDateTime`   | |

**Flujo de estados**:

```
PENDING_PAYMENT → PAID → DISPATCHED → DELIVERED
PENDING_PAYMENT | PAID → CANCELLED
```

**Reglas**:
- Un pedido `DELIVERED` o `CANCELLED` no puede modificarse (`OrderModifiableSpecification`).
- `deliveredAt` se usa para calcular la elegibilidad de devoluciones (`RefundEligibilitySpecification`).
- El pedido consolida productos de **un único vendedor** en la versión actual; por ello `Invoice` requiere `sellerId` y el pedido tiene una sola `invoiceId`. Si se habilita multi‑vendedor, la factura se emitirá por vendedor.

---

### 9. Invoice (Agregado Raíz)

| Atributo    | Tipo              | Descripción |
|-------------|-------------------|-------------|
| `invoiceId` | `InvoiceId`       | Identificador (UUID) |
| `orderId`   | `OrderId`         | Pedido de origen |
| `buyerId`   | `BuyerId`         | Comprador facturado (reemplaza a `buyerInfo: String`) |
| `sellerId`  | `SellerId`        | Vendedor facturado (reemplaza a `sellerInfo: String`) |
| `items`     | `List<OrderItem>` | Copia congelada de `OrderItem` |
| `total`     | `Money`           | Suma verificada contra el pedido |
| `issueDate` | `LocalDateTime`   | Fecha de emisión |
| `status`    | `InvoiceStatus`   | ISSUED, CANCELLED |

**Reglas**: solo se cancela una factura `ISSUED`. Si el pedido se cancela tras el pago, la factura debe anularse (`INVOICE_CANCELLATION`).

> **Corrección**: `buyerInfo` y `sellerInfo` eran `String`. Se reemplazan por referencias tipadas `BuyerId`/`SellerId`; los datos de facturación (nombre, documento, razón social, NIT) se derivan de `User`/`Seller` en el momento de emitir.

---

### 10. Shipping (Agregado Raíz)

| Atributo      | Tipo             | Descripción |
|---------------|------------------|-------------|
| `shippingId`  | `ShippingId`     | Identificador (UUID) |
| `orderId`     | `OrderId`        | Pedido asociado |
| `warehouseId` | `WarehouseId`    | Bodega de origen |
| `status`      | `ShippingStatus` | PREPARING, DISPATCHED, IN_TRANSIT, DELIVERED |
| `deliveryInfo`| `DeliveryInfo`   | trackingNumber, carrier, estimatedDate |
| `actualDate`  | `LocalDate`      | Fecha real de entrega (se asigna al entregar) |
| `createdAt`   | `LocalDateTime`  | |
| `updatedAt`   | `LocalDateTime`  | |

**Flujo de estados**: `PREPARING → DISPATCHED → IN_TRANSIT → DELIVERED`.

> **Corrección**: los campos `trackingNumber` y `estimatedDate` se agrupan en el VO `DeliveryInfo` para mantener coherencia con el catálogo de Value Objects y facilitar la integración con transportadoras.

---

### 11. Return (Agregado Raíz)

| Atributo        | Tipo               | Descripción |
|-----------------|--------------------|-------------|
| `returnId`      | `ReturnId`         | Identificador (UUID) |
| `orderId`       | `OrderId`          | Pedido de origen |
| `items`         | `List<ReturnItem>` | Productos devueltos; cada ítem tiene cantidad y motivo |
| `status`        | `ReturnStatus`     | REQUESTED, APPROVED, REJECTED, PROCESSED |
| `requestDate`   | `LocalDateTime`    | Fecha de solicitud |
| `resolutionDate`| `LocalDateTime`    | Fecha de resolución |

**Reglas**: `approve()` → APPROVED, `reject()` → REJECTED (solo desde REQUESTED); `process()` → PROCESSED (solo desde APPROVED). Al aprobar, se crea el `Refund`.

> **Corrección**: la versión anterior tenía dos campos redundantes: `reason` a nivel de devolución e `items` con motivo por ítem. El motivo es **por ítem devuelto** (`ReturnItem.reason`); se elimina el campo `reason` agregado.

---

### 12. Refund (Agregado Raíz)

| Atributo        | Tipo             | Descripción |
|-----------------|------------------|-------------|
| `refundId`      | `RefundId`       | Identificador (UUID) |
| `returnId`      | `ReturnId`       | Opcional; presente si el reembolso proviene de una devolución |
| `orderId`       | `OrderId`        | Pedido de origen |
| `amount`        | `Money`          | Monto a reembolsar |
| `status`        | `RefundStatus`   | PENDING, PROCESSED, FAILED |
| `requestDate`   | `LocalDateTime`  | |
| `processedDate` | `LocalDateTime`  | Se asigna al procesar o fallar |

**Reglas**: `process()` → PROCESSED, `fail()` → FAILED (solo desde PENDING).

---

## Reglas de Negocio Transversales

- `Email` y `DocumentId` de `User` únicos en toda la plataforma (`UniqueUserSpecification`).
- Solo usuarios `ACTIVE` operan (`ActiveUserSpecification`); solo compradores `ACTIVE` compran (`ActiveBuyerSpecification`).
- Los catálogos (`OrderStatus`, `ShippingStatus`, etc.) no son `enum` de Java (ver *Domain Value Objects.md*).
- Todo cambio de existencias queda registrado en `Inventory.movements` (auditoría).
- Los límites de máx. 10 direcciones y máx. 10 bodegas se validan dentro de los agregados `Buyer` y `Seller`.

---

## Referencias Cruzadas

| Modelo     | Value Objects usados |
|------------|----------------------|
| User       | UserId, FullName, Email, DocumentId, SystemRole, UserStatus |
| Buyer      | BuyerId, UserId, Address, CommercialStatus |
| Seller     | SellerId, UserId, BusinessName, TaxId, SellerStatus, WarehouseId |
| Warehouse  | WarehouseId, Address, WarehouseType, WarehouseStatus, SellerId |
| Product    | ProductId, SellerId, ProductName, ProductDescription, ProductType, Money, ProductStatus |
| Inventory  | InventoryId, ProductId, WarehouseId, Quantity, InventoryStatus, InventoryMovement |
| Cart       | CartId, BuyerId, CartItem, Money |
| Order      | OrderId, BuyerId, OrderItem, Money, Address, OrderStatus, InvoiceId |
| Invoice    | InvoiceId, OrderId, BuyerId, SellerId, OrderItem, Money, InvoiceStatus |
| Shipping   | ShippingId, OrderId, WarehouseId, DeliveryInfo, ShippingStatus |
| Return     | ReturnId, OrderId, ReturnItem, ReturnStatus, ReturnReason |
| Refund     | RefundId, ReturnId, OrderId, Money, RefundStatus |
