# Diagnóstico del Proyecto NexusMarket

Estado verificado contra el código y las pruebas (octubre de 2026). Para el detalle de cada tema véase `market/docs/`.

## Estado General

| Entrega | Estado | Descripción |
|---------|--------|-------------|
| Dominio | ✅ Completada | Models, value objects, enums, specifications, excepciones, 9 servicios y 19 puertos de salida |
| Adaptadores de salida | ✅ Completada | Repositorios JPA/MySQL, auditoría MongoDB, pago/envío/notificación simulados, BCrypt, JWT, configuración |
| Capa de aplicación y API REST | ✅ Completada | 11 casos de uso transaccionales, 55 endpoints, Bean Validation y errores uniformes |
| Seguridad | ✅ Completada | JWT stateless, roles, propiedad del recurso, `denyAll` por defecto, límite de intentos de login, CORS sin comodines |
| Despliegue | ✅ Completada | Dockerfile, `docker-compose.yml`, perfil `prod`, Flyway, CI con pruebas MySQL, Dependency-Check y Trivy |
| Integraciones reales | ⏳ Pendiente | Pasarela de pago, correo/SMS, transportadora (hoy simulados; los puertos son intercambiables) |

## Decisiones del proyecto

- Proyecto **académico y funcional**.
- **Pagos simulados** y **bloqueados en producción**: con el perfil `prod` la aplicación no arranca salvo que se declare
  `NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED=true` (despliegue académico o de demostración).
- **Envíos simulados** (seguimiento ficticio).
- **Notificaciones por log** (destinatario enmascarado), enviadas después de confirmar la transacción.
- Sin integraciones reales de Wompi, Mercado Pago, correo, SMS ni WhatsApp.

## Cambios recientes de estabilización (Fase 0)

1. El pago simulado queda bloqueado en `prod` por defecto.
2. El login tiene límite de intentos (por email y por cliente) y no revela por tiempo si un email existe.
3. Los `IllegalArgumentException` ajenos al proyecto ya no filtran su mensaje al cliente.
4. La auditoría se guarda en MongoDB con el perfil `prod` (antes solo con un perfil `mongodb` que ningún despliegue activaba).
5. El flujo de pedido y envío emite notificaciones (log).
6. Flyway apunta siempre a `db/schema`; la imagen Docker excluye las migraciones antiguas.
7. Corrección de defectos de negocio hallados con pruebas exploratorias: cancelar un pedido pagado ahora reembolsa y repone el
   stock; las devoluciones validan producto y cantidad (no se puede devolver ni reembolsar más de lo comprado) y una segunda
   devolución del mismo pedido responde 409 (antes 500); ambos caminos de despacho dejan pedido y envío coherentes; el carrito
   y el inventario ya no desbordan al sumar cantidades (antes 500).
8. El id de transacción del cobro se guarda en el pedido (migración `V3`) y los reembolsos lo usan.
9. El jar ya no incluye `db/migration`; el Dockerfile tiene `HEALTHCHECK`.
10. El catálogo público y los pedidos del comprador se listan paginados (antes el catálogo devolvía toda la tabla).
11. Los reembolsos comprueban su estado antes de llamar a la pasarela, bloquean su fila y conservan `FAILED` si la pasarela falla (y un `FAILED` puede reprocesarse). Si el pago no se completa tras el cobro, se intenta anular el cobro; los pedidos pagados antes de `V3` usan la referencia derivada `CHARGE-<pedido>` (auditada, no es un id real de pasarela).

## Cierre técnico previo a producción (2026-11-01)

Cambios de esta fase, todos con pruebas (ver `market/docs/ENTORNOS.md` y `market/docs/PAGOS_IDEMPOTENCIA.md`):

1. **Errores HTTP:** una excepción del framework con estado 4xx sin handler propio (p. ej. `Accept` sin representación, 406)
   responde 4xx con el formato de la API en vez de 500. El 500 queda para errores inesperados, sin detalles internos.
