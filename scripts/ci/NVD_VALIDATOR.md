# Validador de la base NVD de Dependency-Check 12.2.2

`validate-nvd-database.sh` comprueba si una base H2 de Dependency-Check (`odc.mv.db`) puede considerarse válida para
transferirla (caché de Actions u otro medio). **Integración en CI: fase 2 (bloquea)**: los workflows lo ejecutan y, si no
aprueba la base, el job falla (ver «Integración en CI» más abajo y `market/docs/CI_CD.md`).

## Qué comprueba

| Comprobación | Modo | Cómo |
|---|---|---|
| `odc.mv.db` existe, es un archivo regular (no un enlace) y no está vacío | ambos | sistema de archivos |
| SHA-256 (64 hexadecimales en minúscula) y tamaño (entero no negativo) calculados con comprobación de errores | ambos | `sha256sum`, `wc -c`; un fallo es código 2 y no se publica nada |
| Marcador del proyecto: 13 claves exactas, sin duplicadas ni desconocidas, LF; versión, fechas, SHA-256 y tamaño | `verify` | **antes de abrir H2** |
| Inicio de la actualización creíble: no futuro y a lo sumo 21 600 s (límite de un job de GitHub) + 300 s antes de ahora | `create` | `--job-start-epoch`, **antes de abrir H2** |
| La copia de trabajo tiene el mismo SHA-256 que el original | ambos | tras `cp` |
| La base se abre en solo lectura y es H2 válido (no truncada, no corrupta) | ambos | `NvdDbInspect.java` sobre una **copia** (`ACCESS_MODE_DATA=r;IFEXISTS=TRUE`) |
| `properties.version` = `5.6` | ambos | tabla `properties` |
| `nvd.api.last.checked` y `nvd.api.last.modified` presentes, con formato `uuuu-MM-dd'T'HH:mm:ssX` (el de `DatabaseProperties.save`), > 0, no futuros (tolerancia de reloj 300 s) y `modified` ≤ `checked` + 300 s | ambos | tabla `properties` |
| `vulnerability` y `cpeEntry` con al menos 1 fila; `knownExploited` contable (puede ser 0) | ambos | `SELECT COUNT(*)` |
| `nvd.api.last.checked` ≥ inicio − `valid-hours`·3600 | `create` | política de actualización (ver más abajo) |
| `nvd.api.last.checked` ≥ `update_started_epoch` − `valid-hours`·3600, ≤ `completed_epoch` + 300 s, y de ≤ `--max-age-days` | `verify` | marcador + base |
| `nvd_api_last_checked`, `nvd_api_last_modified` y `count_*` del marcador iguales a lo leído de la base | `verify` | marcador + base |
| Archivos adicionales en el directorio de datos | ambos | ver «Archivos adicionales» |

Los **marcadores internos** (`nvd.api.last.*`, `version`) son los de Dependency-Check y viven *dentro* de la base.
El **marcador del proyecto** (`nexusmarket-nvd-update.properties`) es un archivo aparte; no se confunden.

### Orden de la validación (qué ocurre antes de abrir H2)

El archivo, su SHA-256/tamaño, el marcador (formato, claves, fechas, SHA-256, tamaño) y el inicio de la actualización se
comprueban **antes** de abrir la base con H2. Si ya hay un rechazo, la base no se abre y la salida lo dice
(`No se abrio la base con H2`). Las comparaciones entre lo que declara el marcador y lo que contiene la base
(fechas internas y recuentos) necesitan H2 y se hacen después.

**Esto reduce la exposición, no resuelve la autenticidad.** Abrir con H2 un archivo hostil es una superficie de
ejecución de código (la misma que ya tiene el plugin al usar esa caché), y un marcador forjado con un SHA-256 y un
tamaño coincidentes llega a abrirse igualmente.

## Contrato del marcador del proyecto

Trece claves, todas obligatorias, ninguna otra (una clave desconocida, duplicada o una línea mal formada se rechaza), con
finales de línea LF (un marcador con CRLF se rechaza):

| Clave | Quién la aporta | Significado |
|---|---|---|
| `update_started_epoch` | `--emit` (eco de `--job-start-epoch`, ya validado) | segundos UTC del inicio de la actualización |
| `odc_sha256`, `odc_size_bytes` | `--emit` | SHA-256 y tamaño de `odc.mv.db` en el momento de validarlo |
| `nvd_api_last_checked`, `nvd_api_last_modified` | `--emit` | valor literal de esas propiedades internas |
| `count_vulnerability`, `count_cpeEntry`, `count_knownExploited` | `--emit` | `SELECT COUNT(*)` de cada tabla (se contrastan con la base y, si se pasan `--min-*`, con los mínimos) |
| `dependency_check_version` | workflow | versión de la herramienta (`12.2.2`) |
| `completed_epoch`, `completed_at` | workflow | fin de la actualización (`date -u +%s` y `+%Y-%m-%dT%H:%M:%SZ`) |
| `run_id`, `commit` | workflow | `GITHUB_RUN_ID` y `GITHUB_SHA` (40 hexadecimales) |

