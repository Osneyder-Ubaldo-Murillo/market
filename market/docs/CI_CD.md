# CI/CD de NexusMarket

Workflows (en la raíz del repositorio): `.github/workflows/ci.yml` (CI/CD principal) y `.github/workflows/update-dependency-check-data.yml` (mantiene la base de datos de NVD, ver más abajo). Scripts auxiliares: `scripts/ci/`.

## Jobs

| Job | Qué hace | Bloquea si |
|---|---|---|
| `build-and-test` | Compila y ejecuta **toda** la suite, incluidas las pruebas de integración con **MySQL 8 real** (contenedor desechable iniciado por el job). Después `verify-mysql-tests-ran.sh` exige que cada clase gated tenga informe, pruebas ejecutadas y 0 omitidas/fallos/errores. | Falla cualquier prueba, o una clase MySQL no se ejecutó (variables ausentes, servicio caído). |
| `dependency-check` | OWASP Dependency-Check **12.2.2** (versión fijada). **No descarga NVD**: restaura la última base de datos publicada por el workflow `update-dependency-check-data.yml`, comprueba que es utilizable y analiza con `-DautoUpdate=false`. | No hay base de datos de NVD en caché, es de otra versión o tiene más de 7 días; hay error de análisis (`failOnError=true`); alguna dependencia tiene CVSS ≥ 7.0 (`failBuildOnCVSS=7`: HIGH y CRITICAL). Nunca se omite el análisis. |
| `image-verify` | Construye la imagen **sin publicarla**, la arranca con MySQL y MongoDB desechables y comprueba `/actuator/health` = UP, `GET /api/products` = 200, ruta protegida = 401, OpenAPI no público y usuario no root. Luego la analiza con Trivy 0.75.0. | Falla el arranque o la salud; Trivy encuentra vulnerabilidades HIGH/CRITICAL **con corrección disponible**; Trivy no puede descargar su base de datos. |
| `build-docker` | Publica en Docker Hub **la misma imagen** verificada por `image-verify` (se pasa como artefacto, no se reconstruye). Solo en `push` a `main`. | No pasaron los tres jobs anteriores, o faltan `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`. |
| `deploy-staging` | **Deshabilitado** (`if: false`). | Si se habilita sin implementarlo, falla con un error explícito; no informa de éxito. |

## Secretos que debe configurar el propietario del repositorio
(Settings → Secrets and variables → Actions → New repository secret. No comparta sus valores.)

- `NVD_API_KEY`: clave gratuita de la NVD (<https://nvd.nist.gov/developers/request-an-api-key>). Solo la usa el workflow de actualización de NVD, que falla a propósito si falta. El job `dependency-check` del CI no la necesita: analiza con `-DautoUpdate=false` y, según el código de Dependency-Check 12.2.2, la clave solo se emplea en la fuente de datos de NVD, que no se ejecuta con esa opción.
- `DOCKERHUB_USERNAME` y `DOCKERHUB_TOKEN`: un *access token* de Docker Hub con permiso de escritura **solo** sobre `osneydermurillo/nexusmarket`. Sin ellos la publicación queda bloqueada.

Los secretos no se exponen a los `pull_request` que vienen de forks. El análisis de dependencias de esos PR no necesita secretos (usa la base restaurada de la caché del repositorio base).

## Base de datos de NVD (Dependency-Check)

Workflow: `.github/workflows/update-dependency-check-data.yml` («Actualizar base de datos NVD (Dependency-Check)»).

- **Cuándo corre:** a mano (Actions → ese workflow → *Run workflow*) y cada día a las 03:23 UTC (`schedule`, solo en la rama por defecto). GitHub puede desactivar los workflows programados de un repositorio público tras un periodo largo sin actividad; si el CI empieza a quejarse de que no hay base de datos, ejecútelo a mano.
- **Qué hace:** restaura la última base (si existe), ejecuta `dependency-check-maven:12.2.2:update-only` con `-DfailOnError=true` y la clave `NVD_API_KEY`, escribe un marcador (`nexusmarket-nvd-update.properties`: versión, fecha, ejecución y commit) y guarda la caché. Sin `continue-on-error` ni `|| true`. Según el código de Dependency-Check 12.2.2 (`Engine.doUpdates`), un fallo de la descarga de NVD se registra como error y `update-only` puede terminar con código 0; por eso, antes de escribir el marcador, el workflow rechaza la base si el registro contiene errores de actualización (`UpdateException` o mensajes de error de NVD). Esa comprobación se basa en mensajes del código de la herramienta y no sustituye a inspeccionar el log si algo parece raro.
- **Caché:** las cachés de GitHub son inmutables, así que cada ejecución correcta publica una clave nueva `dependency-check-data-<versión>-<run_id>-<intento>` y el CI restaura la más reciente por el prefijo `dependency-check-data-<versión>-`. Una ejecución fallida o con timeout **no guarda nada**, de modo que nunca sustituye a la última base válida. La versión del plugin forma parte de la clave. GitHub elimina las cachés sin uso durante 7 días y, al llegar al límite de 10 GB del repositorio, las menos recientemente usadas.
- **Qué comprueba el CI antes de analizar:** que se restauró alguna caché, que existe el marcador y un `odc.mv.db` no vacío, que la versión del marcador coincide con la del workflow, que su fecha es válida y no futura (se toleran 5 min de desfase de reloj) y que la actualización completa tiene como máximo 7 días (aviso a partir de 2). Si algo falla, el job falla con un mensaje que indica cómo ejecutar el workflow de actualización. El umbral de 7 días es una decisión de este proyecto.
- **Publicación de imágenes:** `build-docker` depende de `dependency-check`; si no hay base utilizable o el análisis falla, no se publica nada.
- **Tiempo:** el job de actualización tiene un límite de 350 min y el paso de actualización de 320 min (el máximo de un job en runners de GitHub es 6 h). Si la **primera** descarga completa no cabe, el paso falla por tiempo y no se guarda nada. La documentación consultada no garantiza que una actualización interrumpida se reanude, así que no se asume.
- **Frescura:** si el workflow programado falla, el CI sigue usando la última base válida hasta que supere los 7 días; a partir de ahí falla.

Diagnóstico de errores de NVD sin exponer secretos: abra el log del paso «Actualizar la base de datos de NVD» (GitHub enmascara el valor de los secretos) y busque códigos HTTP (403, 429, 503), reintentos o el progreso de registros. No use `-X` ni escriba el valor de la clave en comentarios, issues o logs.

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
