# Gestión logística de pedidos y supervisión

Cómo se compra con cantidades, cómo el operador logístico recoge y entrega, y cómo el supervisor controla el avance. Los endpoints
están en Swagger (`market/docs/API_SWAGGER.md`); aquí está el modelo, las reglas y las decisiones.

## 1. Compra con cantidades (comprador)

| Acción | Endpoint | Regla |
|---|---|---|
| Agregar | `POST /api/buyers/{buyerId}/cart/items` `{productId, quantity}` | entero ≥ 1; si el producto ya está, **suma** |
| Cambiar | `PUT /api/buyers/{buyerId}/cart/items/{productId}` `{quantity}` | entero ≥ 1 (para quitarlo, usar `DELETE`) |
| Quitar | `DELETE /api/buyers/{buyerId}/cart/items/{productId}` | |
| Ver | `GET /api/buyers/{buyerId}/cart` | por línea: producto, `sellerSku`, vendedor, precio unitario, `quantity` y `total` (el subtotal de la línea); más el `total` general |

- `0`, negativos o no enteros se rechazan con 400.
- **Stock:** la cantidad total del producto en el carrito no puede superar lo disponible (existencias menos reservas, todas las
  bodegas): 400 `INSUFFICIENT_INVENTORY`, tanto al agregar (sumando lo que ya había) como al cambiar la cantidad.
- **Se valida de nuevo al crear el pedido:** la reserva del inventario usa bloqueo de fila, por lo que dos compradores que pidan las
  últimas unidades a la vez no pueden vender de más (solo uno crea el pedido; el otro recibe 400 `INSUFFICIENT_INVENTORY`).
- Precios, subtotales y total los calcula siempre el servidor; el cliente solo envía `productId` y `quantity`.
- El pedido congela producto, cantidad y precio del momento de la compra: cambiar después el precio del producto no lo altera.

## 2. Código legible del pedido

Cada pedido tiene `orderCode`, p. ej. `NM-PED-2026-7K3F9Q` (año + 6 caracteres sin ambiguos: sin 0, 1, O, I, L).

- **Único** (`UNIQUE` en `orders.order_code`) y **estable**: no cambia en todo el ciclo del pedido.
- **No reemplaza** al UUID (`order_id`), que sigue siendo la clave primaria y la referencia de envíos, facturas y devoluciones.
- **No es una credencial:** todas las operaciones que lo aceptan exigen autenticación y permiso; para un operador, además, que el
  pedido esté asignado a él.
- Se acepta (en mayúsculas o minúsculas) donde la ruta dice `{reference}`: `/api/logistics/orders/{reference}` y
  `/api/supervision/orders/{reference}`. Las rutas anteriores (`/api/orders/{orderId}`…) siguen usando el UUID.
- Los pedidos anteriores a la migración `V5` reciben un código secuencial por orden de creación (`NM-PED-<año>-000001`…).

## 3. Operador logístico

Un **administrador asigna** el operador: `PUT /api/logistics/orders/{reference}/operator` `{"operator": "<correo|documento|UUID>"}`.
La cuenta debe ser un operador logístico **activo**; el pedido debe estar `PAID` o `DISPATCHED`. Reasignar es posible y conserva el
historial. No hay asignación automática.

El operador **solo ve y gestiona los pedidos que tiene asignados**. Un pedido ajeno y uno inexistente responden igual (403) para no
revelar cuáles existen. Esto vale también para las rutas logísticas anteriores (`/api/orders/{id}`, `/shipping`, `/dispatch`,
`/deliver`, `/api/shippings/*`).

| Acción | Endpoint |
|---|---|
| Listado "Gestión de pedidos" | `GET /api/logistics/orders?q=&status=&from=&to=&page=&size=` |
| Detalle | `GET /api/logistics/orders/{reference}` |
| Registrar recogida | `POST /api/logistics/orders/{reference}/pickups` `{productId, quantity}` |
| Reportar novedad | `POST /api/logistics/orders/{reference}/incidents` `{productId?, type, description}` |
| Crear envío / despachar / tránsito / entregar | rutas existentes: `POST /api/orders/{id}/shippings`, `/api/shippings/{id}/dispatch`, `/in-transit`, `/deliver` |

**Listado:** código, fechas de creación y actualización, nombre del comprador (sin correo ni dirección), cantidad de líneas y de
unidades, vendedores, resumen de productos, estado, etapa logística, recogidas completadas/pendientes (por línea y por unidades),
novedades abiertas, operador y `availableActions` (qué puede intentar ahora; el servidor valida cada una).

**Detalle:** además, por línea: producto, `sellerSku`, vendedor, cantidad pedida, recogida y pendiente, estado (`PENDING`, `PARTIAL`,
`PICKED`); las novedades, el historial cronológico y la dirección de entrega (solo la ve el operador asignado).

**Recogidas.** Cada registro **suma** unidades a una línea física del pedido y nunca supera lo pedido (409 `PICKUP_EXCEEDS_REQUESTED`).
Solo con el pedido `PAID`. Los productos digitales no se recogen. La cantidad pedida sale de la línea congelada del pedido, así que no
puede desincronizarse. Un pedido solo cuenta como recogido cuando todas sus líneas físicas están completas.

**Etapas (`logisticsStage`)** — no son estados nuevos; se derivan de los existentes (pedido y envío):

`PENDING_PICKUP` → `PARTIALLY_PICKED` → `PICKED` → `PREPARING` (envío creado) → `DISPATCHED` → `IN_TRANSIT` → `DELIVERED`