El marcador final = lo que escribe `--emit` (8 claves) + las 5 del workflow. Eso es lo que ejercitan las pruebas
(`ida-y-vuelta`). Desde la fase 2, el workflow de actualización escribe siempre el marcador de 13 claves y **solo si el
validador aprueba la base**: si la rechaza (o no puede ejecutarse), el run falla antes de escribir el marcador y de guardar la
caché, y se conserva la anterior. Una caché publicada antes de la fase 2 con el marcador de 5 claves (fase 1, base no
verificada) la rechaza `verify` («falta la clave …»); las anteriores a la integración también.

## Códigos de salida

| Código | Significado |
|---|---|
| `0` | válida por estas comprobaciones (no demuestra que la base esté completa) |
| `1` | rechazada |
| `2` | uso o entorno incorrecto: falta `--valid-hours`, falta Java o un jar, classpath erróneo, `mktemp`/`cp`/`sha256sum`/`wc`/escritura fallidos. No se pudo validar |
| `3` | no aprobada automáticamente: hay archivos que exigen revisión humana (ver más abajo). No se emite nada |

Solo el `0` es una aprobación. **No existe ningún modo que apruebe una base sin leer sus marcadores internos**:
`--file-only` fue eliminado (pasarlo es un error de uso, con o sin `GITHUB_ACTIONS=true`).

## Parámetros: operativos y exclusivos de pruebas

| Tipo | Parámetros |
|---|---|
| **Operativos** | `--mode`, `--dir`, `--valid-hours` (**obligatorio**), `--job-start-epoch` (obligatorio en `create`), `--max-age-days` (7), `--expected-version` (12.2.2), `--emit` (solo `create`), `--min-vulnerability`, `--min-cpe-entry`, `--min-known-exploited` (0 = sin mínimo), `--java`, `--h2-jar`, `--core-jar`; variables `JAVA`, `JAVA_HOME`, `M2_REPO` |
| **Exclusivos de pruebas** | `--now-epoch`, `--max-job-seconds`, `--expected-schema` |

- Los parámetros exclusivos de pruebas se **rechazan con código 2** si no está `NVD_VALIDATOR_TEST=1`.
- **`NVD_VALIDATOR_TEST=1` es un mecanismo de pruebas, no una autenticación ni una frontera de seguridad**: cualquiera
  que controle el entorno del proceso puede activarlo. Su único fin es evitar un uso accidental. Por eso, si se usa
  algún parámetro de pruebas, la salida lo declara (`MODO DE PRUEBAS`) y avisa de que el resultado no sirve para
  aprobar una base real.
- Los parámetros operativos pueden relajar el resultado (`--max-age-days`, `--valid-hours`, rutas de Java y jars). No se
  restringen, porque tienen usos documentados, pero la salida muestra siempre la **política efectiva**
  (`valid-hours`, `max-age-days`) y el **entorno** (`java`, `h2`, `core`) para que se vea en el log.
- Los valores numéricos se validan sin ceros a la izquierda (bash los leería como octal) y con una longitud máxima
  (los `--min-*`, hasta 9 cifras).
- **Mínimos de registros (`--min-vulnerability`, `--min-cpe-entry`, `--min-known-exploited`):** por defecto 0 = sin mínimo
  (siempre se exige `≥ 1` en `vulnerability` y `cpeEntry`). Si el recuento queda por debajo, la base se rechaza («la tabla X
  tiene N registros, por debajo del mínimo exigido (M): descarga incompleta o truncada»); una tabla vacía sigue rechazándose
  con su motivo propio. Se aplican en `create` y en `verify`, se muestran en la política efectiva y el aviso final distingue si se
  exigió algún mínimo. No cambian lo que publica `--emit`.
- Una opción sin valor al final se rechaza (antes hacía fallar `shift 2` y el script no terminaba).

## Política de actualización: `--valid-hours` (obligatorio)

Dependency-Check **omite** la actualización si la anterior fue hace menos de `nvd.api.check.validforhours` (4 h por
defecto) y, en ese caso, no cambia ninguna marca (lectura del bytecode de `processApi`). Una omitida no es una
actualización ejecutada. Por eso `--valid-hours` no tiene valor por defecto: si falta, el código es 2.

