# Activación controlada del administrador inicial

**Estado: implementado y DESHABILITADO por defecto.** Nada de lo descrito aquí se ha ejecutado contra ninguna
base de datos real: este documento es el procedimiento para una activación posterior, deliberada y revisada.
Diseño y justificación: `ADMIN_INICIAL_PROPUESTA.md`.

## Qué hace

- Con `NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED=true`, en el arranque (tras Flyway y antes de aceptar peticiones) crea
  **un** usuario `ADMIN` activo con sus credenciales (hash BCrypt) en una sola transacción.
- Si ya existe **cualquier** usuario con rol `ADMIN` (activo, inactivo o bloqueado): no crea otro, no modifica,
  no reactiva y no cambia credenciales, aunque las variables traigan otros valores.
- Si el correo o el documento pertenecen a un usuario que **no** es administrador: el arranque falla y ese
  usuario no se toca.
- Con `ENABLED=true`, si falta un dato o es inválido, o falta el perfil `jpa`, la aplicación **no arranca**
  (mensaje con los nombres de las variables, nunca los valores).
- Sin `ENABLED=true` (ausente, `false`, `yes`, un error de escritura...) no se carga ningún componente.

## Variables

| Variable | Obligatoria con ENABLED=true | Contenido |
|---|---|---|
| `NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED` | — | exactamente `true` (sin distinguir mayúsculas) |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_EMAIL` | sí | correo válido, ≤160 caracteres |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_FULL_NAME` | sí | 2–100 caracteres |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_DOCUMENT_ID` | sí | 4–20 caracteres alfanuméricos o guiones |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_PASSWORD_FILE` | una de las dos | ruta a un archivo con la contraseña (**preferida**) |
| `NEXUSMARKET_BOOTSTRAP_ADMIN_PASSWORD` | una de las dos | contraseña directa; solo si no hay `_PASSWORD_FILE` |

- **Precedencia**: si `_PASSWORD_FILE` está definida se usa **solo** el archivo (si no se puede leer, falla; no
  se recurre a la variable directa). Del archivo se recortan los saltos de línea finales; los espacios no.
- **Política de la contraseña**: mínimo 12 caracteres y máximo 72 bytes UTF-8 (se rechaza, nunca se trunca); sin
  valores de ejemplo (`password`, `admin`, `nexusmarket`, `123456`, `qwerty`...), sin el correo o su parte
  local, y con al menos 6 caracteres distintos. El registro público mantiene su mínimo de 8.

## Requisito del pool de conexiones

El aprovisionamiento necesita **dos conexiones simultáneas**: una dedicada al candado `GET_LOCK` (el candado es de
la conexión) y otra para la transacción de creación. Por eso, con el mecanismo **habilitado**:

- `spring.datasource.hikari.maximum-pool-size` debe ser **>= 2** (producción usa 20; Hikari usa 10 si no se define).
  Si el pool efectivo permite menos, el arranque se aborta enseguida (antes de conectar con la base de datos y de
  ejecutar Flyway) con: *"El administrador inicial necesita al menos 2 conexiones simultáneas del pool ... pero
  spring.datasource.hikari.maximum-pool-size vale N ..."*. Sin esta validación el fallo ocurría a los ~30 s, con un
  mensaje de Hikari.
- Con el mecanismo **deshabilitado** no se aplica ninguna validación específica: un pool de tamaño 1 arranca como
  siempre.
- **Cómo se valida (dos capas):** (1) al arrancar, antes de crear ningún bean, se lee la configuración efectiva
  con el `Binder` de Spring Boot (variables de entorno, argumentos, perfiles, otras grafías de la propiedad) sin
  abrir conexiones; (2) antes de tomar ninguna conexión para el candado, se consulta el tamaño máximo del
  `HikariDataSource` real (un getter de configuración: no inicializa el pool).
- **Valores raros:** propiedad ausente = 10 (acepta); `0`, negativos o no numéricos = se rechazan de forma explícita
  (no se sustituyen en silencio); marcador sin resolver = se rechaza.
