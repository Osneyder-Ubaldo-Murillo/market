# Transacciones y validación de longitudes

## Cómo funciona

- Un único `EntityManagerFactory` (MySQL) y su `JpaTransactionManager`. Los 13 adaptadores JPA
  heredan de `JpaRepositorySupport`.
- **Con transacción de Spring en curso** (caso de uso con `@Transactional`), todos los
  repositorios usan el `EntityManager` de esa transacción: commit o rollback conjunto, y las
  lecturas ven las escrituras previas. Cada escritura hace `flush`, de modo que una violación
  de restricciones falla en el paso que la causa.
- **Sin transacción**, cada escritura sigue siendo atómica por sí misma (comportamiento anterior).
- Rollback: cualquier `RuntimeException` (incluidas `BusinessException` y
  `IllegalArgumentException`) revierte la operación completa. No se captura ninguna excepción
  para devolver éxito.

## Casos de uso cubiertos (`@Transactional` en la capa de aplicación)

| Caso de uso | Escrituras relacionadas |
|---|---|
| `registerBuyer`, `registerSeller` | usuario + perfil + credenciales |
| `createOrder` | pedido + reserva de inventario (uno o varios) |
| `confirmPayment` | pedido + venta de inventario + factura (+ pedido) |
| `dispatch` | envío + pedido |
| `cancel` | pedido + factura + liberación de inventario |
| `ShippingApplicationService.deliver` | envío + pedido |
| `generateInvoice` | factura + pedido |
| `approveReturn` | reembolso + devolución |
| `processReturn` | ingreso de inventario (uno o varios) + devolución |

Actualización: además, `Order.deliver`, `Shipping.createShipping/dispatchShipping/markInTransit` y todas las
operaciones de escritura de `InventoryApplicationService` (`createInventory`, `addStock`, `reserve`,
`release`, `adjust`, `markDamaged`, `markActive`) son ahora transaccionales con aislamiento
`READ_COMMITTED` y bloqueo de fila (ver `CONCURRENCIA_BLOQUEOS.md`); los casos de uso de pedidos, envíos,
facturas y devoluciones listados arriba también usan `READ_COMMITTED`.

## No cubiertos, y por qué

- Casos de uso con **una sola escritura** (productos, carrito, bodegas, bloqueo/activación de
  usuarios, `requestReturn`, `rejectReturn`, `deliver` de pedido, inventario por id,
  `dispatchShipping`, `markInTransit`): cada escritura ya es atómica por sí sola.
- **Pasarela de pago y transportadora** (`PaymentServicePort`, `ShippingProviderPort`): son
  externas. Un rollback de MySQL no deshace un cobro o un envío ya realizado. Hoy son
  simulaciones que solo escriben en el log; el cobro simulado es determinista por pedido
  (idempotente). Con un proveedor real haría falta idempotencia con clave por pedido y una
  compensación o un patrón *outbox* (esto exige cambiar el esquema).
- **Auditoría (log/MongoDB)**: otra tecnología, sin transacción compartida. No se afirma
  atomicidad global. Solución aplicada: dentro de una transacción, el registro se difiere hasta
  *después del commit* (`TransactionAwareAuditConfiguration`), así que no queda constancia de
  operaciones revertidas. Límite: si el proceso cae entre el commit y el registro, esa entrada
  de auditoría se pierde. Un fallo de auditoría nunca revierte la operación (regla de la SDD).
- **Concurrencia**: no hay bloqueo optimista (`@Version`) ni pesimista; dos operaciones
  simultáneas sobre el mismo inventario podrían pisarse. Es una limitación previa, ajena a
  esta corrección.

## Longitudes (sin cambiar el esquema)

Los límites del dominio y de los DTO ahora coinciden con las columnas. Se rechaza con
`400` (`VALIDATION_ERROR` o el error de dominio) **antes** de escribir; nada se trunca.

| Campo | Límite | Origen |
|---|---|---|
| `email` | 160 | columna `users.email` |
| `businessName` | 160 (DTO REST: 100) | columna `sellers.business_name`; el dominio admitía 200 (decisión aprobada) |
| dirección: `street` / `complement` / `neighborhood` | 120 | columnas |
| dirección: `number` | 30 | columna |
| dirección: `city` / `state` / `country` | 80 | columnas |
| dirección: `postalCode` | 10 | columna |
| `trackingNumber` / `carrier` | 64 / 80 | columnas de `shippings` |
| `password` | mínimo 8 caracteres (política existente); máximo **72 bytes UTF-8** | límite de BCrypt; se rechaza con 400, nunca se trunca |
| montos | ≤ 9 999 999 999 999,99 | `decimal(15,2)` |

Además, los objetos anidados de las peticiones (`address`, `shippingAddress`, `price`, líneas de
devolución) no se validaban (faltaba `@Valid`): era la causa de que una ciudad de 200
caracteres llegara hasta la base de datos.

Sin cubrir: `product.specifications_json` y `inventory_movements.metadata_json` son
`tinytext` (255 bytes); hoy no hay API que los llene con texto del usuario.

## Producto: nombre y descripción (justificación)

- **SDD** (`Domain Model.md`, `Domain Value Objects.md`): nombre 3–100 y descripción 10–500
  caracteres. El código del dominio (`ProductName`, `ProductDescription`, sin modificar) ya
  aplicaba exactamente esos límites.
- **Especificación funcional** (PDF): no fija longitudes para el catálogo. Nota: el texto se
  extrajo con `pdftotext` y las tablas salen parcialmente; no se pudieron renderizar las páginas
  como imagen para confirmarlo visualmente.