**Reglas que hacen cumplir el flujo:**

| Intento | Resultado |
|---|---|
| Crear el envío o despachar con unidades por recoger | 409 `PICKUP_INCOMPLETE` |
| Crear el envío, despachar o entregar con novedades sin resolver | 409 `INCIDENT_OPEN` |
| Saltarse un estado (p. ej. tránsito sin despacho, entrega sin tránsito) | 409 `INVALID_STATE_TRANSITION` |
| Operar un pedido de otro operador | 403 |

**Novedades.** Tipos: `PICKUP_DELAY`, `MISSING_PRODUCT`, `INCOMPLETE_QUANTITY`, `DAMAGED_PRODUCT`, `SELLER_WAREHOUSE_ISSUE`,
`TRANSPORT_ISSUE`, `DELIVERY_DIFFICULTY`, `OTHER`. Guardan pedido, producto (opcional), quién la reportó, descripción, fecha y hora,
estado (`OPEN`, `IN_PROGRESS`, `RESOLVED`), responsable y observaciones de solución. **No modifican cantidades, pagos ni inventario.**

**Entrega.** `POST /api/shippings/{id}/deliver` (o `/api/orders/{id}/deliver`) acepta un cuerpo opcional `{"notes": "..."}`. Queda en
el historial con el operador, el rol, la fecha y la hora, y `deliveredAt` del pedido. **Limitación:** no hay evidencia digital (foto,
firma, geolocalización); no existe soporte para ello en el proyecto y no se simuló.

## 4. Supervisor

Solo lectura del trabajo logístico, más la **atención de novedades**. No asigna operadores, no despacha ni modifica inventario, pagos
ni información comercial. Los endpoints sirven a SUPERVISOR y ADMIN.

| Acción | Endpoint |
|---|---|
| Panel "Supervisión de pedidos" | `GET /api/supervision/orders?q=&operator=&status=&unassigned=&withIncidents=&pickupsPending=&from=&to=` |
| Detalle con historial | `GET /api/supervision/orders/{reference}` |
| Listar / consultar novedades | `GET /api/supervision/incidents?status=`, `GET /api/supervision/incidents/{incidentId}` |
| Instrucción | `POST /api/supervision/incidents/{incidentId}/instructions` `{text}` (pasa a `IN_PROGRESS`) |
| Responsable | `POST /api/supervision/incidents/{incidentId}/responsible` `{responsible}` |
| Resolver | `POST /api/supervision/incidents/{incidentId}/resolve` `{notes}` |

Con los filtros se identifican los casos del seguimiento: sin operador (`unassigned=true`), con recogidas pendientes
(`pickupsPending=true`), detenidos por novedades (`withIncidents=true`), despachados y entregados (`status=`).

**Trazabilidad.** Resolver no borra nada: la novedad conserva `reportedBy`/`reportedAt`, el `responsible` y `resolvedBy`/`resolvedAt`
con las observaciones. Una novedad resuelta no se reabre (409) ni admite más instrucciones. Cada hecho (asignación, recogida, novedad,
instrucción, responsable, resolución, envío, despacho, tránsito, entrega) se agrega al historial del pedido (`order_events`) con el
usuario, su rol y la hora. Las filas del historial solo se insertan; el detalle no incluye contraseñas ni tokens.

## 5. Permisos

| Rol | Puede | No puede |
|---|---|---|
| BUYER | su carrito y sus pedidos (incluido su `orderCode`) | cualquier ruta de logística o supervisión |
| SELLER | ver sus líneas del pedido (con `orderCode`, sin datos del comprador) | logística y supervisión |
| LOGISTICS_OPERATOR | listar, ver, recoger, reportar novedades, crear envío, despachar, tránsito y entregar **solo sus pedidos asignados** | ver o tocar pedidos de otro operador; supervisar; asignar |
| SUPERVISOR | panel, detalle, historial y atención de novedades | recoger, despachar, entregar, asignar operador |
| ADMIN | asigna operadores, supervisa y atiende novedades, y conserva sus facultades anteriores | hacer el trabajo del operador (recoger, despachar, entregar) |

Las restricciones se aplican en el backend (`SecurityConfiguration` por rol y `AccessPolicy` por propiedad), no solo en la interfaz.

## 6. Base de datos (migración `V5__order_logistics.sql`)

Solo cambios **aditivos**; no se borra ni modifica ningún dato.

- `orders`: `order_code` (único), `assigned_operator_id`, `assigned_at`. Los pedidos existentes reciben su código dentro de la misma
  migración.
- Tablas nuevas: `order_pickups` (unidades recogidas por línea), `order_incidents` y `order_events`.

## 7. Cambios de comportamiento respecto a la versión anterior

- Los endpoints logísticos existentes ahora exigen **operador asignado** y **recogida completa** antes de crear el envío o despachar
  (antes cualquier operador podía despachar cualquier pedido sin recoger nada). Un administrador debe asignar el operador primero.
- El operador ya no puede consultar (`GET /api/orders/{id}`) pedidos que no tiene asignados.
- El carrito rechaza cantidades superiores al stock disponible (antes solo fallaba al crear el pedido).
- Respuestas de pedido: nuevo campo `orderCode`.

## 8. Limitaciones

- Sin evidencia digital de entrega (foto, firma, ubicación).
- Sin aviso a vendedores ni asignación automática de operadores; la coordinación con vendedores queda en las instrucciones del
  supervisor.
- El historial ordena por fecha y hora; dos hechos en el mismo microsegundo podrían intercambiarse.
