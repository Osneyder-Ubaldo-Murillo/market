# Output Ports – NexusMarket

## Introducción

Los **puertos de salida** son interfaces definidas dentro del dominio (`nexus.market.domain.ports.out`) que representan la frontera entre el negocio y los detalles tecnológicos: bases de datos, pasarelas de pago, transportadoras, notificaciones, seguridad y configuración.

- Los **servicios de dominio** dependen de estas interfaces, nunca de implementaciones concretas.
- Los **adaptadores de salida** (`adapters.out.*`) implementan los puertos.
- Ningún puerto expone tipos de infraestructura (entidades JPA, documentos MongoDB, tipos HTTP); solo se permiten tipos del dominio, `java.util.*` y tipos básicos de Java.

> **Decisión de diseño**: las consultas que pueden no devolver resultado usan `Optional<T>`; las colecciones usan `List<T>`. La conversión hacia/desde entidades de persistencia vive exclusivamente en los adaptadores (restricción 4 de *Software Architecture.md*).

> **Relación con las especificaciones**: `UniqueUserSpecification.UserLookup` y `ProductPublishableSpecification.InventoryRepository` no son puertos adicionales; son vistas funcionales mínimas que el adaptador correspondiente implementa (consulte *Domain Specifications.md*).

---

## Repositorios (Persistencia)

### UserRepositoryPort

**Propósito**: persistencia de `User`.
**Usado por**: `UserManagementService`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface UserRepositoryPort {
    User save(User user);
    Optional<User> findById(UserId userId);
    Optional<User> findByEmail(Email email);
    boolean existsByEmail(Email email);
    boolean existsByDocumentId(DocumentId documentId);
}
```

---

### BuyerRepositoryPort

**Propósito**: persistencia del perfil comercial `Buyer`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface BuyerRepositoryPort {
    Buyer save(Buyer buyer);
    Optional<Buyer> findById(BuyerId buyerId);
    Optional<Buyer> findByUserId(UserId userId);
}
```

### SellerRepositoryPort

**Propósito**: persistencia del perfil comercial `Seller`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface SellerRepositoryPort {
    Seller save(Seller seller);
    Optional<Seller> findById(SellerId sellerId);
    Optional<Seller> findByUserId(UserId userId);
    List<Seller> findByStatus(SellerStatus status);
}
```

### WarehouseRepositoryPort

**Propósito**: persistencia de `Warehouse`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface WarehouseRepositoryPort {
    Warehouse save(Warehouse warehouse);
    Optional<Warehouse> findById(WarehouseId warehouseId);
    List<Warehouse> findBySellerId(SellerId sellerId);
}
```

### ProductRepositoryPort

**Propósito**: persistencia de `Product` y consultas del catálogo.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface ProductRepositoryPort {
    Product save(Product product);
    Optional<Product> findById(ProductId productId);
    List<Product> findBySellerId(SellerId sellerId);
    List<Product> findByStatus(ProductStatus status);
    List<Product> findPublished();
}
```

### InventoryRepositoryPort

**Propósito**: persistencia de `Inventory`. Usada también por `ProductPublishableSpecification.InventoryRepository`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface InventoryRepositoryPort {
    Inventory save(Inventory inventory);
    Optional<Inventory> findById(InventoryId inventoryId);
    List<Inventory> findByProductId(ProductId productId);
    Optional<Inventory> findByProductIdAndWarehouseId(ProductId productId, WarehouseId warehouseId);
}
```

---

### CartRepositoryPort

**Propósito**: persistencia de `Cart`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface CartRepositoryPort {
    Cart save(Cart cart);
    Optional<Cart> findById(CartId cartId);
    Optional<Cart> findByBuyerId(BuyerId buyerId);
}
```

### OrderRepositoryPort

**Propósito**: persistencia de `Order`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface OrderRepositoryPort {
    Order save(Order order);
    Optional<Order> findById(OrderId orderId);
    List<Order> findByBuyerId(BuyerId buyerId);
}
```

