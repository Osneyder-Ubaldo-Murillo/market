# Pagos: estado actual, límites y diseño de idempotencia (pasarela real)

**Diseño, no implementado.** La pasarela actual (`LoggingPaymentServicePort`) es una **simulación** que
solo escribe en el log.

## Qué ofrece hoy (y qué no)

- Se calcula un `transactionId` determinista a partir del `orderId` (`TXN-` + UUID derivado). Por eso, en la
  simulación, cobrar dos veces el mismo pedido devuelve el mismo identificador. **Eso es una propiedad de la
  simulación; una pasarela real no la ofrece por sí sola.**
- `OrderService.confirmPayment` valida primero el estado del pedido (`PENDING_PAYMENT`) y solo entonces cobra.
- El pedido está bloqueado en la base (`SELECT … FOR UPDATE`) durante todo el caso de uso: dos confirmaciones
  concurrentes del mismo pedido en la misma base se serializan; la segunda ve `PAID` y falla **antes** de
  cobrar. Medido en MySQL real: 1 cobro, 1 factura, stock descontado una vez (`cobros=1 facturas=1`).

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
