# Pagos: estado actual, límites y diseño de idempotencia (pasarela real)

**Diseño, no implementado.** La pasarela actual (`LoggingPaymentServicePort`) es una **simulación** que
solo escribe en el log.

## Estado para producción real (resumen honesto)

**Ni los pagos ni los envíos están listos para operar con un proveedor real.** Lo que existe y lo que falta:

| Capacidad | Estado | Detalle |
|---|---|---|
| Un cobro por pedido dentro de la aplicación | **Hecho y probado** | Estado `PENDING_PAYMENT` validado y fila del pedido bloqueada (`SELECT ... FOR UPDATE`) antes de cobrar; la segunda confirmación ve `PAID` y falla antes de cobrar. Prueba MySQL real: 1 cobro, 1 factura. |
| Id de transacción persistido | **Hecho** | `orders.payment_transaction_id` (migración `V3`); lo usan reembolsos y cancelación. |
| Reembolso sin duplicados desde la aplicación | **Hecho y probado** | `PENDING` bloqueado antes de llamar; un fallo deja `FAILED` y se puede reprocesar; `PROCESSED` no. |
| Anulación del cobro si falla la finalización | **Hecho (mejor esfuerzo)** | `confirmPayment` intenta reembolsar y audita `CONCILIACIÓN` si también falla. No es una garantía. |
| Simuladores bloqueados en producción | **Hecho y probado** | Pago, envío y notificaciones por log: con `prod` no arranca sin autorización expresa (`PaymentProductionGuardTest`, `ShippingProductionGuardTest`, `NotificationProductionGuardTest`). |
| Clave de idempotencia por intento de pago | **Pendiente** | Depende de la pasarela elegida (si la soporta y con qué ventana). |
| Tabla de intentos de pago (*payment attempt*) | **Pendiente, requiere esquema** | Tabla nueva en `db/schema`; solo con aprobación (ver diseño más abajo). |
| Reconciliación de intentos `PENDING` / cobros sin pedido pagado | **Pendiente** | Necesita la tabla de intentos y una consulta al proveedor. |
| Webhooks del proveedor (duplicados, firma) | **Pendiente: no existe ningún endpoint** | Ver sección siguiente. |
| Integración real de pasarela y de transportadora | **Pendiente: requisito externo** | Elegir proveedor, obtener credenciales y probar en su *sandbox*. |

No hay ningún proveedor inventado ni llamadas externas en el código. **No declare pagos ni envíos como producción operativa
hasta probarlos con un proveedor real en su entorno de pruebas (*sandbox*).**

### Webhooks y callbacks (no implementados a propósito)

La aplicación **no expone ningún endpoint de callback** y no se ha creado uno "genérico": su forma (cabecera de firma, algoritmo,
ventana de tolerancia, identificador de evento, reintentos) la define el proveedor, y un endpoint sin verificación sería una
puerta abierta para marcar pedidos como pagados. Cuando se elija proveedor, el endpoint debe cumplir como mínimo:

1. **Autenticidad antes de nada:** verificar la firma del proveedor sobre el cuerpo **sin reinterpretar** (bytes crudos) con una
   comparación en tiempo constante y rechazar con 401/403 sin procesar, y con una tolerancia de fecha corta contra *replays*.
2. **Idempotencia por id de evento:** registrar el id del evento en la misma transacción que sus efectos (restricción de
   unicidad); un evento repetido responde 200 sin repetir efectos.
3. **No fiarse del contenido:** tratar el evento como una notificación y consultar el estado real al proveedor antes de pagar el
   pedido; comprobar que importe, moneda y pedido coinciden con los esperados.
4. **Estados:** solo transiciones válidas (`PENDING_PAYMENT` → `PAID`); un evento tardío sobre un pedido cancelado dispara un
   reembolso o una revisión, nunca un pago silencioso.
5. **Registro seguro:** auditar id de evento y resultado, sin cuerpo completo, sin cabeceras de firma y sin datos de tarjeta.
6. **Ruta pública mínima:** una única ruta en `SecurityConfiguration` (hoy `denyAll` por defecto), con su propio límite de tasa.

### Envíos

`SimulatedShippingProviderPort` genera un seguimiento ficticio (`TRK-...`) y nunca se comunica con una transportadora. Para un
proveedor real hace falta el mismo tipo de trabajo: elegir transportadora, credenciales, guía/etiqueta, cancelación y, si ofrece
estados por callback, aplicar los mismos criterios de la sección anterior. Los estados del pedido y del envío ya se mantienen
coherentes dentro de la aplicación (despachar el envío despacha el pedido).

## Qué ofrece hoy (y qué no)

- Se calcula un `transactionId` determinista a partir del `orderId` (`TXN-` + UUID derivado). Por eso, en la
  simulación, cobrar dos veces el mismo pedido devuelve el mismo identificador. **Eso es una propiedad de la
  simulación; una pasarela real no la ofrece por sí sola.**