- **Limitaciones técnicas:** solo se sabe leer el tamaño de un pool **HikariCP**. Si se configura otro pool
  (`spring.datasource.type` distinto de Hikari), la capa 1 no puede juzgarlo y la capa 2 **rechaza** el
  aprovisionamiento ("No se puede verificar el tamaño del pool"), en lugar de suponer la capacidad. Un pool de
  tamaño >= 2 pero con las conexiones ya agotadas por otras peticiones no se detecta aquí: en ese caso la espera
  de conexión de Hikari (`connection-timeout`, 30 s) acaba con un fallo limpio sin crear nada y sin dejar el
  candado tomado.

## Pasos seguros para activarlo manualmente (primer arranque)

1. **Base de datos nueva o ya migrada** con el procedimiento de `migrations/PROCEDIMIENTO_INSTALACIONES.md`.
   Confirme que no hay administradores: `SELECT COUNT(*) FROM users WHERE role = 'ADMIN';` debe dar 0 (si da más
   de 0, el mecanismo no hará nada, por diseño).
2. **Genere una contraseña** larga y aleatoria y guárdela solo en un archivo de secreto con permisos
   restringidos, p. ej. `openssl rand -base64 36 > admin_password` (un secreto de Docker/Kubernetes en
   producción). No la escriba en `.env`, en el historial del shell ni en el repositorio.
3. **Defina las variables** (el correo, nombre y documento del responsable): en `.env`
   `NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED=true`, `..._EMAIL`, `..._FULL_NAME`, `..._DOCUMENT_ID` y
   `..._PASSWORD_FILE=/run/secrets/admin_password` (monte el secreto en esa ruta).
4. **Arranque** con `SPRING_PROFILES_ACTIVE=jpa,prod` y compruebe el log: `Administrador inicial creado (id=...)`.
   El log no contiene la contraseña ni su hash.
5. **Desactive de inmediato**: ponga `NEXUSMARKET_BOOTSTRAP_ADMIN_ENABLED=false` (o elimine las variables
   `NEXUSMARKET_BOOTSTRAP_ADMIN_*`), destruya el archivo de secreto y reinicie. Repetir el arranque con el
   mecanismo habilitado no cambia nada, pero no hay motivo para dejarlo activo.
6. Inicie sesión (`POST /api/auth/login`) y **cambie la contraseña de inmediato** con `POST /api/auth/password`
   (`ROTACION_Y_RECUPERACION_ADMIN.md`); después destruya el archivo de secreto.

## Solución de problemas

- *La aplicación no arranca y el mensaje nombra una variable*: falta o es inválida; corrija esa variable.
- *"...pertenecen a un usuario que no es administrador"*: el correo o documento ya están registrados por otra
  persona; elija otros datos. Nada se modificó.
- *"No se obtuvo el candado... (otra instancia lo mantiene)"*: otra instancia está aprovisionando; espere y
  reintente. No se creó nada.
- *"Ya existe un administrador"* con ENABLED=true: comportamiento normal en arranques posteriores.
- *El único administrador está bloqueado o inactivo*: este mecanismo **no** lo recupera ni lo reactiva (decisión
  de diseño). Se requiere un procedimiento de recuperación independiente y autorizado (no implementado).

## Límites conocidos

- La exclusión entre instancias usa `GET_LOCK` de MySQL (por conexión); en H2 (pruebas) un candado de JVM.
- **Otro procedimiento que inserte un ADMIN con un `INSERT` simple, sin usar este servicio ni el candado
  `nexusmarket:admin-bootstrap`, no queda excluido** (en SQL puro se midió 12 de 20 ensayos con dos
  administradores). Cualquier futura vía de creación de administradores debe pasar por
  `InitialAdminService` o tomar el mismo candado y usar la misma sentencia condicional.
- Un procedimiento manual con SQL debe hacer: `SELECT GET_LOCK('nexusmarket:admin-bootstrap', 30);`, comprobar que
  no existe ningún `ADMIN`, insertar usuario y credenciales en la misma transacción, y
  `SELECT RELEASE_LOCK('nexusmarket:admin-bootstrap');`.
- No hay restricción de esquema que impida varios administradores (no autorizada ni deseada por ahora).
- La rotación de contraseña y la recuperación fuera de línea están en `ROTACION_Y_RECUPERACION_ADMIN.md`.
