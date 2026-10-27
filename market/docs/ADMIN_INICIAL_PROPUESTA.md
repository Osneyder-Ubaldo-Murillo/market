# Propuesta: creación controlada del primer administrador (rev. 2)

**Estado: IMPLEMENTADO y DESHABILITADO por defecto (diseño aprobado). No se ha activado ni se ha creado ningún
administrador en ninguna base de datos. Procedimiento de activación: `ADMIN_INICIAL_ACTIVACION.md`.** Las migraciones antiguas
(`V1__schema.sql`, `V2__seed.sql`) no se tocan; `V2` contiene un ADMIN con contraseña conocida y
**debe seguir inactivo** (fuera de `classpath:db/schema`).

## Decisiones ya aprobadas (incorporadas)

1. **Fail-fast**: con `NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED=true`, si falta un dato o es inválido, la
   aplicación no arranca. Deshabilitado por defecto.
2. **Contraseña**: mínimo **12** caracteres y máximo **72 bytes UTF-8**, sin truncar; el registro público
   mantiene el mínimo de 8. Se valida antes de escribir; nunca en logs ni excepciones.
3. **Administrador existente**: si existe **cualquier** usuario con rol `ADMIN` (activo, inactivo o
   bloqueado) el mecanismo no crea otro, no modifica, no reactiva y no cambia credenciales. La
   recuperación es un procedimiento aparte y autorizado.
4. **ID determinista**: aprobado como parte del diseño, **no como garantía suficiente** (ver sección 2).
5. **Cambio/recuperación de contraseña**: tarea separada (`docs/CAMBIO_CONTRASENA_REQUISITOS.md`).
6. **Datos del administrador**: nombre, documento y correo obligatorios por configuración cuando está
   habilitado; sin valores predeterminados ni ejemplos. La contraseña, preferentemente desde un archivo
   de secreto.

## Hechos del código actual en los que se apoya

- `users` (PK `id`; únicos `email` y `document_id`; `role`, `status` como texto **sin índice en `role`**)
  y `user_credentials` (PK `user_id`; `password_hash varchar(100)`). Sin FK entre ellas.
- No existe forma de crear ADMIN por la API; hoy solo las pruebas crean staff por el puerto de repositorio.
- Hash: `BcryptPasswordServicePort` (rechaza más de 72 bytes). Credenciales: `CredentialStorePort`.
- Transacciones y rollback: ya verificados (`@Transactional`, `JpaRepositorySupport`).
- `JpaRepositorySupport.persist` usa `merge`, que **sobrescribe** una fila con el mismo id.
- No hay ninguna API para cambiar contraseñas.

## 1. Entrada explícita (variables de entorno o secretos)

Todo apagado por defecto y sin valores por defecto.

