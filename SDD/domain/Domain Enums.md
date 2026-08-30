# Domain Enums – NexusMarket

## Introducción

Los **enums técnicos** representan valores fijos sin metadata adicional de negocio (no heredan de `DomainCatalog`). Se utilizan para conceptos puramente técnicos o de infraestructura y residen en el paquete `com.nexusmarket.domain.enums`.

> Los catálogos de negocio (`SystemRole`, `OrderStatus`, `ShippingStatus`, `ReturnReason`, etc.) **NO son enums de Java**: son clases inmutables que heredan de `DomainCatalog`. Consulte *Domain Value Objects.md*.

---

## InventoryMovementType

Representa el tipo de movimiento de inventario. Todo cambio de existencias en `Inventory` debe quedar registrado con uno de estos movimientos.

| Valor       | Descripción |
|-------------|-------------|
| INCOME      | Ingreso de mercancía a la bodega |
| RESERVATION | Reserva de existencias para un pedido en curso |
| RELEASE     | Liberación de una reserva (pedido cancelado o reserva anulada) |
| SALE        | Salida definitiva por venta confirmada |
| ADJUSTMENT  | Ajuste físico (merma, sobrante, corrección de conteo) |
| RETURN      | Devolución de productos a la bodega |

> **Corrección**: la versión anterior no incluía `RELEASE`, pero el catálogo `OperationType` define `INVENTORY_RELEASE`. Se agrega para mantener la coherencia entre catálogos y movimientos.

---

## NotificationChannel

Canal de notificación usado por el adaptador de notificaciones.

| Valor             | Descripción |
|-------------------|-------------|
| EMAIL             | Correo electrónico |
| SMS               | Mensaje de texto |
| PUSH_NOTIFICATION | Notificación push en aplicación móvil |

---

## AuditSeverity

Nivel de severidad de un evento de auditoría.

| Valor    | Descripción |
|----------|-------------|
| INFO     | Informativo |
| WARNING  | Advertencia |
| ERROR    | Error |
| CRITICAL | Crítico |
