# Domain Value Objects – NexusMarket

## Introducción

Los Value Objects son inmutables, se comparan por su valor y encapsulan validaciones y lógica de negocio asociada a los atributos. Se dividen en dos grupos: **catálogos de negocio** (heredan de `DomainCatalog`) y **objetos de valor básicos**.

---

## DomainCatalog (Abstracta)

Proporciona una estructura común para todos los catálogos de negocio.

| Atributo      | Tipo     | Descripción |
|---------------|----------|-------------|
| `code`        | String   | Identificador único del catálogo |
| `name`        | String   | Nombre legible |
| `description` | String   | Descripción del negocio |

```java
public abstract class DomainCatalog {
    private final String code;
    private final String name;
    private final String description;
    // constructor, getters, equals/hashCode basados en code
}
Catálogos de Negocio (herencia de DomainCatalog)
SystemRole
Código	Nombre	Descripción
BUYER	Comprador	Usuario que realiza compras
SELLER	Vendedor	Usuario que vende productos
LOGISTICS_OPERATOR	Operador Logístico	Encargado de bodegas y despachos
ADMIN	Administrador	Gestiona vendedores y bodegas
SUPERVISOR	Supervisor	Consulta y seguimiento operativo
UserStatus
Código	Nombre	Descripción
ACTIVE	Activo	Usuario puede operar normalmente
BLOCKED	Bloqueado	Acceso suspendido
INACTIVE	Inactivo	Usuario existente pero sin operaciones
CommercialStatus
Código	Nombre	Descripción
ACTIVE	Activo	Comprador habilitado para comprar
BLOCKED	Bloqueado	Comprador suspendido
INACTIVE	Inactivo	Comprador sin actividad
SellerStatus
Código	Nombre	Descripción
ACTIVE	Activo	Vendedor habilitado
BLOCKED	Bloqueado	Vendedor suspendido
INACTIVE	Inactivo	Vendedor inactivo
PENDING_VERIFICATION	Pendiente de Verificación	En espera de aprobación
ProductStatus
Código	Nombre	Descripción
ACTIVE	Activo	Producto visible en catálogo
INACTIVE	Inactivo	Producto oculto
OUT_OF_STOCK	Sin Stock	Producto agotado
InventoryStatus
Código	Nombre	Descripción
ACTIVE	Activo	Disponible para venta
OUT_OF_STOCK	Sin Stock	Agotado
DAMAGED	Dañado	No disponible para venta
OrderStatus
Código	Nombre	Descripción
CART	Carrito	Selección provisional
PENDING_PAYMENT	Pendiente de Pago	Espera confirmación financiera
PAID	Pagado	Inicio de alistamiento
DISPATCHED	Despachado	Salida física de la bodega
DELIVERED	Entregado	Conclusión satisfactoria
CANCELLED	Cancelado	Pedido anulado
ProductType
Código	Nombre	Descripción
PHYSICAL	Físico	Requiere inventario y despacho
DIGITAL	Digital	Entrega inmediata tras pago
WarehouseType
Código	Nombre	Descripción
MARKETPLACE	Marketplace	Bodega central de la plataforma
SELLER	Vendedor	Bodega privada de un vendedor
WarehouseStatus
Código	Nombre	Descripción
ACTIVE	Activa	Bodega operativa
INACTIVE	Inactiva	Bodega fuera de servicio
ShippingStatus
Código	Nombre	Descripción
PREPARING	Preparación	En proceso de alistamiento
DISPATCHED	Despachado	Salida de la bodega
IN_TRANSIT	En Tránsito	En camino al destino
DELIVERED	Entregado	Llegada confirmada
ReturnStatus
Código	Nombre	Descripción
REQUESTED	Solicitado	Devolución iniciada
APPROVED	Aprobado	Aceptada por el vendedor
REJECTED	Rechazado	Denegada
PROCESSED	Procesado	Completada
RefundStatus
Código	Nombre	Descripción
PENDING	Pendiente	Esperando procesamiento
PROCESSED	Procesado	Reembolso completado
FAILED	Fallido	Error en el reembolso
InvoiceStatus
Código	Nombre	Descripción
ISSUED	Emitida	Factura generada
CANCELLED	Anulada	Factura cancelada
ReturnReason
Código	Nombre	Descripción
DAMAGED	Producto Dañado	
WRONG_ITEM	Producto Incorrecto	
NOT_AS_DESCRIBED	No coincide con la descripción	
OTHER	Otro motivo	
OperationType
Lista extensa de operaciones de negocio (ver archivo de Enums o sección correspondiente). Incluye:

USER_REGISTRATION, SELLER_REGISTRATION, BUYER_REGISTRATION

PRODUCT_CREATION, PRODUCT_UPDATE, PRODUCT_PUBLISH, PRODUCT_UNPUBLISH

INVENTORY_ADD, INVENTORY_RESERVE, INVENTORY_CONFIRM_SALE, INVENTORY_RELEASE, INVENTORY_ADJUST

CART_ADD_ITEM, CART_REMOVE_ITEM, CART_CLEAR

ORDER_CREATION, ORDER_PAYMENT, ORDER_DISPATCH, ORDER_DELIVERY, ORDER_CANCELLATION

INVOICE_GENERATION, INVOICE_CANCELLATION

SHIPPING_CREATION, SHIPPING_UPDATE

RETURN_REQUEST, RETURN_APPROVAL, RETURN_REJECTION

REFUND_PROCESS

Value Objects Básicos
Email
Representa un correo electrónico con validación de formato.

Validación: formato válido, no vacío.

Método of(String value) que lanza excepción si es inválido.

DocumentId
Número de identificación (cédula, NIT, etc.).

Validación: no vacío, formato alfanumérico según país.

FullName
Nombre completo.

Validación: no vacío, longitud entre 2 y 100 caracteres.

BusinessName
Razón social.

Validación: no vacío, longitud mínima 3.

TaxId
NIT o RUT.

Validación: formato específico (ej. números).

Address
Dirección postal.

Campos: street, number, complement (opcional), neighborhood, city, state, postalCode, country.

Validación: todos los campos obligatorios excepto complemento; postalCode debe tener formato válido.

Money
Cantidad monetaria.

Atributos: BigDecimal amount, Currency currency.

Operaciones: add, subtract, multiply, compareTo.

Validación: amount no negativo.

Quantity
Cantidad entera no negativa.

Operaciones: add, subtract, isZero, isGreaterThan, isLessThanOrEqual.

ProductName
Longitud 3-100 caracteres.

ProductDescription
Longitud 10-500 caracteres.

Identificadores (UserId, BuyerId, SellerId, etc.)
Basados en UUID. Generación automática con UUID.randomUUID().

InventoryMovement
Atributos: InventoryMovementType type, Quantity quantity, LocalDateTime date, Map<String, Object> metadata.

Inmutable.

CartItem
Atributos: ProductId productId, ProductName productName, Quantity quantity, Money unitPrice, Money total, ProductType productType.

El total se calcula como unitPrice * quantity.

OrderItem
Similar a CartItem pero inmutable y sin métodos de modificación.

PaymentConfirmation
Atributos: String transactionId, LocalDateTime date, String paymentMethod.

DeliveryInfo
Atributos: String trackingNumber, String carrier, LocalDate estimatedDate.