- `OrderService.confirmPayment` valida primero el estado del pedido (`PENDING_PAYMENT`) y solo entonces cobra.
- El pedido está bloqueado en la base (`SELECT … FOR UPDATE`) durante todo el caso de uso: dos confirmaciones
  concurrentes del mismo pedido en la misma base se serializan; la segunda ve `PAID` y falla **antes** de
  cobrar. Medido en MySQL real: 1 cobro, 1 factura, stock descontado una vez (`cobros=1 facturas=1`).

## Limitaciones conocidas (decididas y pendientes)

- **El identificador de transacción del cobro se guarda** en `orders.payment_transaction_id` (migración `V3`).
  `OrderService.confirmPayment` lo registra y lo usan `processRefund` y la cancelación de un pedido pagado. Los pedidos
  anteriores a `V3` (columna nula) usan la referencia derivada `"CHARGE-" + orderId` (`Order.chargeReference()`).
  **Esa referencia no es un id emitido por la pasarela** (el simulador emite `TXN-...`): solo es aceptable con el
  simulador, que no la valida. Cada uso deja una auditoría `WARNING`, y `PaymentServicePort.refund` documenta que una
  pasarela real debe localizar el cobro por el pedido cuando recibe el prefijo `CHARGE-`. No hay relleno de datos en `V3`.
- **Cancelar un pedido pagado** reembolsa el total por la pasarela y repone el stock. Si la pasarela falla, la excepción
  revierte todo: el pedido sigue `PAID` y no se repone nada; el intento fallido queda en la auditoría (`ERROR`).
- **Pago no completado tras el cobro:** si falla algo después de cobrar (guardar el pedido, inventario, factura), la base
  se revierte y `OrderService.confirmPayment` intenta anular el cobro con un reembolso. Si esa anulación también falla,
  se audita un aviso `CONCILIACIÓN` con la transacción para revisarla a mano. Sigue sin ser una garantía (ver abajo).
- **Pago simulado bloqueado en producción.** Con el perfil `prod` la aplicación no arranca si
  `nexusmarket.payment.simulated.allowed` no es `true` (`LoggingPaymentServicePort`).
- **Reembolsos:** el estado `PENDING` se comprueba y se bloquea la fila **antes** de llamar a la pasarela, y un fallo de
  la pasarela deja el reembolso en `FAILED` (no se reintenta solo, pero un `FAILED` **puede reprocesarse a mano**; un
  reembolso `PROCESSED` no). Eso evita un doble reembolso desde esta aplicación,
  pero no resuelve el caso de la sección siguiente (resultado desconocido tras un timeout).

## Lo que NO se puede afirmar

El bloqueo de base de datos **no garantiza exactamente un cobro externo ante cualquier fallo**:

1. *Cobro correcto y rollback posterior*: el cargo se hizo, pero un fallo después (factura, inventario,
   `COMMIT`, caída del proceso) revierte la base. El cliente queda cobrado con el pedido en
   `PENDING_PAYMENT`. Un reintento volvería a cobrar si la pasarela no es idempotente.
2. *Fallo de red tras cobrar*: la aplicación no sabe si el cobro ocurrió (timeout) y no hay estado que lo
   registre.
3. *Reembolsos* (`refund`): mismo problema.
4. El bloqueo no protege entre sistemas distintos (otra aplicación, reintentos del cliente, webhooks).

## Diseño propuesto para la integración real

1. **Clave de idempotencia por intento de pago**: derivada del pedido (p. ej. `order:<orderId>:payment`),
   enviada a la pasarela en la cabecera/parámetro de idempotencia que ofrezca (verificar que el proveedor
   la soporta y su ventana de validez). Reintentar con la misma clave devuelve el resultado original.
2. **Registrar la intención antes de cobrar** (patrón *payment attempt* / *outbox*): una fila de intento
   `PENDING` con la clave, confirmada en su propia transacción antes de llamar a la pasarela; tras la
   respuesta, actualizarla a `SUCCEEDED`/`FAILED` con el id de transacción del proveedor.
   **Requiere una tabla nueva → cambio de esquema → solo con aprobación.**
3. **Reconciliación**: proceso periódico y webhook del proveedor que resuelvan intentos `PENDING` o cobros
   sin pedido pagado (caso 1 y 2 anteriores), con consulta al proveedor por la clave.
4. **Orden de operaciones**: validar estado y bloquear pedido → registrar intención (transacción propia) →
   cobrar con la clave → completar pedido/inventario/factura. Si la finalización falla, la reconciliación
   completa el pedido a partir del intento `SUCCEEDED` en lugar de volver a cobrar.
5. **Tiempos**: llamada con *timeout* y sin retener el bloqueo de fila más tiempo del necesario (valorar
   cobrar fuera de la transacción de bloqueo una vez registrada la intención).
6. **Pruebas** (contra un doble de la pasarela con idempotencia y fallos inyectables): cobro exitoso y
   fallo de la base → no se cobra dos veces al reintentar; timeout con resultado desconocido → reconciliación;
   doble clic / dos solicitudes simultáneas → un solo cobro; reembolso idempotente.

## Estado de la simulación respecto a la corrección de concurrencia

Mientras sea una simulación, "un solo cobro" se refiere al número de llamadas a `PaymentServicePort.charge`
(medido con un *spy* en las pruebas), no a un cargo real.