2. **Notificaciones por log bloqueadas en `prod`** salvo `NEXUSMARKET_NOTIFICATIONS_LOGGING_ALLOWED=true`: escriben en claro los
   códigos de verificación y de recuperación de contraseña.
3. **Transportadora simulada bloqueada en `prod`** salvo `NEXUSMARKET_SHIPPING_SIMULATED_ALLOWED=true`.
4. **IP del cliente detrás de un proxy:** `TRUSTED_PROXIES` (IP/CIDR). Solo desde esos proxies se lee `X-Forwarded-For`, de derecha a
   izquierda; por defecto no se confía en ninguna cabecera. Un valor inválido o `/0` impide arrancar.
5. **Sin contraseña generada en el log:** se declara un `UserDetailsService` sin usuarios; antes Spring Boot escribía
   "Using generated security password" en el arranque.
6. **MongoDB caído:** los tiempos de espera del cliente quedan acotados (`nexusmarket.audit.mongo.timeout-ms`, 2000 ms). Antes cada
   operación auditada retenía su hilo ~30 s y el health tardaba lo mismo en dar 503.
7. Pruebas nuevas de cabeceras de seguridad (sin cookies de sesión, `nosniff`, `X-Frame-Options`, no-store, HSTS solo bajo HTTPS).
8. `.gitignore` protege `market/effective-pom` y `market/src/main/resources/db/migration/` (sin afectar a `db/schema`).

Verificado con servicios desechables: arranque desde cero con las 3 migraciones, 20 tablas validadas por Hibernate, datos
conservados tras reiniciar la aplicación, y recuperación sin reiniciar tras aislar MySQL (503 con el formato de la API) y MongoDB.

## Limitaciones conocidas y pendientes

| Tema | Detalle |
|------|---------|
| Idempotencia del pago real | El id de transacción ya se guarda (`V3`), pero una pasarela real aún necesita clave de idempotencia, intento de pago y reconciliación. Ver `market/docs/PAGOS_IDEMPOTENCIA.md` |
| Inventario con varias bodegas | `releaseForProduct` libera en la primera bodega con reserva suficiente, que podría no ser la que reservó; solo afecta a productos repartidos en varias bodegas |
| Tamaño del cuerpo JSON | No hay límite explícito del tamaño de una petición JSON (se probaron 100 KB sin problema) |
| Estado de los límites | Los limitadores y los contadores del bloqueo de cuentas son en memoria y por instancia; con N instancias el tope se multiplica. **Decisión pendiente de autorización:** compartir ese estado exigiría tablas nuevas en MySQL y una escritura en cada intento. Hoy: una sola instancia o afinidad por IP en el balanceador. Detrás de un proxy propio, `TRUSTED_PROXIES` |
| Migraciones antiguas | `db/migration/V1__schema.sql` (SQL inválido) y `V2__seed.sql` (cuentas con contraseña conocida) siguen sin versionar y fuera de Flyway, del jar y de la imagen; ahora `.gitignore` las protege. No se borran ni se mueven. Esas credenciales estuvieron publicadas en el historial de GitHub: considérelas expuestas |
| Integridad referencial | Las relaciones entre agregados van por id, sin claves foráneas ni índices secundarios; cualquier cambio exige validar reglas de negocio y una migración `V2` |
| Pruebas MySQL | Solo se ejecutan con `NEXUS_TEST_MYSQL_URL` (el CI las ejecuta y comprueba que no queden omitidas). Incluyen devoluciones y reembolsos, inventario, cambio de contraseña, administrador inicial y pool |
| Funcionalidad | Sin logout ni refresh de token |
| Bloqueo de cuentas | 3 fallos consecutivos desde 2 o más orígenes distintos bloquean la cuenta; solo un ADMIN la reactiva. Un solo origen no puede bloquearla (429 tras 3 intentos por cuenta). Contador en memoria (se pierde al reiniciar; el bloqueo sí persiste). Quien controle varias IP puede aún bloquear a otra persona |
| Migración V2 | `db/schema/V2__user_email_verification.sql` añade `users.email_verified_at`. Validada: las pruebas `*MySqlTest` aplican las tres migraciones con Flyway y Hibernate `validate`, y la imagen arranca desde cero con ellas |
| Recuperación de contraseña | El código se entrega por `NotificationPort`; con el adaptador de log queda escrito en el log, por lo que **en `prod` el adaptador está bloqueado salvo autorización expresa** (solo demostración). No aplica al ADMIN |
| `AccessPolicy` | Ahora vive en la capa de aplicación (consulta puertos de repositorio, no pasa por un caso de uso propio); usa `SecurityContextHolder` de Spring Security |

