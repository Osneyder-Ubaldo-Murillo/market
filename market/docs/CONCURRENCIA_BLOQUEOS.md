# Concurrencia: bloqueos pesimistas, orden de adquisición y límites

Sin cambios de esquema, sin `@Version`, sin migraciones.

## Qué se corrigió y por qué

Antes de esta corrección (medido en MySQL 8.0 real, `InventoryConcurrencyMySqlTest`):

- no había serialización del acceso a inventarios ni pedidos;
- la base de datos evitaba la corrupción solo por accidente (clave primaria
  `inventory_movements (inventory_id, position)` duplicada), de modo que pedidos y reservas **válidos**
  fallaban con un error de BD presentado como 500 (1 de 20 pedidos aceptados en vez de 10);
- el mismo pago confirmado dos veces llamaba **dos veces a la pasarela**;
- `markDamaged` / `markActive` (que no añaden movimiento y por tanto no chocaban con esa clave)
  **perdían actualizaciones**: una reserva exitosa quedaba sin efecto (`reservado=0` en vez de 1) porque el
  cambio de estado reescribía la fila completa con datos leídos antes.

## Mecanismo

1. **Bloqueo de fila `SELECT … FOR UPDATE`** (`LockModeType.PESSIMISTIC_WRITE`) al cargar un `Order` o un
   `Inventory` **dentro de una transacción de Spring** (`JpaRepositorySupport.findForUpdate`). Fuera de una
   transacción no hay bloqueo (no habría dónde sostenerlo) y se usa la lectura simple. Todo caso de uso
   transaccional que carga estos agregados lo hace para modificarlos.
2. **Inventario por producto (`findByProductId`)**: `product_id` **no tiene índice**, así que un
   `FOR UPDATE` por ese predicado bloquearía toda la tabla bajo REPEATABLE READ. Se hace en dos pasos:
   (a) lectura sin bloqueo solo de los `inventory_id`, ordenados; (b) bloqueo por clave primaria, uno a uno,
   en ese orden ascendente.
3. **Aislamiento `READ_COMMITTED`** en los casos de uso que bloquean (`@Transactional(isolation =
   READ_COMMITTED)`). Con REPEATABLE READ, la lectura de los movimientos (tabla hija, lectura sin bloqueo)
   usaría la instantánea tomada al inicio de la transacción y no vería lo confirmado por la transacción que
   acabamos de esperar: volvería a calcular la misma `position` y chocaría con la clave primaria.
4. **Estado de pedido validado antes de cobrar**: `Order.requirePendingPayment()` se invoca antes de
   `PaymentServicePort.charge`. Antes, un segundo pago de un pedido ya pagado cobraba y fallaba después.
5. **Errores**: un conflicto agotado (tiempo de espera de bloqueo o deadlock) se responde `409
   CONCURRENT_MODIFICATION` (reintentable). `innodb_lock_wait_timeout` de la sesión = 10 s en producción
   (`spring.datasource.hikari.connection-init-sql`), para que las esperas no retengan conexiones. El rechazo
   por falta de stock es un error de negocio (`InsufficientInventoryException`), no un error de BD.

## Orden global de adquisición de bloqueos

Todos los casos de uso respetan este orden; ninguno lo viola, por lo que no puede haber ciclos entre ellos:

1. **Pedido** (`orders`, por `order_id`). Es el primer bloqueo en `confirmPayment`, `cancel`, `dispatch`,
   `deliver`, `Shipping.createShipping/dispatchShipping/markInTransit/deliver`, `generateInvoice`,
   `approveReturn`/`processReturn` (cada uno carga el pedido antes de tocar inventario).
2. **Inventarios**, ordenados por `(product_id, inventory_id)` ascendente: los productos de una operación se
   procesan por `productId` ascendente (`OrderService.inLockOrder`) y, dentro de un producto, los
   inventarios por `inventory_id` ascendente.
