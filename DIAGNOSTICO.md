# Diagnóstico del Proyecto NexusMarket

**Fecha:** 25/09/2026 — **Actualizado:** 26/09/2026 (adaptadores de salida)
**Rama:** `main`
**Comando de verificación:** `cd market && ./mvnw.cmd clean test`

---

## 1. Resumen ejecutivo del diagnóstico

| Área | Estado |
|------|--------|
| Compilación del dominio (73 fuentes, Java 17) | ✅ OK |
| `MarketApplicationTests.contextLoads` (contexto Spring) | ✅ OK (corregido) |
| Suite de pruebas del dominio (52 tests) | ✅ OK (corregido; 3 fallos iniciales resueltos) |
| Servicios de dominio (`domain.services`) | ✅ Implementados (commit `297adac`) |
| Puertos de salida (`domain.ports.out`) | ✅ Implementados (commit `297adac`) |
| Adaptadores de salida (`adapters.out.*`) | ✅ Implementados (JPA/MySQL, MongoDB, pagos, envío, notificaciones, seguridad, configuración) |
| Tests de integración JPA (H2) + adaptadores externos | ✅ 9 tests nuevos — **61 tests totales, 0 fallos** |
| Adaptadores de entrada (`adapters.in.*`) | ❌ No existen |
| Capa de aplicación / infraestructura | ❌ No existe |

---

## 2. Puntos con fallos encontrados y corregidos

### 2.1 [CRÍTICO] `mvn test` fallaba: `Failed to determine a suitable driver class`

- **Síntoma:** el test `MarketApplicationTests.contextLoads` (y cualquier arranque de la
  aplicación) fallaba al crear el bean `dataSource` (Hikari) por no poder determinar
  una clase de driver JDBC.
- **Causa raíz:** el `pom.xml` declara `spring-boot-starter-data-jpa`,
  `spring-boot-starter-mongodb`, etc. En Spring Boot 4.1 esto activa
  `DataSourceAutoConfiguration` (Hikari) y `HibernateJpaAutoConfiguration`. Como el
  proyecto aún es **solo dominio** (no hay adaptadores de persistencia ni
  `spring.datasource.url`), el contexto no puede resolverse.
- **Corrección aplicada** en `src/main/resources/application.properties`:

  ```properties
  spring.autoconfigure.exclude=\
    org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,\
    org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration,\
    org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
  ```

  > Los nombres de clase se verificaron dentro de `spring-boot-jdbc-4.1.0.jar` y
  > `spring-boot-hibernate-4.1.0.jar`. Cuando se implementen los adaptadores de
  > infraestructura hay que **quitar estas exclusiones** y declarar la URL de conexión.

### 2.2 [CRÍTICO] Bug lógico en `Inventory.confirmSale`

- **Síntoma (detectado por test):** tras `reserve(2)` sobre stock de 2
  (disponible = 0), `confirmSale(2)` lanzaba `InsufficientInventoryException`.
- **Causa:** `confirmSale` validaba `sold > getAvailableQuantity()` en lugar de
  `sold > reservedQuantity`. Solo se debería poder confirmar la venta de lo
  **previamente reservado**.
- **Corrección aplicada** en `domain/models/Inventory.java`: validación contra
  `reservedQuantity` y mensaje acorde.

### 2.3 [MEDIO] Catálogo `OperationType` incompleto

- La SDD (`SDD/domain/Domain Services.md`) marcaba como pendientes los códigos
  `SELLER_APPROVAL`, `USER_BLOCK`, `USER_ACTIVATION`, `USER_DEACTIVATION`.
- Además `InventoryMovementType` tiene `RETURN` pero `OperationType` no tenía
  ningún código de devolución de inventario.
- **Corrección aplicada** en `domain/valueobjects/OperationType.java`: se agregaron
  los 4 códigos pendientes + `INVENTORY_RETURN`.

### 2.4 [BAJO] Errores en los tests nuevos (iniciales)

- `OrderLifecycleTest.pedidoConItemsEnMonedasDistintasEsInvalido` usaba nombres de
  producto de 1 carácter (inválidos); se corrigió a nombres válidos para probar
  realmente la regla de moneda.
- `InventoryTest.ajusteFisicoRegistraDelta` esperaba `delta = 2` para un ajuste
  10 → 8; el contrato correcto es `delta firmado = -2` (disminución).

---

## 3. Qué falta según la SDD (hoja de ruta)

La SDD (segunda entrega) documenta los servicios y puertos. **Estado actual:**
servicios y puertos implementados (commit `297adac`); **adaptadores de salida
implementados** (persistencia JPA/MySQL con Hibernate, auditoría MongoDB y
adaptadores externos). Inventario de pendientes:

### 3.1 Servicios de dominio (`nexus.market.domain.services`) — ✅ Implementados (9)

