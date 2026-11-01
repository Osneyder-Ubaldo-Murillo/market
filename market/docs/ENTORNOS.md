# Entornos: desarrollo y producción

## Desarrollo (sin credenciales de producción)

- **Por defecto** (`./mvnw spring-boot:run`): sin base de datos (el DataSource está excluido), solo
  el dominio y sin endpoints REST; Flyway desactivado y secreto JWT propio de desarrollo
  (`application.properties`). No sirve en producción: con el perfil `prod` el arranque lo rechaza.
  Las pruebas automáticas usan H2 en memoria.
- **Con MySQL/Mongo locales** (`-Dspring-boot.run.profiles=jpa`): `application-jpa.properties`
  usa `localhost:3306` con las credenciales **locales de desarrollo** (`root`/`root`) y
  `ddl-auto=update`. Es solo para una máquina de desarrollo; no se reutilizan en ningún
  despliegue.
- Pruebas: `./mvnw -B -ntp clean test` (H2, sin servicios externos).

## Producción (todo explícito, sin valores por defecto)

Perfil `SPRING_PROFILES_ACTIVE=jpa,prod`. Variables obligatorias; si falta alguna, la
aplicación no arranca:

| Variable | Contenido |
|---|---|
| `SPRING_DATASOURCE_URL` | URL JDBC de MySQL |
| `SPRING_DATASOURCE_USERNAME` | usuario de aplicación (no `root`) |
| `SPRING_DATASOURCE_PASSWORD` | contraseña propia; se rechazan las de ejemplo (`root`, `nexus`, `password`...) |
| `SPRING_MONGODB_URI` | URI de MongoDB (con credenciales si hay autenticación) |
| `JWT_SECRET` | al menos 32 bytes aleatorios (`openssl rand -base64 48`) |

Además, **el pago simulado está bloqueado en producción**: con `prod` la aplicación no arranca salvo
que se declare `NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED=true` (propiedad
`nexusmarket.payment.simulated.allowed`). Es una autorización explícita para un despliegue académico o de
demostración; la pasarela es una simulación que no cobra. Con ella, la auditoría se guarda en MongoDB
(el perfil `prod` activa `MongoAuditLogRepositoryPort`); sin `prod` se escribe en el log.

**La transportadora simulada también está bloqueada en producción.** El único adaptador de envíos
(`SimulatedShippingProviderPort`) genera un seguimiento ficticio y no crea ningún envío real. Con `prod` la aplicación no
arranca salvo que se declare `NEXUSMARKET_SHIPPING_SIMULATED_ALLOWED=true` (propiedad `nexusmarket.shipping.simulated.allowed`).
Solo para un despliegue académico o de demostración.

**Las notificaciones por log también están bloqueadas en producción.** El único adaptador de notificaciones
(`LoggingNotificationPort`) escribe en el log el contenido de cada mensaje, y eso incluye los **códigos de verificación de
correo y de recuperación de contraseña**: quien pueda leer el log puede tomar una cuenta. Con `prod` la aplicación no arranca
salvo que se declare `NEXUSMARKET_NOTIFICATIONS_LOGGING_ALLOWED=true` (propiedad
`nexusmarket.notifications.logging.allowed`); al arrancar con ella se escribe un aviso en el log. Es una autorización expresa
y **solo para una demostración académica**: sin un proveedor real de correo/SMS las cuentas de registro público no pueden
verificar su correo ni recuperar la contraseña salvo leyendo el código en el log. Un despliegue con usuarios reales debe
reemplazar el adaptador por uno que implemente `NotificationPort` (sin credenciales ni proveedor inventados: es un requisito
externo pendiente) y no declarar esta variable.

El login tiene límite de intentos (por defecto 10 por email y 50 por cliente cada 15 minutos; se pueden ajustar con
`nexusmarket.login.max-per-email` y `nexusmarket.login.max-per-client`). El registro público de compradores
(`nexusmarket.registration.max-per-*`, 5 por email y 20 por cliente) y las solicitudes de recuperación de contraseña
(`nexusmarket.recovery.max-per-*`, 3 y 20) tienen cupos propios. Todos responden 429 con `Retry-After`; el estado es en
memoria y por instancia.

