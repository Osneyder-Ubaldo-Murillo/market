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
11. Los reembolsos comprueban su estado antes de llamar a la pasarela, bloquean su fila y conservan `FAILED` si la pasarela falla.

## Limitaciones conocidas y pendientes

| Tema | Detalle |
|------|---------|
| Idempotencia del pago real | El id de transacción ya se guarda (`V3`), pero una pasarela real aún necesita clave de idempotencia, intento de pago y reconciliación. Ver `market/docs/PAGOS_IDEMPOTENCIA.md` |
| Inventario con varias bodegas | `releaseForProduct` libera en la primera bodega con reserva suficiente, que podría no ser la que reservó; solo afecta a productos repartidos en varias bodegas |
| Tamaño del cuerpo JSON | No hay límite explícito del tamaño de una petición JSON (se probaron 100 KB sin problema) |
| Límite de login | En memoria y por instancia; detrás de un proxy sin reenvío de cabeceras todos los clientes comparten IP |
| Migraciones antiguas | `db/migration/V1__schema.sql` (SQL inválido) y `V2__seed.sql` (cuentas con contraseña conocida) siguen sin versionar y fuera de Flyway; no se borran ni se mueven. Decisión pendiente del responsable |
| Integridad referencial | Las relaciones entre agregados van por id, sin claves foráneas ni índices secundarios; cualquier cambio exige validar reglas de negocio y una migración `V2` |
| Pruebas MySQL | Solo se ejecutan con `NEXUS_TEST_MYSQL_URL` (el CI las ejecuta). No hay pruebas MySQL de devoluciones y reembolsos |
| Funcionalidad | Sin logout ni refresh de token |
| Bloqueo de cuentas | 3 fallos consecutivos de login bloquean la cuenta; solo un ADMIN la reactiva. Contador en memoria (se pierde al reiniciar; el bloqueo sí persiste). Permite bloquear a otra persona conociendo su email |
| Migración V2 | `db/schema/V2__user_email_verification.sql` añade `users.email_verified_at`. **No validada contra MySQL real** (las pruebas `*MySqlTest` solo corren en el CI) |
| Recuperación de contraseña | El código se entrega por `NotificationPort`; con el adaptador de log queda escrito en el log (solo académico). No aplica al ADMIN |
| `AccessPolicy` | Ahora vive en la capa de aplicación (consulta puertos de repositorio, no pasa por un caso de uso propio); usa `SecurityContextHolder` de Spring Security |

## Hoja de ruta pendiente

### P0 — Antes de cualquier despliegue real
- [ ] Decidir el destino de `db/migration` (versionarla como histórica fuera del classpath, o conservarla local).
- [ ] Verificar en MySQL real el orden de bloqueo de devoluciones y reembolsos.
- [ ] Persistir el id de transacción del pago (cambio de esquema aprobado) o sustituir la pasarela simulada por una real.

### P1 — Funcionalidad
- [x] Listados paginados del catálogo público y de los pedidos del comprador (`page`, `size`, `X-Total-Count`).
- [x] Listado de facturas del comprador y búsqueda de catálogo por nombre.
- [x] Edición de nombre y descripción de productos (`PUT /api/products/{id}`).
- [x] Recuperación de contraseña (`/api/auth/password/forgot` y `/reset`) y límite del registro público.
- [x] Verificación de correo (`/api/auth/email/verify`, `/resend`; migración `V2`) y bloqueo de la cuenta tras 3 fallos de login.
- [ ] Ejecutar en el CI / MySQL desechable la migración `V2` y comprobar que Hibernate la valida.
- [x] `AccessPolicy` movida a la capa de aplicación y `JwtAuthenticationFilter` a `adapters.in.rest.security`.

### P2 — Mejoras
- [ ] OpenAPI documentado con anotaciones, logs estructurados, métricas y trazas.
- [ ] `Clock` inyectable en dominio y adaptadores.
- [ ] Imágenes Docker fijadas por digest y `HEALTHCHECK` en el Dockerfile.

## Cómo desplegar (local con Docker)

```bash
cp .env.example .env
# Rellene las variables obligatorias (contraseñas, JWT_SECRET) y, para un despliegue académico,
# NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED=true
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