- `--valid-hours 0`, junto con `-DnvdValidForHours=0` en el plugin (con 0 o menos la actualización **nunca** se
  omite): se exige `last.checked` ≥ inicio, es decir, «esta ejecución actualizó». Es la política recomendada para el
  workflow de actualización.
- `--valid-hours N` con N > 0: se acepta una base cuyo `last.checked` esté dentro de esa ventana respecto al inicio,
  aunque la actualización se haya omitido o haya fallado sin dejar rastro en el log. El validador advierte
  explícitamente de que **no demuestra que la ejecución actual haya actualizado la base**.

**Limitación:** el validador se fía de `--job-start-epoch`; que sea el inicio real lo garantiza el workflow (capturarlo
después de restaurar la caché y justo antes de `update-only`). Con los datos disponibles no puede comprobarse por sí
solo. Los topes (duración ≤ 21 600 s + 300 s, inicio no futuro) impiden falsificar un inicio muy antiguo para hacer
pasar una base vieja, pero no uno dentro de esas 6 h.

## `--emit`

Solo en `create`. Antes de validar se **borra** cualquier archivo previo; si la base es válida se escribe en un temporal
del mismo directorio y se publica con `mv` (atómico). Un fallo de validación o de escritura no deja ningún resultado ni
temporal. Antes de escribir se revalidan SHA-256, tamaño y recuentos. Se rechaza un destino dentro de `--dir` (acabaría
en la caché), un directorio o un enlace simbólico. Un error de uso *anterior* a esa comprobación (modo o `--dir`
inválidos) no toca el archivo: quien consuma el resultado debe comprobar siempre el código de salida (y no publicar nada
con el código 3).

## Archivos adicionales

Qué se **sabe** (del código de Dependency-Check 12.2.2 y de las pruebas con bases sintéticas):
- La base H2 se llama `odc.mv.db`; Dependency-Check, al purgar, borra además `odc.trace.db` y `odc.update.lock`
  (cadenas halladas en el bytecode), lo que indica que son archivos que conoce. También puede añadir
  `AUTO_SERVER=TRUE` a la conexión, lo que crearía un archivo de bloqueo de H2.
- H2 escribe `odc.trace.db` cuando registra una excepción. Se observó con una base sintética mientras fallaba la
  creación de una fixture.

Qué se **hace**:

| Categoría | Qué es | Efecto |
|---|---|---|
| Esperados | `odc.mv.db` y `nexusmarket-nvd-update.properties` | sin aviso |
| Propios de Dependency-Check, **reconocidos solo por nombre y tipo** | el directorio `cache/` y los archivos `jsrepository.json`, `jsrepository.json.properties`, `publishedSuppressions.xml` y `publishedSuppressions.xml.properties` | se informa en una línea («reconocidos solo por nombre (su contenido NO se valida)»); no avisan ni bloquean |
| **Exigen revisión humana** | cualquier otro archivo `odc.*` (p. ej. `odc.trace.db`, `odc.update.lock`, `odc.lock.db`, `odc.mv.db.tempFile`, `odc.temp.db`) y los enlaces simbólicos | aviso `REVISION HUMANA`; si el resto es válido, **código 3** y no se emite nada; si hay otro rechazo, manda el código 1 y el archivo sigue listado |
| No reconocidos | cualquier otro archivo, **incluidos los ocultos**, y los directorios | se listan en un aviso («no se ha verificado que sea inocuo»); no bloquean |

**Evidencia real:** en dos actualizaciones completas en `ubuntu-latest` (runs 5 y 6 de *Actualizar base de datos NVD*, 2026-11-01)
el directorio de datos contenía, además de `odc.mv.db` y el marcador, exactamente esos cinco elementos (por sus nombres, relacionados con suppressions
hospedados, RetireJS y la caché del plugin; su origen no se ha verificado más allá de eso) y ningún archivo `odc.*`. Por eso se reconocen. El reconocimiento es **solo por
nombre y tipo**: un enlace simbólico con ese nombre sigue exigiendo revisión humana, y un directorio llamado `jsrepository.json`,
un archivo llamado `cache`, o variantes (`.bak`, `.old`, otra capitalización) siguen avisándose como no reconocidos. **No se
valida el contenido de ninguno** ni se ha comprobado que `cache/` sea inocuo más allá de ser lo que el plugin crea. Qué otros
archivos puede dejar Dependency-Check en otras circunstancias sigue sin conocerse: los no reconocidos no se rechazan, y los
`odc.*` se tratan como «revisar» y no como «base dañada».

## Qué NO demuestra (limitaciones)

