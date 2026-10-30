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

El login tiene límite de intentos (por defecto 10 por email y 50 por cliente cada 15 minutos; se pueden ajustar con
`nexusmarket.login.max-per-email` y `nexusmarket.login.max-per-client`). El registro público de compradores
(`nexusmarket.registration.max-per-*`, 5 por email y 20 por cliente) y las solicitudes de recuperación de contraseña
(`nexusmarket.recovery.max-per-*`, 3 y 20) tienen cupos propios. Todos responden 429 con `Retry-After`; el estado es en
memoria y por instancia.

**Bloqueo de cuentas:** tres inicios de sesión fallidos consecutivos (`nexusmarket.login.lockout-after`, por defecto 3) bloquean la
cuenta; solo un ADMIN la reactiva con `PATCH /api/users/{id}/activate`. **Verificación de correo:** las cuentas de registro
público no inician sesión hasta verificar el correo (la migración `V2` marca como verificados a los usuarios existentes). Los
códigos de recuperación y de verificación se entregan por `NotificationPort`: con el adaptador de log quedan escritos en el log.

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
