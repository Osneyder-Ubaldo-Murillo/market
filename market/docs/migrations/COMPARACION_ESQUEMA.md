# Comparación de esquema: entidades JPA vs. migraciones Flyway

Estado: **borrador para aprobación**. Nada de lo descrito aquí está en el classpath de
producción. `V1__schema.sql` y `V2__seed.sql` no se han modificado, movido ni ejecutado
(`V2` nunca se ejecutó, ni siquiera en bases efímeras).

## 1. Historial de migraciones: DESCONOCIDO

No hay evidencia de que alguna base haya aplicado alguna versión de `V1` o `V2`, ni de que
no lo haya hecho. Que `db/` esté sin seguimiento en Git, o que `V1` tenga un error de
sintaxis, **no** prueba que nunca se aplicaron versiones anteriores. Toda la estrategia
siguiente debe ser segura en los dos casos.

## 2. Hallazgos

| # | Hallazgo | Evidencia |
|---|----------|-----------|
| 1 | `V1` no es SQL válido en MySQL 8.0: la sentencia `buyer_addresses` está truncada (sin PK ni `);`). | Error 1064, línea 52, en una base efímera vacía (reproducido de nuevo en esta fase). |
| 2 | `V1` define un modelo distinto al de las entidades (nombres de tabla y de columna). | Comparación de la sección 3. |
| 3 | `V2` inserta en `users` las columnas `password_hash`, `role_code`, `status_code`, que la entidad `users` no tiene. | Lectura de `V2`; no ejecutado. |
| 4 | `V2` siembra usuarios (ADMIN, SELLER, BUYER) con un hash BCrypt conocido (`password123`) en la ruta de migraciones de producción. | Lectura de `V2`. |
| 5 | Un `V1` fallido en MySQL deja la base **a medias**: el DDL no es transaccional. | Tras el fallo quedaron `buyers`, `sellers`, `users` y `flyway_schema_history` en la base efímera. |
| 6 | Con `baseline-on-migrate=true` y `baseline-version=1`, una base con tablas y sin historial se marca como "versión 1 aplicada" **sin verificar** que sus tablas coincidan con `V1`. | Verificado en base efímera (sección 5, escenario B). |

## 3. Tablas: entidades JPA frente a `V1`

Las entidades (20 tablas) provienen del esquema que Hibernate 7.4 genera en MySQL 8.0.

| Entidad (tabla) | En `V1` | Diferencia |
|---|---|---|
| `users` | Sí | `V1`: `password_hash`, `role_code`, `status_code`. Entidad: `role`, `status`, sin hash. |
| `user_credentials` | **No** | El hash vive aquí en el modelo real. |
| `buyers` | Sí | `V1`: `id`, `commercial_status_code`. Entidad: `buyer_id`, `commercial_status` y dirección principal embebida. |
| `buyer_additional_addresses` | **No** | `V1` tiene `buyer_addresses` (truncada), con otro diseño. |
| `sellers` | Sí | `V1`: `id`, `status_code`. Entidad: `seller_id`, `status`. |
| `seller_warehouses` | **No** | Colección de bodegas del vendedor. |
| `warehouses` | Sí | `V1`: `id`, `type_code`, `status_code`; direcciones en `warehouse_addresses`. Entidad: `warehouse_id`, `type`, `status`, dirección embebida. |
| `products` | Sí | `V1`: `type_code`, `status_code`, `amount`, `currency`, specs en `product_specifications`. Entidad: `product_type`, `status`, `price_*`, `specifications_json`. |
| `inventories` | **No** | `V1` tiene `inventory`, con otro nombre y columnas. |
| `inventory_movements` | **No** | Sin equivalente en `V1`. |
| `carts` | Sí | `V1`: `id`. Entidad: `cart_id`, `total_*`; un carrito por comprador (UNIQUE). |
| `cart_items` | Sí | Claves distintas: la entidad usa `(cart_id, position)`. |
| `orders` | Sí | Claves y columnas distintas; dirección de envío embebida; `invoice_id`, `delivered_at`. |
| `order_items` | Sí | `V1`: `id`, `*_snapshot`. Entidad: `(order_id, position)`, `unit_*`, `total_*`. |
| `invoices` | Sí | `V1`: `invoice_number`. Entidad: `invoice_id`, `buyer_id`, `seller_id`, `issue_date`; UNIQUE por pedido. |
| `invoice_items` | **No** | Sin equivalente en `V1`. |
| `returns` | Sí | `V1`: `id`, `status_code`. Entidad: `return_id`, `request_date`, `resolution_date`; UNIQUE por pedido. |
| `return_items` | **No** | `V1` tiene `return_lines`. |
| `refunds` | Sí | `V1`: `id`, `transaction_id`. Entidad: `refund_id`, `request_date`, `processed_date`. |
| `shippings` | **No** | `V1` tiene `shipping`. |