1. **No demuestra que la base esté completa.** Solo exige los marcadores que Dependency-Check escribe al final de una
   actualización completa (lectura del bytecode de 12.2.2) y que haya datos. Una base parcial a la que alguien añadiera
   a mano esos marcadores pasaría.
2. **Los mínimos de registros no demuestran que la base esté completa.** Solo detectan una descarga muy truncada. Los valores de CI
   (`vulnerability` 200 000, `cpeEntry` 215 000, `knownExploited` 1 400) son ~80 % de lo observado en dos ejecuciones reales del
   mismo día (246 568 / 268 022 / 1 766, idénticos en ambas): es una **decisión de este proyecto con una muestra pequeña**, no un
   valor demostrado. Una base con el 85 % de los registros pasaría. NVD solo crece: hay que revisarlos de vez en cuando, y si
   los recuentos reales variaran más de lo previsto, recalibrarlos. Sin `--min-*` solo se exige `≥ 1` en `vulnerability` y
   `cpeEntry`.
3. **Un SHA-256 correcto solo prueba que el archivo no cambió**, nunca que sea completo ni correcto. Es integridad, no
   autenticidad: el marcador viaja dentro de la misma caché, así que quien pueda escribirla puede forjar base y marcador.
   `run_id` y `commit` son datos declarativos; no se verifica su procedencia.
4. **El caso positivo de las pruebas es sintético.** Valida la lógica del script, no una base NVD real.
5. **Sin verificar con una base real:** que `ACCESS_MODE_DATA=r` abra siempre una base real cerrada por
   `Engine.close()`; que las credenciales (`data.user`/`data.password` del `dependencycheck.properties` del jar core) y
   el H2 2.4.240 sirvan para una base creada por el plugin; los archivos adicionales (ver arriba); el tamaño real de
   `odc.mv.db`; el formato real de las fechas (se asume el patrón del código); que `last.modified ≤ last.checked`; y que
   12.2.2 escriba `nvd.api.last.*` en una primera actualización completa. Un fallo aquí se manifestaría como un
   **falso rechazo**.
6. **KEV** (`knownExploited`) se cuenta y se contrasta con el marcador, pero no se exige que tenga filas; su fallo (y el
   de los suppressions hospedados) no lo detecta este validador.
7. Requiere bash 4+, GNU `date`/`sha256sum`/`mktemp`/`find -print0`/`sort -z`, Java 11+ y los jars `h2-2.4.240` y
   `dependency-check-core-12.2.2` ya presentes en `~/.m2` (o rutas dadas con `--h2-jar` / `--core-jar`). No descarga
   nada. En CI esos jars no existen de antemano: los workflows los resuelven con `mvnw dependency:get` (sin dependencias
   transitivas) antes de ejecutar el validador.

## Lectura en solo lectura: qué se demostró y qué no

- Se trabaja **siempre sobre una copia** en un directorio temporal; el original solo se lee para calcular su SHA-256
  (antes, tras la copia y después: si la copia difiere del original o este cambia durante la validación, no se continúa).
- Las pruebas demuestran, **con bases sintéticas**, que el SHA-256 y la lista de archivos del directorio original no
  cambian tras validar. No está demostrado para una base real.
- `IFEXISTS=TRUE` evita que H2 cree una base vacía si la ruta es incorrecta.
- Las credenciales de la base las lee `NvdDbInspect` del `dependencycheck.properties` del propio jar de
  Dependency-Check; no se muestran ni se registran. La salida de `NvdDbInspect` se limita a ASCII imprimible.

## Integración en CI

**Fase 2 (bloquea, vigente):**
- `update-dependency-check-data.yml`: tras la actualización ejecuta `--mode create --valid-hours 4 --job-start-epoch <inicio capturado
  justo antes de update-only> --emit` con los mínimos `NVD_MIN_*`. Solo con código 0 (y `--emit` no vacío) escribe el marcador de
  13 claves; con cualquier otro resultado **el run falla antes de escribir el marcador y de guardar la caché**, y se conserva la
  anterior (el CI la sigue usando mientras tenga como máximo 7 días). Las ejecuciones desde una rama que no es `main` no guardan caché
  y se detienen si no hay una base previa que restaurar.
- `ci.yml`: en el job `dependency-check`, `--mode verify --valid-hours 4` con los mismos mínimos antes del análisis; con los códigos
  1, 2 y 3 el job **falla**. Exige el marcador de 13 claves (el de 5 claves se rechaza).
