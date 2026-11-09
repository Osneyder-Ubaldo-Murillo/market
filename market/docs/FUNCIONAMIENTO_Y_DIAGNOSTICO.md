# Cómo funciona NexusMarket y qué hacer cuando no funciona

Este documento responde tres preguntas: **por qué funciona** el aplicativo, **cómo saber que funciona** y, cuando no funciona,
**cómo encontrar la causa y arreglarla**. Todo lo que afirma se comprobó contra la pila real (Docker Compose con MySQL y MongoDB).

## 1. Por qué funciona: la cadena, en orden

Para que una petición tenga éxito, cada eslabón de esta cadena tiene que estar bien. Si uno falla, los siguientes no se alcanzan.

| # | Eslabón | Qué garantiza | Cómo se ve que está bien |
|---|---|---|---|
| 1 | **Docker Desktop** | Hay un motor que ejecuta los contenedores | `docker info` responde |
| 2 | **`.env`** | Contraseñas, `JWT_SECRET` y los tres permisos de simulador | `docker compose config` no da error |
| 3 | **MySQL y MongoDB** `healthy` | Las bases existen antes que la aplicación (`depends_on` con `service_healthy`) | `docker compose ps` |
| 4 | **Validaciones de arranque** | La aplicación no arranca con una configuración insegura y dice **qué variable** falta (JWT, contraseña de la base, simuladores) | el log de `app` no muestra `APPLICATION FAILED` |
| 5 | **Flyway + Hibernate `validate`** | Las 3 migraciones crean 20 tablas y el modelo coincide con el esquema | la aplicación arranca |
| 6 | **Administrador inicial** | Existe un `ADMIN` para dar de alta a los demás | log: `Administrador inicial creado` |
| 7 | **`/actuator/health` = `UP`** | La API atiende y llega a las bases | `curl http://localhost:8080/actuator/health` |

### Qué ocurre con una petición

1. **Seguridad:** el filtro JWT valida el token y, **en cada petición**, consulta en la base que la cuenta siga activa y toma el rol vigente.
   Sin token o con uno inválido: 401. Rol insuficiente: 403.
2. **Límites:** el login, el registro y la recuperación tienen límite de intentos (429).
3. **Controlador:** valida el cuerpo (400 si es inválido).
4. **Caso de uso:** abre la transacción y comprueba la propiedad del recurso (un comprador solo ve lo suyo).
5. **Dominio:** aplica las reglas de negocio (estados válidos, stock, unicidad).
6. **Adaptadores:** guardan en MySQL; la auditoría va a MongoDB **después** de confirmar; el pago, el envío y las notificaciones son simulados.
7. **Errores:** `GlobalExceptionHandler` los traduce a 400, 404, 406, 409, 429 o 503, siempre con el mismo formato y sin detalles internos.

### El ciclo de un pedido y quién lo mueve

| Paso | Quién | Endpoint | Resultado |
|---|---|---|---|
| Alta de operador y de vendedor | Administrador | `POST /api/auth/register/staff`, `/register/seller` | cuentas que verifican su correo |
| Producto con stock, publicado | Vendedor | `POST /api/products`, `/api/inventory`, `…/publish` | aparece en el catálogo público |
| Carrito, pedido y pago | Comprador | `…/cart/items`, `…/orders`, `…/payment` | `PAID` y factura emitida |
| Despacho, envío y entrega | **Operador logístico** | `…/dispatch`, `/api/shippings/…` | `DELIVERED` |
| Cancelación (pagado) | Comprador | `…/cancel` | `CANCELLED`, reembolso y stock repuesto |
| Devolución y reembolso | Comprador, administrador, operador | `…/returns`, `…/approve`, `…/process`, `/api/refunds/…/process` | reembolso `PROCESSED` |

## 2. Cómo saber que funciona

1. **Un comando:** `bash scripts/diagnose.sh` revisa los siete eslabones y termina con `RESULTADO: todo en orden`.
2. **El recorrido completo** (verificado el 2026-11-04 contra la pila real): login de los tres roles, bodega, producto, inventario, publicación,
   carrito, pedido, pago simulado con `transactionId` `TXN-…`, factura, cancelación con reembolso, bloqueo y reactivación de cuenta,
   alta de un operador, despacho, envío, entrega (`DELIVERED`), devolución aprobada y procesada, y reembolso `PROCESSED`
   (repetirlo da 409: no se duplica). Los pasos están en la colección de Postman y en las pruebas `RestApiIntegrationTest` y
   `StaffRegistrationIntegrationTest`.
3. **Las pruebas automáticas** (`./mvnw test`, y las de MySQL real en el CI) cubren estos mismos recorridos.

## 3. Cuando no funciona: cómo encontrar la causa

