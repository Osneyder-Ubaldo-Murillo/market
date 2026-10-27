# Entornos: desarrollo y producción

## Desarrollo (sin credenciales de producción)

- **Por defecto** (`./mvnw spring-boot:run`): H2 en memoria, sin MySQL ni Mongo, Flyway
  desactivado, secreto JWT propio de desarrollo (`application.properties`). No sirve en
  producción: con el perfil `prod` el arranque lo rechaza.
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