### InvoiceRepositoryPort

**Propósito**: persistencia de `Invoice`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface InvoiceRepositoryPort {
    Invoice save(Invoice invoice);
    Optional<Invoice> findById(InvoiceId invoiceId);
    Optional<Invoice> findByOrderId(OrderId orderId);
}
```

### ShippingRepositoryPort

**Propósito**: persistencia de `Shipping`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface ShippingRepositoryPort {
    Shipping save(Shipping shipping);
    Optional<Shipping> findById(ShippingId shippingId);
    Optional<Shipping> findByOrderId(OrderId orderId);
}
```

### ReturnRepositoryPort

**Propósito**: persistencia de `Return`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface ReturnRepositoryPort {
    Return save(Return aReturn);
    Optional<Return> findById(ReturnId returnId);
    Optional<Return> findByOrderId(OrderId orderId);
}
```

### RefundRepositoryPort

**Propósito**: persistencia de `Refund`.
**Implementado por**: `adapters.out.persistence.mysql` (JPA).

```java
public interface RefundRepositoryPort {
    Refund save(Refund refund);
    Optional<Refund> findById(RefundId refundId);
    List<Refund> findByOrderId(OrderId orderId);
}
```

### AuditLogRepositoryPort

**Propósito**: persistencia del registro de auditoría (destino natural: MongoDB).
**Usado por**: `AuditService`.
**Implementado por**: `adapters.out.persistence.mongodb`.

```java
public interface AuditLogRepositoryPort {
    void append(OperationType operation, AuditSeverity severity, LocalDateTime occurredAt,
                String actorId, String description, Map<String, Object> metadata);
}
```

> **Decisión de diseño**: en lugar de introducir un agregado `AuditLogEntry`, la auditoría se registra con parámetros tipados del dominio (catálogos y VOs existentes). Si el volumen de metadatos crece, se podrá extraer un VO `AuditLogEntry` en una iteración futura sin cambiar el contrato del puerto.

---

## Servicios Externos (Puertos de integración)

### PaymentServicePort

**Propósito**: pasarela de pagos: cobro de pedidos y reembolsos.
**Usado por**: `OrderService`, `ReturnRefundService`.
**Implementado por**: `adapters.out.payment`.

```java
public interface PaymentServicePort {
    PaymentConfirmation charge(OrderId orderId, Money amount, String paymentMethod);
    PaymentConfirmation refund(OrderId orderId, Money amount, String originalTransactionId);
}
```

> **Decisión de diseño**: la confirmación devuelve el VO `PaymentConfirmation` (transacción, fecha y método), que `OrderService` y `ReturnRefundService` usan para actualizar el estado del pedido o del reembolso.

### ShippingProviderPort

**Propósito**: transportadora externa: creación y cancelación de envíos.
**Usado por**: `ShippingService`.
**Implementado por**: `adapters.out.shipping`.

```java
public interface ShippingProviderPort {
    DeliveryInfo createShipment(OrderId orderId, Address destination, WarehouseId originWarehouseId);
    void cancelShipment(String trackingNumber);
}
```

### NotificationPort

**Propósito**: envío de notificaciones por canal (`NotificationChannel`).
**Usado por**: capa de aplicación (orquestador de eventos).
**Implementado por**: `adapters.out.notification`.

```java
public interface NotificationPort {
    void send(NotificationChannel channel, String recipient, String subject, String content);
}
```

### PasswordServicePort

**Propósito**: hash y verificación de contraseñas.
**Usado por**: `UserManagementService` (vía capa de aplicación).
**Implementado por**: `adapters.out.security` (BCrypt/Argon2).

```java
public interface PasswordServicePort {
    String hash(String rawPassword);
    boolean verify(String rawPassword, String hashedPassword);
}
```

---

### JwtServicePort

**Propósito**: emisión y validación de tokens JWT.
**Usado por**: capa de aplicación (autenticación y autorización).
**Implementado por**: `adapters.out.security`.

```java
public interface JwtServicePort {
    String issue(UserId userId, SystemRole role);
    boolean isValid(String token);
    Optional<UserId> getUserId(String token);
}
```

### BusinessConfigurationPort

**Propósito**: parámetros de negocio configurables (plazos, moneda, límites).
**Usado por**: `ReturnRefundService`, `CartService`.
**Implementado por**: `adapters.out.config` (propiedades o BD de configuración).

```java
public interface BusinessConfigurationPort {
    int refundMaxDays();
    Currency defaultCurrency();
    int maxAdditionalAddresses();
    int maxWarehousesPerSeller();
}
```

---

## Matriz de Puertos → Adaptadores

| Puerto                      | Adaptador destino         | Servicio cliente                                  |
|-----------------------------|---------------------------|---------------------------------------------------|
| `UserRepositoryPort`        | MySQL (JPA)               | `UserManagementService`                           |
| `BuyerRepositoryPort`       | MySQL (JPA)               | `UserManagementService`, `CartService`, `OrderService` |
| `SellerRepositoryPort`      | MySQL (JPA)               | `UserManagementService`                           |
| `WarehouseRepositoryPort`   | MySQL (JPA)               | `InventoryService`                                |
| `ProductRepositoryPort`     | MySQL (JPA)               | `ProductCatalogService`, `CartService`, `InvoiceService` |
| `InventoryRepositoryPort`   | MySQL (JPA)               | `ProductCatalogService`, `InventoryService`, `OrderService` |
| `CartRepositoryPort`        | MySQL (JPA)               | `CartService`, `OrderService`                     |
| `OrderRepositoryPort`       | MySQL (JPA)               | `OrderService`, `ShippingService`, `ReturnRefundService`, `InvoiceService` |
| `InvoiceRepositoryPort`     | MySQL (JPA)               | `OrderService`, `InvoiceService`                  |
| `ShippingRepositoryPort`    | MySQL (JPA)               | `ShippingService`                                 |
| `ReturnRepositoryPort`      | MySQL (JPA)               | `ReturnRefundService`                             |
| `RefundRepositoryPort`      | MySQL (JPA)               | `ReturnRefundService`                             |
| `AuditLogRepositoryPort`    | MongoDB                   | `AuditService`                                    |
| `PaymentServicePort`        | Pasarela de pagos         | `OrderService`, `ReturnRefundService`             |
| `ShippingProviderPort`      | Transportadora            | `ShippingService`                                 |
| `NotificationPort`          | Correo/SMS/Push           | Capa de aplicación                                |
| `PasswordServicePort`       | Seguridad (BCrypt)        | Capa de aplicación                                |
| `JwtServicePort`            | Seguridad (JWT)           | Capa de aplicación                                |
| `BusinessConfigurationPort` | Configuración             | `ReturnRefundService`, `CartService`              |

---

## Reglas de Implementación

1. Un puerto es solo una **interfaz**: no puede contener lógica ni referencias a Spring/JPA/MongoDB/HTTP.
2. Los adaptadores implementan los puertos y son los únicos que conocen las bibliotecas de infraestructura.
3. Cambiar de base de datos, moneda o proveedor externo **no** modifica el dominio: basta con implementar el puerto de nuevo.
4. Toda excepción de servicios externos debe traducirse, en el adaptador, a `BusinessException` o a las excepciones de dominio correspondientes.
5. `AuditLogRepositoryPort` es el único puerto cuyo fallo **no** revierte la operación de negocio (se recomienda registro asíncrono).

---

## Referencias Cruzadas

- *Domain Services.md* — matriz completa de servicios que consumen estos puertos.
- *Software Architecture.md* — estructura de paquetes de `adapters.out` y restricciones arquitectónicas.
- *Domain Specifications.md* — interfaces funcionales (`UserLookup`, `InventoryRepository`) implementadas por los adaptadores.