**Bloqueo de cuentas:** tres inicios de sesión fallidos consecutivos (`nexusmarket.login.lockout-after`, por defecto 3) desde al
menos dos orígenes distintos (`nexusmarket.login.lockout-min-clients`, por defecto 2) bloquean la cuenta; solo un ADMIN la
reactiva con `PATCH /api/users/{id}/activate`. Un único origen queda limitado a 3 intentos por cuenta cada 15 minutos
(`nexusmarket.login.max-per-client-and-email`) y no puede bloquearla. **Verificación de correo:** las cuentas de registro
público no inician sesión hasta verificar el correo (la migración `V2` marca como verificados a los usuarios existentes). Los
códigos de recuperación y de verificación se entregan por `NotificationPort`: con el adaptador de log quedan escritos en el log.

**Identificación del cliente y proxy inverso.** Los límites de intentos y el bloqueo de cuentas distinguen a los clientes por
su dirección. **Por defecto es la de la conexión TCP y no se confía en ninguna cabecera**: cualquier cliente puede enviar
`X-Forwarded-For`, y aceptarla sin más permitiría eludir los límites cambiando de "IP" en cada petición. Si la aplicación va
detrás de un proxy inverso propio, todas las conexiones llegan desde la dirección del proxy y los clientes compartirían cupo
(y el bloqueo, que necesita fallos desde dos orígenes, no se produciría nunca). Para ese caso declare los proxies propios en
`TRUSTED_PROXIES` (propiedad `nexusmarket.proxy.trusted-proxies`): direcciones IP o CIDR separados por comas, p. ej.
`TRUSTED_PROXIES=10.0.0.5,172.16.0.0/12`.

- Solo si la conexión TCP procede de uno de esos proxies se lee `X-Forwarded-For`, **de derecha a izquierda**: se descartan las
  entradas que también son proxies de confianza y la primera restante es el cliente. Lo que un cliente escriba a la izquierda
  no sirve de nada, porque cada proxy añade al final a quien le habló.
- Ante cualquier duda se usa la dirección TCP: entrada que no es una IP literal (no se resuelven nombres), con puerto, cabecera
  de más de 1024 caracteres o con más de 20 entradas, o una cadena formada solo por proxies de confianza. La cabecera
  `Forwarded` (RFC 7239) no se interpreta. No active `server.forward-headers-strategy`: confiaría en cualquier remitente (una
  prueba lo vigila).
- Un valor que no sea una IP o un CIDR válido, o un prefijo `/0`, impide arrancar la aplicación.
- Configure también el proxy para que **sustituya** (no solo añada a) cualquier `X-Forwarded-For` que traiga el cliente si
  recibe tráfico directo de Internet; con esta lectura de derecha a izquierda basta con que añada la dirección de quien le habló.