**Regla:** los eslabones fallan en orden. Arregla el **primero** que falle; casi siempre es la causa de los demás.

```bash
bash scripts/diagnose.sh                      # revisa Docker, .env, contenedores, puertos, API y log
bash scripts/diagnose.sh --explain            # lee un error por la entrada estándar y dice su causa
docker compose logs app --tail 100 | bash scripts/diagnose.sh --explain
```

El script **solo lee**: no cambia ni reinicia nada y **nunca imprime** el valor de una contraseña, un secreto ni un token.

### Causas conocidas

| Síntoma | Causa | Cómo comprobarlo | Solución |
|---|---|---|---|
| Postman: `ECONNREFUSED 127.0.0.1:8080` o «Could not send request» | La API no está levantada | `curl http://localhost:8080/actuator/health` sin respuesta | levantar la pila (ver abajo) |
| `docker` falla: «failed to connect to the docker API» | **Docker Desktop está cerrado** (la causa más común) | `docker info` | abrir Docker Desktop; los contenedores arrancan solos (`restart: unless-stopped`) |
| `docker compose` dice que falta una variable | No existe `.env` o le faltan variables | `bash scripts/diagnose.sh` (paso 2) | `cp .env.example .env` y rellenarlo |
| La aplicación se detiene al arrancar con «bloqueado con el perfil prod» | Un simulador (pago, envío, notificaciones) sin autorizar | log de `app` | poner a `true` `NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED`, `NEXUSMARKET_SHIPPING_SIMULATED_ALLOWED` y `NEXUSMARKET_NOTIFICATIONS_LOGGING_ALLOWED` (solo demostración) |
| La aplicación no arranca: `jwt.secret…` | `JWT_SECRET` falta, es corto o de ejemplo | log de `app` | `openssl rand -hex 48` |
| `Access denied for user` | El volumen de MySQL se creó con otras credenciales | log de `app` | restaurar la contraseña original o, en una base de **prueba**, `docker compose down -v` (borra los datos) |
| El contenedor MySQL sale con código 1 | Se lanzó sin `MYSQL_ROOT_PASSWORD` (típico con `docker run` suelto) | `docker logs <contenedor>` | usar Compose, que ya lo define |
| El sistema se congela o un contenedor muere (137) | Memoria insuficiente | `docker compose ps -a` | cerrar programas; la pila necesita varios GB |
| «port is already allocated» | Otro programa usa 8080, 3306 o 27017 | `bash scripts/diagnose.sh` (paso 4) | cerrar ese programa |
| Postman: `ENOTFOUND {{baseurl}}` | La variable no existe: se distinguen mayúsculas (`baseUrl`) y no hay entorno elegido | pasar el cursor sobre la variable | importar el entorno y elegirlo; no escribir `http://` delante |

### Qué significa cada código HTTP que devuelve la API

| Código | Significa | Qué mirar |
|---|---|---|
| 400 | Cuerpo inválido o valor fuera de catálogo | el mensaje dice el campo; el motivo de devolución es `DAMAGED`, `WRONG_ITEM`, `NOT_AS_DESCRIBED` u `OTHER` |
| 401 | Sin token, token inválido o vencido | iniciar sesión de nuevo |
| 403 | Rol o propiedad insuficientes, **correo sin verificar** (`EMAIL_NOT_VERIFIED`) o cuenta bloqueada | el código `code` del cuerpo lo dice |
| 404 | El recurso no existe | el identificador |
| 406 | `Accept` que la API no puede satisfacer | usar `application/json` |
| 409 | Estado inválido, duplicado o conflicto de concurrencia | p. ej. un reembolso ya `PROCESSED` |
| 429 | Demasiados intentos | esperar (`Retry-After`); 15 minutos |
| 503 | La base de datos no está disponible | `docker compose ps`; se recupera sola al volver |

### Levantar la pila y comprobarla

```bash
docker compose up -d          # la primera vez construye la imagen: varios minutos
bash scripts/diagnose.sh      # debe terminar en «todo en orden»
```

El administrador inicial y las cuentas de prueba se describen en `market/docs/ENTORNOS.md` y `market/docs/ADMIN_INICIAL_ACTIVACION.md`.

## 4. Un defecto que se encontró y se corrigió

El recorrido completo destapó que **un pedido pagado no podía avanzar**: despachar y entregar exigen un operador logístico y la API no tenía
ningún camino para crearlo, así que todo pedido se quedaba en `PAID`. Ahora el administrador da de alta personal interno con
`POST /api/auth/register/staff` (solo `LOGISTICS_OPERATOR` o `SUPERVISOR`, nunca `ADMIN`; el correo se verifica como en el resto de cuentas).
Comprobado en la pila real: el pedido que estaba atascado llegó a `DELIVERED`. Cubierto por `StaffRegistrationIntegrationTest`.