- **Esquema**: `products.name varchar(120)` y `products.description varchar(500)`, compatibles
  con 100 y 500.
- Los `@Size` del DTO (200 y 1000) nunca fueron el límite efectivo: el dominio ya rechazaba
  101–200 y 501–1000 con 400. Cambiarlos a 100 y 500 solo adelanta el rechazo y alinea el mensaje;
  **no redujo ningún límite real**, y no hay nada que ampliar ni migración que preparar. Si prefieres
  conservar 200/1000 en el DTO, el comportamiento observable no cambia (el dominio decide).
  Una prueba (`productNameAndDescription_followTheSddLimits_...`) fija 100/500 como válidos y
  101/501 como 400.

## Contraseña y BCrypt

El BCrypt de Spring usado **no trunca**: lanza `IllegalArgumentException` si la contraseña supera
72 bytes (`BcryptTruncationTest`). Sin validación previa eso saldría a mitad del registro. Ahora se
valida en bytes UTF-8 en la API (`@MaxUtf8Bytes(72)`) y en `AuthApplicationService` (antes de
escribir): 37 "ñ" (74 bytes) se rechazan y 35 "ñ" + 2 letras (exactamente 72 bytes) se aceptan y
autentican con la contraseña completa. El mínimo de 8 caracteres no cambia.

## Concurrencia sobre inventario y pedidos: resultados medidos y corrección aplicada (MySQL 8.0 real)

Detalle del mecanismo, el orden de bloqueos y los límites: **`CONCURRENCIA_BLOQUEOS.md`**. Pagos:
**`PAGOS_IDEMPOTENCIA.md`**.

Pruebas: `src/test/java/nexus/market/concurrency/InventoryConcurrencyMySqlTest.java` (9 pruebas). Se omiten
por defecto; se ejecutan solo con un MySQL **efímero** indicado por `NEXUS_TEST_MYSQL_URL`,
`NEXUS_TEST_MYSQL_USER` y `NEXUS_TEST_MYSQL_PASSWORD` (esquema por Flyway, `ddl-auto=validate`; nunca
apuntar a una base real o compartida). Arranque simultáneo con `CountDownLatch`; coincidencia forzada con
`CyclicBarrier` tras la lectura de inventario o del cobro (spy), que **expira a los 5 s** cuando las
operaciones quedan serializadas.

**Antes de la corrección** (2 ejecuciones idénticas, 5 pruebas originales): las 5 fallaban. La base evitaba
la corrupción solo por accidente (clave primaria de `inventory_movements`): 1 de 20 pedidos aceptados en
vez de 10, errores de clave duplicada presentados como 500, 2 cobros del mismo pago, 15 de 150
reservas/liberaciones aceptadas. Las pruebas nuevas de `markDamaged`/`markActive` revelaron además
**actualizaciones perdidas** (una reserva exitosa sin efecto: `reservado=0` en vez de 1).

**Después de la corrección** (bloqueo pesimista + orden de bloqueo + READ_COMMITTED + estado validado antes
de cobrar): **20 ejecuciones consecutivas de la clase completa, 20/20 verdes (9 pruebas, 0 fallos, 0
errores en cada una)**:

| Escenario | Resultado en las 20 ejecuciones |
|---|---|
| 2 pedidos de 8, stock 10 | 1 aceptado, 1 `InsufficientInventoryException` (negocio), reservado = 8 |
| 20 pedidos de 1, stock 10 (forzado y natural) | **10 aceptados**, 10 `InsufficientInventoryException`, pedidos en BD = 10, reservado = 10 |
| Mismo pago confirmado 2 veces a la vez | **1 cobro, 1 factura**, stock 8, reservado 0; la otra confirmación recibe un error de negocio (estado `PAID`) |
| 5 reservas + 5 liberaciones × 15 rondas | **150 de 150** operaciones aceptadas, contadores consistentes |
| `markDamaged`/`markActive` × reservas (16 rondas) | sin actualizaciones perdidas |
| `markDamaged` ×2 + `markActive` | sin errores de BD |
| Pedidos con productos A-B y B-A cruzados | 6 de 6 aceptados, sin deadlock (sin la ordenación por producto: deadlock en 3 de 6, comprobado por mutación) |
| Pagar y cancelar el mismo pedido a la vez | serializados; ≤ 1 cobro, ≤ 1 factura |

En los 20 logs: **0** ocurrencias de deadlock o clave duplicada. No hay stock negativo, sobreventa,
movimientos duplicados ni reservas inconsistentes en ninguna ejecución. El contador `solapamientos=0` de los
informes es el esperado tras la corrección (las operaciones se serializan y la barrera expira); antes de
ella marcaba 2 de 2 y 20 de 20.

**Límites (no resueltos):** `createInventory` sigue siendo *check-then-act* (requiere un índice único →
esquema); el cobro externo no queda garantizado exactamente una vez ante cualquier fallo
(`PAGOS_IDEMPOTENCIA.md`); las pruebas validan MySQL 8.0/InnoDB con las configuraciones indicadas, no otros
motores.

## Auditoría entre MySQL y MongoDB (limitación vigente)

Sin transacción compartida. La auditoría de las operaciones transaccionales se difiere a *después
del commit* y se descarta si hay rollback; si el proceso cae entre el commit y el registro, se
pierde esa entrada. Una garantía fuerte requeriría un *outbox* (tabla nueva → cambio de esquema).