Tablas de `V1` sin entidad: `buyer_addresses`, `cart_items` (otro diseño), `inventory`,
`order_items` (otro diseño), `payments`, `product_specifications`, `return_lines`,
`shipping`, `warehouse_addresses`.

Conclusión: no es un desajuste de nombres sueltos. `V1` y el modelo JPA son **dos diseños
distintos**; con `ddl-auto=validate`, ninguna base creada por `V1` podría arrancar la
aplicación.

## 4. Artefacto de referencia

`docs/migrations/reference/V1__schema.reference.sql`

- Generado del esquema real de Hibernate (20 tablas), reordenado para que las FKs
  referencien tablas ya creadas. Sin datos ni credenciales.
- **Fuera del classpath**: Flyway no lo ve. No es una migración.
- Verificado: aplicado a una base MySQL 8.0 efímera, la aplicación con el perfil de
  producción y `ddl-auto=validate` arrancó (health `UP`, `/api/products` 200).
- Es fiel a las entidades: no añade índices ni FKs que el modelo no declare. Los
  endurecimientos (FKs entre agregados, índices por búsqueda) son una decisión aparte.

## 5. Comportamiento de Flyway por escenario

Configuración actual de producción: `baseline-on-migrate=true`, `baseline-version=1`,
migraciones `V1` y `V2` en `classpath:db/migration`.

Leyenda: **[V]** verificado en base efímera en esta fase; **[D]** derivado de la
semántica documentada de Flyway, no ejecutado (ejecutarlo implicaría correr `V2`).

| Escenario | Con la configuración actual | Con baseline retirado (propuesta) |
|---|---|---|
| **A. Base vacía** | **Falla** en `V1` (1064). Queda a medias: tablas parciales e historial con una fila fallida. **[V]** | Igual: falla en `V1` mientras no se corrija el esquema. Sin baseline no cambia nada aquí, porque en una base vacía el baseline no se aplica. **[V]** |
| **B. Tablas, sin historial Flyway** | Flyway crea el historial con un registro `BASELINE` en versión 1, **da `V1` por aplicada sin verificarla** y continúa. La aplicación arranca si las tablas coinciden con las entidades. **[V]** Después aplicaría `V2` (pendiente), que fallaría por columnas inexistentes o, en otra base, sembraría usuarios conocidos. **[D]** | Flyway se **niega** a migrar ("found non-empty schema without schema history table"): no toca nada y la aplicación no arranca. Estado seguro pero bloqueante: exige una decisión explícita (baseline manual y controlado). **[D]** |
| **C. Historial Flyway existente** | Valida checksums y aplica solo lo pendiente. Si `V1` se edita, falla por *checksum mismatch* y la aplicación no arranca. Si el historial ya incluye `V2`, los usuarios semilla ya existen. **[D]** | Igual (el baseline solo actúa cuando no hay historial). **[D]** |

Lectura de riesgos:

- Retirar el baseline **no deja inseguro** ningún escenario: B pasa de "adoptar en silencio"
  a "bloquear con un error claro"; A y C no cambian. Lo que sí hace es dejar de
  enmascarar el problema en B.
- La contrapartida: una base inicializada por Hibernate (como las de desarrollo con
  `ddl-auto=update`) ya no podrá "adoptarse" automáticamente; necesitará un baseline manual.
- B es el escenario donde el historial desconocido importa: no se puede saber qué contiene
  una base con tablas hasta inspeccionarla.

## 6. Alternativas seguras para el esquema (sin ejecutar nada todavía)

Estrategia común: **no editar `V1`/`V2` ya existentes** mientras el historial sea
desconocido, porque editarlas rompe el checksum en cualquier base que las haya aplicado
(escenario C).

| Opción | Descripción | Base vacía | Base con tablas sin historial | Base con historial |
|---|---|---|---|---|
| **1. Nueva ubicación de migraciones** | Carpeta nueva (p. ej. `db/migration-v2/`) con una línea base correcta derivada del artefacto de referencia; `locations` apunta solo a ella. `V1`/`V2` actuales salen del classpath, sin borrarse. | Funciona: crea el esquema válido. | Requiere baseline manual explícito tras verificar el esquema. | Historial con checksums de `V1`/`V2` antiguos: debe tratarse a mano (`repair`/nuevo historial). No es automático. |
| **2. Corrección V3 sobre V1 actual** | Dejar `V1`/`V2` y añadir `V3` que repare el esquema. | **No funciona**: `V1` falla antes de que `V3` pueda ejecutarse. | Igual que hoy. | Solo sirve si `V1` llegó a aplicarse completa en esa base, algo que no es demostrable. |
| **3. Reescribir `V1` in situ** | Sustituir el contenido de `V1`. | Funciona. | Baseline manual. | **Rompe** con *checksum mismatch* en cualquier base que ya tenga `V1`. |

Recomendación (sujeta a tu aprobación): **opción 1**, con baseline retirado, `V2` fuera de
la ruta de producción y los datos semilla de desarrollo (si se conservan) en una ubicación
solo de dev/test, idempotente y sin credenciales conocidas.

## 7. Qué NO está demostrado

- Que el arranque de producción funcione con Flyway activo y un esquema válido: hoy solo
  se demostró con el esquema de referencia aplicado a mano y Flyway desactivado, y con
  Flyway en baseline sobre ese esquema (`target=1`, sin ejecutar `V2`).
- El escenario C no se ejecutó (requiere una base con historial real).
- Si alguna base fuera de este repositorio aplicó una versión previa de `V1`/`V2`.

## 8. Implementación (opción 1) y verificación

- Ubicación activa de producción: `classpath:db/schema` → `V1__initial_schema.sql`
  (derivada del artefacto de referencia; sin `INSERT`, usuarios ni credenciales).
- `db/migration/V1__schema.sql` y `V2__seed.sql` siguen intactos, en su sitio y fuera de la
  ubicación activa.
- `baseline-on-migrate=false` y sin `baseline-version`.
- `docker-compose.yml` ya no monta `db/migration` en `docker-entrypoint-initdb.d`
  (ejecutaba `V1` y `V2` al crear el volumen de MySQL).

Verificado en MySQL 8.0 efímero, con `jpa,prod`, Flyway activo y `ddl-auto=validate`:

| Escenario | Resultado |
|---|---|
| A. Base vacía | Aplica `V1 - initial schema`, arranca, health `UP`; 20 tablas + historial; 0 usuarios. Segundo arranque: "up to date". |
| B. Tablas sin historial | Flyway se niega: "Found non-empty schema(s) ... but no schema history table". No modifica la base (sin historial creado). La aplicación no arranca. |
| C. Historial antiguo (`1 - schema`) | `Migration checksum mismatch for migration version 1`. No modifica la base. La aplicación no arranca. |
| C'. Historial propio ya aplicado | Cubierto por el segundo arranque de A. |