**HTTPS y cabeceras.** La aplicación no termina TLS: el HTTPS lo pone el proxy inverso. Las respuestas llevan
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` y `Cache-Control: no-store` (comprobado por pruebas), y la API no emite
cookies ni crea sesión (JWT en `Authorization`, por eso CSRF no aplica). **`Strict-Transport-Security` solo lo emite la aplicación
para peticiones que ve como HTTPS**; detrás de un proxy que termina TLS ve HTTP, así que el **proxy debe añadir HSTS** (y redirigir
HTTP a HTTPS). Los orígenes CORS se declaran exactos en `CORS_ALLOWED_ORIGINS` (no se admiten comodines).

**Auditoría en MongoDB y su disponibilidad.** Con el perfil `prod` la auditoría se escribe en MongoDB. Si MongoDB deja de responder,
la petición que audita termina igualmente (el fallo de auditoría no la revierte), pero antes esperaba el tiempo por defecto del
driver (~30 s) y `/actuator/health` tardaba lo mismo en dar 503. Los tiempos de espera quedan acotados con
`nexusmarket.audit.mongo.timeout-ms` (variable `NEXUSMARKET_AUDIT_MONGO_TIMEOUT_MS`, por defecto 2000 ms; entre 100 y 60000):
selección de servidor y conexión usan ese valor y la lectura cinco veces ese valor. Se aplica sobre las opciones equivalentes de la
URI de MongoDB. Medido con servicios desechables: tras aislar MongoDB, las operaciones auditadas tardan ~2 s (la primera, que
reutiliza una conexión abierta, hasta ~10 s) y `/actuator/health` responde 503 en ~2 s; al volver MongoDB todo se recupera sin
reiniciar la aplicación. Con MySQL aislado la API responde 503 `SERVICE_UNAVAILABLE` y se recupera sola al volver.

**Varias instancias.** Todos los limitadores (login, registro, recuperación, verificación y cambio de contraseña) y los
contadores de fallos del bloqueo de cuentas **viven en memoria y son locales a cada instancia**; solo el estado `BLOCKED` de la
cuenta se guarda en MySQL. Con N instancias detrás de un balanceador, el tope efectivo se multiplica por hasta N y los fallos
repartidos entre instancias pueden no sumar nunca el tope. Hoy esto se mitiga operando **una sola instancia** (es lo que
despliega `docker-compose.yml`) o configurando el balanceador con **afinidad por IP de cliente**, que devuelve la eficacia a los
límites por cliente (no a los límites por cuenta cuando el atacante usa varias IP). Compartir ese estado entre instancias
exigiría tablas nuevas en MySQL y una escritura en cada intento; es una decisión de diseño pendiente de autorización
(ver `DIAGNOSTICO.md`).

Con Docker Compose: copie `.env.example` a `.env` (está en `.gitignore`) y rellene
`MYSQL_ROOT_PASSWORD`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MONGO_USER`, `MONGO_PASSWORD` y
`JWT_SECRET` con valores generados (`openssl rand -hex 16` para contraseñas). `docker compose`
se niega a arrancar si falta alguno. MySQL y Mongo solo se publican en `127.0.0.1`.

Nunca coloque secretos reales en el repositorio, en logs ni en documentación. Para
secretos gestionados por la plataforma, inyéctelos como variables de entorno del contenedor.

Antes de desplegar sobre una base que no sea nueva, siga
`docs/migrations/PROCEDIMIENTO_INSTALACIONES.md`.

## Conexión a MySQL en Docker Compose (TLS)

`docker-compose.yml` entrega a la aplicación
`jdbc:mysql://mysql:3306/<MYSQL_DATABASE>?sslMode=REQUIRED&serverTimezone=UTC`.

- `sslMode=REQUIRED` **exige cifrado TLS** en la conexión con MySQL (MySQL 8 genera su propio certificado al
  iniciarse). Con TLS, la autenticación `caching_sha2_password` del usuario de la aplicación funciona sin
  `allowPublicKeyRetrieval=true`.
- **No valida la identidad del certificado del servidor** (no hay CA ni nombre de host verificados): protege frente a
  escuchas pasivas en la red, pero no frente a un intermediario activo que presente su propio certificado. Si la red
  entre la aplicación y MySQL no es de confianza, use `sslMode=VERIFY_CA` o `VERIFY_IDENTITY` con un certificado
  firmado por una CA propia (requiere cambios de despliegue que esta configuración no incluye).
- No use `useSSL=false` (con MySQL 8 impide arrancar con "Public Key Retrieval is not allowed") ni
  `allowPublicKeyRetrieval=true` (obtiene la clave pública sin autenticar).
- Una prueba (`ComposeJdbcUrlTest`) impide que se reintroduzcan.
- El perfil de desarrollo local `jpa` (`application-jpa.properties`) conserva su propia URL contra un MySQL local; no
  forma parte del despliegue con Compose.