| Servicio | Dependencias de puertos principales |
|----------|-------------------------------------|
| `UserManagementService` | `UserRepositoryPort`, `BuyerRepositoryPort`, `SellerRepositoryPort` |
| `ProductCatalogService` | `ProductRepositoryPort`, `InventoryRepositoryPort` |
| `InventoryService` | `InventoryRepositoryPort`, `WarehouseRepositoryPort` |
| `CartService` | `CartRepositoryPort`, `BuyerRepositoryPort`, `ProductRepositoryPort` |
| `OrderService` | `OrderRepositoryPort`, `CartRepositoryPort`, `InventoryRepositoryPort`, `InvoiceRepositoryPort`, `PaymentServicePort` |
| `ShippingService` | `ShippingRepositoryPort`, `OrderRepositoryPort`, `ShippingProviderPort` |
| `ReturnRefundService` | `ReturnRepositoryPort`, `RefundRepositoryPort`, `OrderRepositoryPort`, `PaymentServicePort`, `BusinessConfigurationPort` |
| `InvoiceService` | `InvoiceRepositoryPort`, `OrderRepositoryPort`, `ProductRepositoryPort` |
| `AuditService` | `AuditLogRepositoryPort` |

### 3.2 Puertos de salida (`nexus.market.domain.ports.out`) — ✅ Implementados (19)

`UserRepositoryPort`, `BuyerRepositoryPort`, `SellerRepositoryPort`,
`WarehouseRepositoryPort`, `ProductRepositoryPort`, `InventoryRepositoryPort`,
`CartRepositoryPort`, `OrderRepositoryPort`, `InvoiceRepositoryPort`,
`ShippingRepositoryPort`, `ReturnRepositoryPort`, `RefundRepositoryPort`,
`AuditLogRepositoryPort`, `PaymentServicePort`, `ShippingProviderPort`,
`NotificationPort`, `PasswordServicePort`, `JwtServicePort`,
`BusinessConfigurationPort`.

> **Nota de diseño (SDD):** `UniqueUserSpecification.UserLookup` y
> `ProductPublishableSpecification.InventoryRepository` no son puertos adicionales;
> son vistas funcionales que los adaptadores implementan y ya existen en el código.

### 3.3 Adaptadores (`adapters`) — salida ✅ / entrada ❌

- ✅ `adapters.out.persistence.mysql` — los 12 repositorios implementados con JPA
  puro (`EntityManagerFactory`), entidades + mapeo dominio↔persistencia en cada
  archivo `*Persistence.java`. **Activación:** perfil `jpa`
  (`application-jpa.properties`: MySQL real; los tests usan H2 en memoria).
- ✅ `adapters.out.persistence.mongodb` — `AuditLogRepositoryPort` con driver
  oficial de MongoDB 5.8. **Activación:** perfil `mongodb`.
- ✅ `adapters.out.*` externos — `LoggingPaymentServicePort` (pasarela simulada),
  `SimulatedShippingProviderPort`, `LoggingNotificationPort`,
  `BcryptPasswordServicePort`, `JwtJwtServicePort` (HS256, `com.auth0:java-jwt`),
  `PropertyBusinessConfigurationPort` (propiedades `nexusmarket.*`).
- ❌ `adapters.in.*` — controladores REST / DTOs / validación de entrada.

### 3.4 Infraestructura / aplicación

- Configuración de seguridad (Spring Security + JWT) — **parcial**: los adaptadores
  BCrypt/JWT existen; falta el filtro/autorización HTTP.
- Manejo global de `BusinessException` → código HTTP.
- Validación de entrada y casos de uso (puertos de entrada).
- `application-jpa.properties` con credenciales reales para ejecución
  con MySQL/MongoDB locales (ya creado; ajustar credenciales).

---

## 4. Riesgos y deudas técnicas detectadas

1. **`Invoice` requiere `sellerId`** pero `Order` no lo expone; la SDD propone
   resolverlo consultando el producto del primer ítem
   (`ProductRepositoryPort.findById(productId).getSellerId()`) — pendiente en
   `InvoiceService`.
2. **`OperationType` aún no cubre** `REFUND_FAILED`, `RETURN_PROCESS` (anotado en
   la SDD como iteración futura).
3. **`Quantity`** usa `int` para existencias: riesgo de desbordamiento con
   `Math.addExact` (si se lanza `ArithmeticException` no es un
   `BusinessException`). Considerar `long` en una iteración futura.
4. **`Spring Boot 4.1.0`** requiere Java 17+; el entorno local usa Java 25. El
   proyecto se compila con `release 17` (correcto).
5. **MongoDB y MySQL** son dependencias declaradas sin adaptadores: el arranque
   real (`mvn spring-boot:run`) solo es posible con las exclusiones activas hasta
   que existan los adaptadores.

---

## 5. Cómo validar

```bash
cd market
./mvnw.cmd clean test
```

Resultado esperado: **61 tests, 0 fallos, 0 errores**, incluyendo
`MarketApplicationTests.contextLoads` (52 de dominio + 4 de adaptadores
externos + 5 de integración JPA sobre H2).