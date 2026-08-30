# Domain Specifications – NexusMarket

## Introducción

Las especificaciones encapsulan reglas de negocio reutilizables que pueden evaluarse en distintos contextos. Cada especificación expone `isSatisfiedBy(...)` (booleano). Viven en `nexus.market.domain.specifications`.

> **Decisión de diseño**: las especificaciones que necesitan datos externos (`UniqueUserSpecification`, `ProductPublishableSpecification`) **no dependen de puertos de infraestructura**; definen interfaces funcionales anidadas (`UserLookup`, `InventoryRepository`) que el servicio o adaptador implementa. Esto mantiene el paquete `domain` 100% libre de frameworks.

---

## AvailableInventorySpecification

**Propósito**: verificar que un inventario tenga suficiente cantidad disponible (descontando reservas) y no esté dañado.

```java
public class AvailableInventorySpecification {
    public boolean isSatisfiedBy(Inventory inventory, Quantity required) {
        if (inventory == null || required == null) return false;
        return inventory.getStatus() != InventoryStatus.DAMAGED
                && required.isLessThanOrEqual(inventory.getAvailableQuantity());
    }
}
```

---

## ActiveUserSpecification

**Propósito**: determinar si un usuario está activo.

```java
public class ActiveUserSpecification {
    public boolean isSatisfiedBy(User user) {
        return user != null && user.getStatus() == UserStatus.ACTIVE;
    }
}
```

---

## ActiveBuyerSpecification

**Propósito**: verificar que un comprador tenga estado comercial activo.

```java
public class ActiveBuyerSpecification {
    public boolean isSatisfiedBy(Buyer buyer) {
        return buyer != null && buyer.getCommercialStatus() == CommercialStatus.ACTIVE;
    }
}
```

---

## OrderModifiableSpecification

**Propósito**: determinar si un pedido puede modificarse (no debe estar entregado ni cancelado).

```java
public class OrderModifiableSpecification {
    public boolean isSatisfiedBy(Order order) {
        return order != null
                && order.getStatus() != OrderStatus.DELIVERED
                && order.getStatus() != OrderStatus.CANCELLED;
    }
}
```

---

## RefundEligibilitySpecification

**Propósito**: validar si un pedido entregado es elegible para devolución/reembolso (dentro del plazo definido).

```java
public class RefundEligibilitySpecification {
    private final int maxDays;

    public RefundEligibilitySpecification(int maxDays) {
        if (maxDays <= 0) {
            throw new BusinessException("INVALID_CONFIGURATION", "maxDays debe ser mayor que cero.");
        }
        this.maxDays = maxDays;
    }

    public boolean isSatisfiedBy(Order order, ReturnItem returnItem) {
        if (order == null || returnItem == null) return false;
        if (order.getStatus() != OrderStatus.DELIVERED) return false;
        if (order.getDeliveredAt() == null) return false;

        LocalDate deliveryDate = order.getDeliveredAt().toLocalDate();
        if (deliveryDate.isAfter(LocalDate.now())) return false;

        long days = ChronoUnit.DAYS.between(deliveryDate, LocalDate.now());
        return !(days > maxDays) && returnItem.getQuantity().isGreaterThan(Quantity.ZERO);
    }
}
```

> **Corrección**: la versión anterior usaba un método hipotético `order.getDeliveryDate()`. El modelo de dominio ahora expone `Order.deliveredAt`, que se asigna al ejecutar `deliver()`.

---

## UniqueUserSpecification

**Propósito**: verificar que el `email` y el `documentId` no estén ya registrados.

```java
public class UniqueUserSpecification {
    public interface UserLookup {
        boolean existsByEmail(Email email);
        boolean existsByDocumentId(DocumentId documentId);
    }

    private final UserLookup userLookup;

    public UniqueUserSpecification(UserLookup userLookup) {
        this.userLookup = Objects.requireNonNull(userLookup, "userLookup es obligatorio");
    }

    public boolean isSatisfiedBy(Email email, DocumentId documentId) {
        Objects.requireNonNull(email, "email es obligatorio");
        Objects.requireNonNull(documentId, "documentId es obligatorio");
        return !userLookup.existsByEmail(email)
                && !userLookup.existsByDocumentId(documentId);
    }
}
```

---

## ProductPublishableSpecification

**Propósito**: verificar que un producto pueda publicarse (tiene al menos una unidad disponible en alguna bodega, o es digital).

```java
public class ProductPublishableSpecification {
    @FunctionalInterface
    public interface InventoryRepository {
        List<Inventory> findByProductId(ProductId productId);
    }

    private final InventoryRepository inventoryRepository;

    public ProductPublishableSpecification(InventoryRepository inventoryRepository) {
        this.inventoryRepository = Objects.requireNonNull(inventoryRepository, "inventoryRepository es obligatorio");
    }

    public boolean isSatisfiedBy(Product product) {
        if (product == null) return false;
        // Los productos digitales no requieren inventario ni despacho.
        if (product.getProductType() == ProductType.DIGITAL) return true;

        List<Inventory> inventories = inventoryRepository.findByProductId(product.getProductId());
        if (inventories == null || inventories.isEmpty()) return false;

        return inventories.stream().anyMatch(inv ->
                inv.getStatus() == InventoryStatus.ACTIVE
                        && inv.getAvailableQuantity().isGreaterThan(Quantity.ZERO));
    }
}
```

> **Corrección**: la versión anterior comparaba contra `quantity` total sin descontar reservas y no contemplaba los productos digitales (que se entregan sin inventario). Ahora usa `availableQuantity()` y exime a `DIGITAL`.

---

## Uso en Servicios de Dominio

Estas especificaciones se instancian dentro de los servicios de dominio (o se les inyecta) para aplicar reglas de negocio de forma declarativa. Ejemplo:

```java
public class OrderService {
    private final AvailableInventorySpecification inventorySpec;
    private final OrderModifiableSpecification orderModSpec;

    public void confirmPayment(Order order) {
        if (!orderModSpec.isSatisfiedBy(order)) {
            throw new OrderNotModifiableException();
        }
        // ...
    }
}
```