- Los mínimos (`NVD_MIN_VULNERABILITY=200000`, `NVD_MIN_CPE_ENTRY=215000`, `NVD_MIN_KNOWN_EXPLOITED=1400`) están definidos en el `env` de
  **ambos** workflows y deben coincidir; no hay una fuente única.
- `nvd-validator-tests.yml`: la suite en Linux cuando cambian los archivos del validador.
- Se muestra la salida del validador sin los prefijos `::error::`/`::warning::` y se emite **una** anotación por resultado: aviso
  informativo si lo aprueba, error en cualquier otro caso (y el paso falla). El resumen del run incluye el código, los mínimos y la
  salida completa.

**Fase 1 (observación, sustituida por la fase 2):** el validador solo informaba y las bases rechazadas se publicaban con un marcador
de 5 claves («SIN verificar»). Quedaron en `main` desde el PR #3 hasta el PR de la fase 2.

**Política `--valid-hours` y `-DnvdValidForHours`** (hallazgos de la lectura del bytecode de `NvdApiDataSource` 12.2.2; no se ha
ejecutado contra NVD): `processApi` omite la actualización solo si hay datos, existe `nvd.api.last.checked`, el valor es **mayor
que 0** y la última comprobación fue hace menos de esas horas; el valor por defecto de `dependencycheck.properties` es 4. Si no se
omite, pide a la API solo lo modificado desde `nvd.api.last.modified` (ventana de 120 días como máximo, páginas de 2000, 50 peticiones
cada 30 s con clave). KEV tiene su propia ventana (`kev.check.validforhours`, 24). Con 4 h, el run diario no se omite nunca; con 0 solo
cambiaría una reejecución manual poco después de una actualización correcta (haría 1 o 2 peticiones más). No hay medición real de
duración ni de consumo. Por eso se mantiene `--valid-hours 4` en la fase 2 y no se toca el plugin.

## Pruebas

```
bash scripts/ci/test/test-validate-nvd-database.sh            # suite completa
bash scripts/ci/test/test-validate-nvd-database.sh 'emit|temporales'   # solo algunas secciones
```

Secciones: `positivo`, `archivo`, `archivos-extra`, `internos`, `esquema`, `minimos`, `plugin-propios`, `entorno`,
`politica`, `parametros-prueba`, `integridad`, `orden`, `emit`, `temporales`, `file-only`, `verify`, `parcial`,
`ida-y-vuelta`. Crean bases H2
sintéticas (`NvdFixtureBuilder.java`, con el `data/initialize.sql` real del jar core) en un directorio temporal y
comprueban, para cada caso negativo, el código de salida **y el motivo** del rechazo. Las funciones de la suite activan
`NVD_VALIDATOR_TEST=1` (`val`, `raw`) o lo quitan (`valnt`, `rawnt`) para probar tanto el rechazo como el uso operativo.

Cubren, entre otros: archivo ausente, vacío, truncado, cabecera destruida, contenido aleatorio; sin `nvd.api.last.*`;
fechas ausentes, inválidas, futuras e incoherentes y sus límites exactos; `--valid-hours` ausente, inválido, 0 y > 0;
parámetros de pruebas sin `NVD_VALIDATOR_TEST=1`; fallos y salidas inválidas de `sha256sum` y `wc` (también en
`verify`) sin que se publique ningún marcador; el orden «marcador antes de H2» (con un Java inexistente: se rechaza
sin llegar a pedirlo); archivos adicionales (ocultos, `odc.trace.db`, directorios); marcador con claves ausentes,
duplicadas, desconocidas, CRLF, fechas imposibles o valores que no coinciden con la base; mínimos de registros (límite
exacto, un registro por debajo, tablas vacías, `create`/`verify`/uso operativo, argumentos inválidos, que un rechazo no publique
`--emit`); archivos propios de Dependency-Check frente a nombres parecidos, de otro tipo o enlaces simbólicos; base antigua con marcador
reciente (también falsificando el inicio); `--emit` previo, dentro de `--dir`, directorio, modo `verify`; y fallos
simulados de `mktemp`, `cp` y `mv` mediante «shims» antepuestos al `PATH`.

**No simulado:** un error de escritura real del temporal de `--emit` (disco lleno) y los enlaces simbólicos en sistemas
que no los permiten (se marcan como omitidas). **Linux:** la suite se ejecutó en un contenedor `eclipse-temurin:17-jdk-jammy`
(sin red): 317 pasadas, 0 fallidas, 1 omitida (disco lleno); en Windows con Git Bash: 310 pasadas, 6 omitidas (4 por enlaces
simbólicos que no se pueden crear, 1 por un sistema de archivos que no distingue mayúsculas y 1 por el disco lleno). Tampoco se ha probado con una base NVD real.
