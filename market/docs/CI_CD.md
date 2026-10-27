# CI/CD de NexusMarket

Workflow: `.github/workflows/ci.yml` (en la raíz del repositorio). Scripts auxiliares: `scripts/ci/`.

## Jobs

| Job | Qué hace | Bloquea si |
|---|---|---|
| `build-and-test` | Compila y ejecuta **toda** la suite, incluidas las pruebas de integración con **MySQL 8 real** (contenedor desechable iniciado por el job). Después `verify-mysql-tests-ran.sh` exige que cada clase gated tenga informe, pruebas ejecutadas y 0 omitidas/fallos/errores. | Falla cualquier prueba, o una clase MySQL no se ejecutó (variables ausentes, servicio caído). |
| `dependency-check` | OWASP Dependency-Check **12.2.2** (versión fijada). | Falta el secreto `NVD_API_KEY`; hay error de descarga o de análisis (`failOnError=true`); alguna dependencia tiene CVSS ≥ 7.0 (`failBuildOnCVSS=7`: HIGH y CRITICAL). |
| `image-verify` | Construye la imagen **sin publicarla**, la arranca con MySQL y MongoDB desechables y comprueba `/actuator/health` = UP, `GET /api/products` = 200, ruta protegida = 401, OpenAPI no público y usuario no root. Luego la analiza con Trivy 0.75.0. | Falla el arranque o la salud; Trivy encuentra vulnerabilidades HIGH/CRITICAL **con corrección disponible**; Trivy no puede descargar su base de datos. |
| `build-docker` | Publica en Docker Hub **la misma imagen** verificada por `image-verify` (se pasa como artefacto, no se reconstruye). Solo en `push` a `main`. | No pasaron los tres jobs anteriores, o faltan `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`. |
| `deploy-staging` | **Deshabilitado** (`if: false`). | Si se habilita sin implementarlo, falla con un error explícito; no informa de éxito. |

## Secretos que debe configurar el propietario del repositorio
(Settings → Secrets and variables → Actions → New repository secret. No comparta sus valores.)

- `NVD_API_KEY`: clave gratuita de la NVD (<https://nvd.nist.gov/developers/request-an-api-key>). Sin ella `dependency-check` falla a propósito.
- `DOCKERHUB_USERNAME` y `DOCKERHUB_TOKEN`: un *access token* de Docker Hub con permiso de escritura **solo** sobre `osneydermurillo/nexusmarket`. Sin ellos la publicación queda bloqueada.

Los secretos no se exponen a los `pull_request` que vienen de forks: en ellos `dependency-check` fallará por no tener `NVD_API_KEY`. Es el comportamiento seguro.

## Pruebas MySQL en CI

Contrato real de las pruebas (`@EnabledIfEnvironmentVariable` y `@TestPropertySource`):
`NEXUS_TEST_MYSQL_URL`, `NEXUS_TEST_MYSQL_USER` y `NEXUS_TEST_MYSQL_PASSWORD`. El job las apunta a un contenedor `mysql:8.0` que él mismo inicia con `docker run ... --max-connections=300`
(base `nexusmarket_ci`, solo `127.0.0.1`, credenciales solo de prueba, destruido al terminar). No se usa `services:` porque Actions no permite pasar argumentos de arranque al servidor y el límite por defecto (151) produce `Too many connections` en las pruebas de concurrencia. El paso de espera imprime `max_connections` y falla si no es 300. No hay conexión con bases externas.
Los informes de Surefire se conservan 14 días como artefacto `test-results`.

Reproducir en local contra una base **desechable** (nunca contra datos reales):
`docker run -d --tmpfs /var/lib/mysql -p 127.0.0.1:3307:3306 -e MYSQL_ROOT_PASSWORD=<local> -e MYSQL_DATABASE=nexusmarket_ci mysql:8.0`
y exporte las tres variables con `sslMode=DISABLED&allowPublicKeyRetrieval=true` en la URL.

## Despliegue (fase posterior)

No hay entorno de despliegue ni credenciales aprobados, así que el job `deploy-staging` está apagado. Para habilitarlo:
1. Decidir el destino (servidor con Docker Compose, Kubernetes, etc.) y crear los secretos/entorno de GitHub necesarios.
2. Sustituir el paso «Despliegue no implementado» por el despliegue real de la imagen verificada (por digest o etiqueta `sha`).
3. Añadir una comprobación posterior (salud del servicio desplegado) y un `environment` de GitHub con aprobación manual.
4. Quitar `if: ${{ false }}`.

## Limitaciones conocidas

- `build-and-test` ejecuta las pruebas con ese MySQL, pero la caída *real* de MySQL se simula con un proxy TCP (no se detiene el servicio).
- Las acciones de GitHub están fijadas por etiqueta mayor (`@v4`), no por SHA.
- Trivy ignora vulnerabilidades sin corrección publicada (`--ignore-unfixed`); quedan fuera del análisis hasta que exista parche.
- El análisis de dependencias deshabilita OSS Index (requiere credenciales) y los analizadores de Node/JavaScript (el backend es Java).