## Hoja de ruta pendiente

### P0 — Antes de cualquier despliegue real
- [x] Decidir el destino de `db/migration`: se conserva solo en local, fuera de Flyway, del jar, de la imagen y de Git (`.gitignore`).
- [x] Verificar en MySQL real el orden de bloqueo de devoluciones y reembolsos (`ReturnRefundConcurrencyMySqlTest`).
- [x] Persistir el id de transacción del pago (`V3`).
- [ ] Sustituir la pasarela de pago, la transportadora y las notificaciones simuladas por proveedores reales y probarlos en su
      *sandbox* (requisito externo: proveedor, credenciales). Ver `market/docs/PAGOS_IDEMPOTENCIA.md`.
- [ ] Idempotencia de pago con clave por intento, tabla de intentos y reconciliación (cambio de esquema; solo con aprobación).
- [ ] Decidir el estado compartido de los limitadores entre instancias (o fijar una sola instancia).
- [ ] Infraestructura de destino: HTTPS y HSTS en el proxy, secretos, copias de seguridad y restauración, monitoreo.

### P1 — Funcionalidad
- [x] Listados paginados del catálogo público y de los pedidos del comprador (`page`, `size`, `X-Total-Count`).
- [x] Listado de facturas del comprador y búsqueda de catálogo por nombre.
- [x] Edición de nombre y descripción de productos (`PUT /api/products/{id}`).
- [x] Recuperación de contraseña (`/api/auth/password/forgot` y `/reset`) y límite del registro público.
- [x] Verificación de correo (`/api/auth/email/verify`, `/resend`; migración `V2`) y bloqueo de la cuenta tras 3 fallos de login.
- [x] Ejecutar en el CI / MySQL desechable la migración `V2` y comprobar que Hibernate la valida.
- [x] `AccessPolicy` movida a la capa de aplicación y `JwtAuthenticationFilter` a `adapters.in.rest.security`.

### P2 — Mejoras
- [ ] OpenAPI documentado con anotaciones, logs estructurados, métricas y trazas.
- [ ] `Clock` inyectable en dominio y adaptadores.
- [ ] Imágenes Docker fijadas por digest y `HEALTHCHECK` en el Dockerfile.

## Cómo desplegar (local con Docker)

```bash
cp .env.example .env
# Rellene las variables obligatorias (contraseñas, JWT_SECRET) y, para un despliegue académico,
# NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED=true, NEXUSMARKET_SHIPPING_SIMULATED_ALLOWED=true y
# NEXUSMARKET_NOTIFICATIONS_LOGGING_ALLOWED=true (los tres simuladores están bloqueados en prod por defecto)
docker compose up -d
curl http://localhost:8080/actuator/health
```

No hay datos semilla: el administrador inicial se crea con el procedimiento de `market/docs/ADMIN_INICIAL_ACTIVACION.md`.
Swagger está deshabilitado en producción (`SWAGGER_ENABLED=true` lo publica).

## Comandos útiles

```bash
cd market
./mvnw -o -B -ntp test          # pruebas (H2); las *MySqlTest se omiten sin NEXUS_TEST_MYSQL_URL
./mvnw package -DskipTests      # construir el JAR
docker build -t nexusmarket:latest .   # desde la raíz del repositorio
```