| Variable | Contenido |
|---|---|
| `NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED` | `true` para activar; cualquier otro valor o ausente: no ocurre nada. |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_EMAIL` | correo (`Email.of`, ≤160) |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_FULL_NAME` | nombre completo (`FullName.of`) |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_DOCUMENT_ID` | documento (`DocumentId.of`) |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_PASSWORD_FILE` | ruta a un archivo con la contraseña (secreto Docker/Kubernetes). **Preferido.** |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_PASSWORD` | alternativa; solo si no hay `_FILE`. |

- La contraseña se lee en el momento de usarla (archivo con salto de línea final recortado, o variable) y
  **no** se enlaza como propiedad de Spring ni se conserva en un campo de un bean: así no aparece en
  `/actuator/env`, `/actuator/configprops` ni en el volcado del `Environment`. Tampoco en `toString`,
  logs ni mensajes de excepción (los mensajes nombran la variable, jamás el valor).
- Docker Compose: variables pasadas solo si el operador las define; `.env.example` con ellas vacías.

## 2. Exclusión de administradores duplicados (verificada con el aislamiento real de MySQL)

**Por qué el id determinista no basta.** Solo detiene a dos ejecuciones del *mismo* mecanismo que derivan el
mismo id. No impide dos administradores con ids distintos (otro procedimiento, SQL manual, una futura
versión del bootstrap), y una comprobación "¿hay algún ADMIN?" seguida de un insert separado es una carrera
*check-then-act*.

### Experimentos en MySQL 8.0 efímero (InnoDB), 8 sesiones simultáneas con ids distintos

Las sesiones esperan a una marca de tiempo común (arranque simultáneo) y ejecutan cada una su sentencia en
su propia transacción. Script reproducible: ensayos repetidos, contando administradores resultantes y
errores SQL.

| Mecanismo | Aislamiento | Ensayos con **más de 1 administrador** | Errores SQL | Conclusión |
|---|---|---|---|---|
| `SELECT count` + `INSERT` simple (patrón de la aplicación), 2 sesiones | REPEATABLE READ | **2 administradores** (E0b) | 0 | La carrera existe. |
| `INSERT … SELECT … WHERE NOT EXISTS (… role='ADMIN')` | **REPEATABLE READ** | **0 de 20** | **88** (deadlock 1213, “Deadlock found when trying to get lock”) | Seguro **solo por que InnoDB aborta a los perdedores por deadlock** (bloqueos *gap/next-key*). Hay que tratar 1213 como "otro intento concurrente" y no como fallo. |
| `INSERT … SELECT … WHERE NOT EXISTS (…)` | **READ COMMITTED** | **20 de 20** | 0 | **NO es seguro**: sin bloqueos de hueco, las 8 sesiones pasan el NOT EXISTS. |
| Igual que el anterior + un 8.º actor con `INSERT` simple (procedimiento externo) | REPEATABLE READ | **12 de 20** con más de 1 | 47 | El procedimiento externo no queda excluido (límite documentado). |
| Igual + procedimiento externo | READ COMMITTED | 20 de 20 | 0 | Idem. |
| `GET_LOCK('nexusmarket:admin-bootstrap')` antes de la transacción + la sentencia atómica | REPEATABLE READ | **0 de 20** | **0** | Seguro y sin deadlocks. |
| `GET_LOCK` + sentencia atómica | READ COMMITTED | **0 de 20** | **0** | Seguro también bajo READ COMMITTED. |
| Misma clave primaria determinista, correos y documentos distintos (E3) | cualquiera | 0 | clave duplicada en las demás | Una barrera adicional, independiente del aislamiento. |

**Hallazgo clave:** la sentencia `INSERT … SELECT … NOT EXISTS` **por sí sola no es una garantía**: depende de
que la transacción corra en REPEATABLE READ y de los deadlocks. La aplicación usa `READ_COMMITTED` en los
casos de uso de pedidos e inventario (ver `CONCURRENCIA_BLOQUEOS.md`); el bootstrap **no debe** heredar ese
aislamiento. La exclusión mutua fiable es el candado.

### Diseño resultante (por capas, sin cambiar el esquema)

1. **Candado de aplicación** `GET_LOCK('nexusmarket:admin-bootstrap', timeout)` tomado en una conexión
   dedicada **antes** de abrir la transacción y liberado después del commit (en MySQL). Garantía principal
   entre instancias: bajo REPEATABLE READ y READ COMMITTED, 0 de 20 ensayos con más de un administrador y
   0 errores. En H2 (pruebas) un candado de JVM; en este caso la concurrencia real se prueba con MySQL
   efímero.
2. **Sentencia atómica condicional** `INSERT INTO users … SELECT … WHERE NOT EXISTS (SELECT 1 FROM users
   WHERE role='ADMIN')`, dentro de la transacción, fijando explícitamente `Isolation.REPEATABLE_READ` (nunca
   READ_COMMITTED). Solo con 1 fila afectada se insertan las credenciales (misma transacción). Un error
   1213/40001 se interpreta como "hay un intento concurrente": se vuelve a comprobar y se sale sin error.
3. **Id determinista** (`UUID.nameUUIDFromBytes("nexusmarket:initial-admin")`) y los únicos de `email` y
   `document_id`: barreras adicionales contra dos ejecuciones del bootstrap, válidas incluso sin el candado.
4. **Inserción exclusiva, nunca `merge`**: no puede sobrescribir un administrador existente.
5. **Todo en una transacción**: usuario + credenciales (rollback ya verificado).

### Límites que quedan (honestos)

- El bootstrap garantiza: (a) no crea un administrador si existe uno **confirmado** antes de su comprobación;
  (b) dos bootstraps simultáneos producen **uno**; (c) nunca modifica uno existente.
- **No puede impedir** que *otro procedimiento* inserte un ADMIN con un `INSERT` simple a la vez: medido, 12
  de 20 ensayos terminan con dos administradores bajo REPEATABLE READ y 20 de 20 bajo READ COMMITTED. Si la
  creación de administradores se abre a otras vías (endpoint, SQL manual), **todas** deben usar el mismo
  servicio de aprovisionamiento (mismo candado + misma sentencia), y el procedimiento manual documentado debe
  tomar el mismo candado (`SELECT GET_LOCK(...)`).
- Una garantía absoluta ("a lo sumo un ADMIN") exigiría una restricción de esquema (columna generada con
  índice único), no autorizada por ahora y contraria a permitir varios administradores.
- `GET_LOCK` es específico de MySQL, es por conexión y caduca con ella: requiere mantener la conexión
  dedicada mientras dura la operación; un *timeout* de espera debe terminar con un mensaje claro (sin
  crear nada).
- **Pool de conexiones**: el candado retiene una conexión y la transacción usa otra; hace falta un pool Hikari con
  al menos 2 conexiones (validado al arrancar, solo con el mecanismo habilitado; ver
  `ADMIN_INICIAL_ACTIVACION.md`, "Requisito del pool de conexiones").
- La búsqueda por `role` (sin índice) recorre `users` y puede bloquear inserciones en `users` brevemente
  (milisegundos, una sola vez en el arranque).

## 3. Contraseña segura y confidencial

- Política del arranque: ≥12 caracteres, ≤72 bytes UTF-8 (se rechaza, no se trunca); no contener el correo
  ni su parte local; rechazar valores de ejemplo (`password`, `admin`, `changeme`, `nexusmarket`,
  `123456`…) y secretos de baja variedad (mismo criterio que `JWT_SECRET`).
- Se cifra con `BcryptPasswordServicePort`. Se valida y se cifra **antes** de abrir la transacción.
- Único log de éxito: `Administrador inicial creado (id=<uuid>)` más un `WARN` recomendando retirar las
  variables `NEXUSMARKET_BOOTSTRAP_ADMIN_*` del entorno.

## 4. Entidades reales

`User` (`ADMIN`, `ACTIVE`, id determinista) y `user_credentials` con el hash, con las mismas entidades y
tablas que usa el registro. Sin SQL fuera del modelo, sin filas en migraciones.

## 5. Protección contra activación accidental

- Apagado por defecto: sin `ENABLED=true` no se instancia ningún componente.
- Todo o nada: con `ENABLED=true`, cualquier dato ausente o inválido impide el arranque (sin valores en el
  mensaje).
- Requiere persistencia real (`@Profile("jpa")`); `ENABLED=true` sin perfil `jpa` → el arranque falla.
- Sin camino "de desarrollo" más débil: misma política y variables en dev y prod; las pruebas generan
  contraseñas aleatorias.

## 6. Cuando ya existe un administrador

Cualquier usuario `ADMIN` (incluso bloqueado/inactivo) → no se crea, modifica, reactiva ni cambia
credenciales, aunque las variables traigan otros datos. `INFO`/`WARN` sin secretos. Si el único
administrador está bloqueado, la recuperación es un procedimiento aparte y autorizado. Choque de
correo/documento con un usuario **no** administrador → el arranque falla con mensaje claro, sin tocar a
ese usuario (la sentencia atómica inserta 0 filas por NOT EXISTS **o** falla por único; en ambos casos
nada cambia y se distingue en el mensaje).

## 7. Pruebas implementadas (las 15 previstas)

H2 en memoria para flujo y confidencialidad; **MySQL 8.0 efímero real** (solo si `NEXUS_TEST_MYSQL_URL` está
definida) para concurrencia, candado, rollback y coexistencia. Clases: `AdminPasswordPolicyTest`,
`BootstrapAdminSettingsTest`, `BootstrapAdminStartupTest`, `InitialAdminBootstrapIntegrationTest` (H2),
`InitialAdminBootstrapMySqlTest` (MySQL), `MigrationLocationTest`.

| # | Prueba prevista | Dónde |
|---|---|---|
| 1 | Desactivado por defecto: 0 administradores; `false`, `yes`, `ture` tampoco activan | `disabledByDefault_createsNoAdministrator`, `enabledFlagFalseOrMisspelled_...`, `BootstrapAdminStartupTest`, `BootstrapAdminSettingsTest.isDisabledByDefault...` |
| 2 | Credenciales ausentes: no arranca, 0 filas, el mensaje nombra la variable y no contiene valores | `BootstrapAdminStartupTest.enabledWithMissingData...`, `invalidConfiguration_preventsStartup_andCreatesNothing`, `BootstrapAdminSettingsTest.missingEverything...` |
| 3 | Credenciales inválidas (correo, contraseña corta / >72 bytes / débil / con el correo, documento, nombre) | `AdminPasswordPolicyTest`, `BootstrapAdminSettingsTest.invalidValues...`, `passwordPolicyViolations...` |
| 4 | Creación correcta: 1 ADMIN/ACTIVE con id determinista, hash que verifica y no es la contraseña, login con rol ADMIN | `enabledWithValidConfiguration_createsTheAdministrator...` |
| 5 | Segunda ejecución: ninguna fila cambia aunque las variables traigan otra contraseña, correo y documento | `secondRun_doesNotChange...` |
| 6 | Dos administradores preexistentes con ids distintos: no se crea otro y ambos quedan idénticos | `twoExistingAdministratorsWithDifferentIds_...` |
| 7 | Administrador bloqueado o inactivo preexistente: cuenta, no se reactiva ni se reemplaza; correo/documento de un no administrador: falla sin tocarlo | `aBlockedOrInactiveAdministrator_...`, `emailOrDocumentOfANonAdministrator_...` |
| 8 | Instancias simultáneas, misma configuración: exactamente 1 (hilos y **dos aplicaciones completas** arrancando a la vez) | `concurrentInstancesWithTheSameConfiguration_...`, `twoFullApplicationInstancesStartingAtOnce_...` (MySQL) |
| 9 | Configuraciones distintas: exactamente 1; protocolo con ids aleatorios (sin id determinista) bajo REPEATABLE READ y READ COMMITTED con candado: 1; y coexistencia con pedidos, inventario y altas en `READ_COMMITTED` | `concurrentInstancesWithDifferentConfigurations_...`, `theProtocolWithTheLock_isSafeWithDistinctIds_...`, `theAtomicStatementAlone...`, `bootstrapCoexistsWithOrderInventoryAndRegistrationUseCases_...` (MySQL) |
| 10 | Procedimiento externo con `INSERT` simple: el bootstrap nunca crea más de uno; se mide y se documenta el límite | `anExternalPlainInsert_isNotExcluded_...` (MySQL) |
| 11 | Rollback: un fallo al guardar credenciales no deja usuario (H2 y MySQL/InnoDB) y libera el candado | `failureWhileSavingCredentials_rollsBackTheUser` (H2), `..._rollsBackTheUserInInnoDb_andReleasesTheLock` (MySQL) |
| 12 | Confidencialidad: ni contraseña ni hash en el log ni en las excepciones; el componente de arranque no conserva textos; `env`/`configprops` no se exponen | `enabledWithValidConfiguration_...(CapturedOutput)`, `invalidConfiguration_...` |
| 13 | Perfil `jpa` ausente con `ENABLED=true`: no arranca | `BootstrapAdminStartupTest.enabledWithoutThePersistenceProfile...` |
| 14 | Migraciones sin administrador (`classpath:db/schema` sin `INSERT`, `'ADMIN'`, hashes) | `MigrationLocationTest` |
| 15 | Lectura desde archivo (salto de línea final, precedencia sobre la variable, archivo ilegible o vacío) | `BootstrapAdminSettingsTest.readsThePasswordFromAFile...`, `theFileHasPrecedence...` |

Además: el candado se libera aunque la acción falle (`theLockIsReleasedEvenWhenTheProtectedActionFails`) y si no se
puede obtener no se crea nada ni se libera el candado ajeno (`ifTheLockCannotBeObtained_...`).

## 8. Ausencia de contraseñas por defecto o semillas

Ninguna migración nueva; ningún `INSERT` de usuarios en migraciones; ninguna contraseña en
`application*.properties`, Dockerfile, Compose ni `.env.example` (solo variables vacías).

## Estado de las decisiones

- Aprobado e implementado: candado `GET_LOCK` + sentencia atómica en REPEATABLE READ + id determinista +
  inserción exclusiva, con el límite documentado frente a procedimientos externos.
- No autorizado (sigue sin hacerse): restricción de esquema para limitar el número de administradores,
  migraciones, índices o semillas; cambio de contraseña (tarea aparte); recuperación de un administrador
  bloqueado (procedimiento aparte).