3. **Devoluciones y reembolsos** (`returns`, `refunds`): `ReturnRepositoryPort.findById` y
   `RefundRepositoryPort.findById` también bloquean la fila (`findForUpdate`) dentro de una transacción.
   Las operaciones sobre una devolución (`approveReturn`, `rejectReturn`, `processReturn`) toman el bloqueo de la
   **devolución primero y del pedido después**; ningún caso de uso toma un pedido y después una devolución existente
   (`requestReturn` bloquea el pedido e **inserta** una devolución nueva, que nadie más puede tener todavía), así que no
   hay ciclo. `processRefund` bloquea solo la fila del reembolso y comprueba que siga `PENDING` **antes** de llamar a
   la pasarela. **Pendiente de verificar en MySQL real**: este orden y la serialización se probaron en H2
   (`RefundConsistencyIntegrationTest`); las pruebas `*MySqlTest` actuales no cubren devoluciones.
4. Las operaciones directas sobre un inventario por id (`reserve`, `release`, `addStock`, `adjust`,
   `markDamaged`, `markActive`, `createInventory`) bloquean **una sola fila**: no pueden formar ciclo.

Un pedido nuevo (`createOrder`) inserta su fila (nadie más puede tenerla) y bloquea después los inventarios
en el orden indicado. Prueba: `ordersWithTheSameProductsInCrossedOrder_doNotDeadlock` (6 pedidos con los
productos A-B y B-A); **comprobación por mutación**: sin la ordenación por producto, esa prueba produce
`MySQLTransactionRollbackException` (deadlock) en 3 de 6 pedidos.

## Operaciones revisadas

| Operación | Bloqueos (en orden) | Transacción |
|---|---|---|
| `createOrder` | inserta pedido → inventarios por producto asc. | RC |
| `confirmPayment` | pedido → (valida estado) → cobro → inventarios por producto asc. → factura | RC |
| `cancel` | pedido → factura → inventarios por producto asc. | RC |
| `dispatch` / `deliver` (pedido) | pedido | RC |
| `Shipping.*` | pedido (y envío) | RC |
| `generateInvoice` | pedido | RC |
| `approveReturn` / `processReturn` | devolución → pedido → inventarios por producto asc. | RC |
| `rejectReturn` | devolución | RC |
| `processRefund` | reembolso (estado PENDING o FAILED (reintento) comprobado antes de la pasarela; FAILED se conserva si la pasarela falla) | RC, `noRollbackFor` `BusinessException` |
| `Inventory.*` (reserve, release, addStock, adjust, markDamaged, markActive) | 1 inventario | RC |
| `registerBuyer` / `registerSeller` | sin bloqueos de fila | por defecto |
| Operaciones de una sola escritura sobre catálogo, carrito, bodegas | — | sin transacción |

## Límites y riesgos que quedan (no resueltos)

- **`createInventory`**: la comprobación "ya existe inventario para (producto, bodega)" es *check-then-act*
  sobre una fila que aún no existe; no hay qué bloquear y no hay índice único: dos solicitudes simultáneas
  pueden crear dos filas. Solucionarlo exige una restricción de esquema → no se toca sin aprobación.
- **Cobro externo dentro de la transacción** (ver `PAGOS_IDEMPOTENCIA.md`): el bloqueo de BD garantiza que
  *dentro de esta aplicación y de esta base de datos* un pedido no se cobra dos veces concurrentemente, pero
  **no** garantiza exactamente un cobro externo ante cualquier fallo (p. ej. cobro correcto y fallo posterior
  que revierte la base).
- **Esperas**: el segundo hilo espera al primero; con una pasarela lenta el bloqueo del pedido se mantiene
  mientras dura el cobro. Acotado por `innodb_lock_wait_timeout` (10 s) → 409.
- **Instancias múltiples**: los bloqueos son de la base de datos, válidos entre instancias de la aplicación.
- **Lecturas sin bloqueo** (consultas de pedidos/inventario fuera de transacción, `AccessPolicy`) pueden ver
  estados ligeramente atrasados; no modifican datos.
- El mapeo de `InsufficientInventoryException` sigue siendo 400 (regla existente); se puede pasar a 409 si
  se prefiere, es una decisión de API.
- Auditoría diferida al commit: ver `TRANSACCIONES.md`.
