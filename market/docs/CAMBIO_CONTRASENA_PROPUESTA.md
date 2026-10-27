# Propuesta: cambio (rotación) de la contraseña del administrador

**Estado: IMPLEMENTADA** con las decisiones definitivas (solo ADMIN, opción A `pwv`, 5 intentos/15 min en memoria por
instancia, `400 INVALID_CURRENT_PASSWORD`, recuperación solo documentada). Uso y recuperación:
`ROTACION_Y_RECUPERACION_ADMIN.md`. Complementa `CAMBIO_CONTRASENA_REQUISITOS.md`. El
`connection-timeout` de producción (10 000 ms) y el bootstrap no se tocan.

## 1. Lo que hay hoy (inspección)

- `user_credentials(user_id PK, password_hash varchar(100), created_at)`. Sin columna de versión ni de fecha de cambio.
  `CredentialStorePort` solo tiene `save` y `findByUserId`; el adaptador usa `merge`, y `created_at` es no actualizable.
- JWT (HS256, `java-jwt`): claims `iss`, `sub`=userId, `role`, `iat`, `exp` (24 h). `JwtAuthenticationFilter` valida firma,
  emisor y expiración, carga al usuario, exige `ACTIVE` y toma el rol **de la base de datos**. **Nada liga el token a la
  contraseña**: tras cambiarla, un token robado seguiría valiendo hasta 24 h. No se invalida solo.
- Identidad en el controlador: el principal es el `userId` del token (`AccessPolicy.currentUserId()`).
- Rutas: `denyAll` por defecto; `/api/auth/login` y `/register/buyer` públicas; `/api/auth/register/seller` solo ADMIN.
- Transacciones: `@Transactional` + `READ_COMMITTED` + `findForUpdate` (bloqueo de fila) ya existen y se pueden reutilizar.
- La política de contraseña de administrador (`AdminPasswordPolicy`: ≥12 caracteres, ≤72 bytes UTF-8, sin ejemplos ni correo)
  y el cifrado (`BcryptPasswordServicePort`) ya existen.

## 2. Flujo propuesto (cambio normal, con la contraseña actual)

`POST /api/auth/password` (cuerpo `{currentPassword, newPassword}`), **sin identificador de usuario**: la víctima es siempre
el principal del token. Ni la ruta ni el cuerpo aceptan un id (un campo `userId` extra se ignora), por lo que no hay
referencia directa manipulable.

1. Seguridad: `hasRole(ADMIN)`; usuario no autenticado → 401, no administrador → 403 (formato de la API).
2. Validación previa a escribir: `newPassword` con `AdminPasswordPolicy` (correo del propio administrador incluido) →
   400; distinta de la actual (se comprueba con `verify` contra el hash vigente) → 400.
3. Transacción (`READ_COMMITTED`): `SELECT … FOR UPDATE` de la fila de `user_credentials` del principal, `verify(currentPassword,
   hashActual)`; si falla → error genérico sin distinguir causas, y se contabiliza el intento fallido; si acierta →
   `UPDATE` del hash (hash nuevo con BCrypt; la contraseña en claro nunca se guarda ni se registra). Dos cambios
   simultáneos se serializan por el bloqueo: el segundo ve el hash nuevo y su `currentPassword` ya no coincide.
4. Respuesta: `200` con un **token nuevo** (la sesión actual continúa) y sin ningún dato secreto.
5. Auditoría (después del commit, mecanismo ya existente): `PASSWORD_CHANGE` (éxito) y `PASSWORD_CHANGE_FAILED`
   (intento fallido), con el id del usuario; nunca contraseñas ni hashes.

Atomicidad y errores de persistencia: un solo `UPDATE` bajo bloqueo; cualquier fallo revierte y la contraseña anterior sigue
vigente. Caída de MySQL → 503 por el manejador existente; fallos inesperados → 500 genérico. Sin éxito parcial.

## 3. Control de los JWT existentes (decisión principal)

Los tokens **no** se invalidan hoy. Opciones sin cambiar el esquema:

| Opción | Cómo | Pros | Contras |
|---|---|---|---|
| **A (recomendada)** — huella de credencial en el token | Al emitir, el token lleva el claim `pwv` = HMAC-SHA256(secreto JWT, hash BCrypt vigente) truncado. El filtro, que ya consulta al usuario, lee también el hash (`findByUserId`, lectura por PK) y rechaza (401) si `pwv` no coincide | Exacta; sin comparar relojes ni zonas horarias; invalida **cualquier** cambio de credencial futuro (también la recuperación fuera de línea); no expone el hash (HMAC) | +1 lectura por PK por petición autenticada; los tokens emitidos antes del despliegue se rechazan una vez (todos vuelven a iniciar sesión; no hay producción activa todavía) |
| B — fecha | Comparar `iat` con una marca de tiempo (`users.updated_at` o `user_credentials.created_at` reescrita) | Sin lectura extra si se usa `users.updated_at` | Mezcla relojes/zonas (`LocalDateTime` sin zona vs `iat` en UTC), granularidad de 1 s (los tokens del mismo segundo siguen valiendo) y `users.updated_at` cambia por otras causas |
| C — esperar a `exp` | no hacer nada | trivial | un token robado sigue valiendo hasta 24 h tras la rotación |

Regla de A: si el usuario **tiene** credencial, el token debe llevar `pwv` y coincidir; si no tiene fila de credenciales
(solo ocurre con usuarios creados fuera del registro, p. ej. los de las pruebas) se conserva el comportamiento actual.

## 4. Control de acceso

- Sin id en la ruta/cuerpo: ningún usuario puede apuntar a las credenciales de otro. Prueba: con un cuerpo que incluya el
  `userId` de otro administrador, solo cambia la del principal.
- No administradores (comprador, vendedor, operador, supervisor): 403. Sin token o token inválido/bloqueado: 401.
- Fuerza bruta de `currentPassword` con un token robado: contador de fallos por usuario en memoria (p. ej. 5 en 15 min →
  `429` durante el resto de la ventana, sin consultar el hash). **Limitación**: es por instancia y se pierde al reiniciar; una
  garantía distribuida exigiría estado compartido (esquema o Redis) y no se incluye.
- `ChangePasswordRequest.toString()` oculta ambos campos (los `record` imprimen sus valores) y no se registra el cuerpo.

## 5. Recuperación (separada; sin implementar)

Cuando **no** se tiene la contraseña actual: **no** habrá endpoint ni bypass. Solo un procedimiento operativo fuera de línea,
con acceso a la base de datos y a los secretos de despliegue (autoridad equivalente), documentado y con doble control:
generar el hash con la propia herramienta del proyecto y actualizar `user_credentials.password_hash` del administrador en
una transacción, tomando `GET_LOCK('nexusmarket:admin-bootstrap')`. Con la opción A, ese cambio **invalida automáticamente
todos los tokens** del administrador. Un administrador bloqueado se reactiva por separado. Un "olvidé mi contraseña" por correo
queda fuera.

## 6. Archivos que cambiarían

- `JwtServicePort` / `JwtJwtServicePort`: `issue(userId, role, credentialVersion)` y `getCredentialVersion(token)`.
- `JwtAuthenticationFilter`: comparación de `pwv` (misma clasificación de fallos de infraestructura → 503).
- `CredentialStorePort` + `CredentialPersistence`: `findForUpdate`/`replaceHash` (bloqueo de fila, sin `merge`).
- `AuthUseCases` / `AuthApplicationService`: `changePassword` transaccional; `login` emite el token con `pwv`.
- `AuthController` + `AuthRequests.ChangePasswordRequest`; `SecurityConfiguration` (regla `hasRole(ADMIN)` para la ruta).
- `OperationType` (`PASSWORD_CHANGE`, `PASSWORD_CHANGE_FAILED`), `GlobalExceptionHandler` (400/429 con código propio).
- Documentación: este archivo y `ADMIN_INICIAL_ACTIVACION.md` (paso de rotación).

Sin migraciones, sin columnas, sin tocar el bootstrap ni el `connection-timeout`.

## 7. Pruebas previstas

Contraseña actual incorrecta → error sin cambios; nueva débil/<12/>72 bytes/igual a la actual/con el correo → 400 sin cambios;
no administrador → 403; sin token → 401; cuerpo con `userId` ajeno → solo cambia el propio; cambio correcto → login
con la nueva (200) y con la antigua (401); **token anterior → 401** y token nuevo → 200; hash cambia y el resto de la fila
no; el hash no es la contraseña; contraseñas ni hashes en logs/excepciones/`toString`; 5 fallos → 429 y el correcto durante
el bloqueo también espera; dos cambios simultáneos (MySQL real) → exactamente uno; fallo inducido al guardar → rollback y
la contraseña anterior sigue válida; MySQL caído → 503; el bootstrap sigue sin cambiar nada; usuarios sin credencial siguen
funcionando.

## 8. Riesgos

- Un token robado **antes** del cambio deja de servir (A); uno robado después, no.
- +1 lectura por PK en cada petición autenticada (medible; el pool de 20 y el timeout de 10 s la absorben).
- El contador anti-fuerza-bruta no es distribuido.
- Si el administrador pierde la contraseña, solo existe el procedimiento operativo.

## 9. Decisiones que necesito

1. ¿Opción A (huella `pwv`) para invalidar tokens? ¿Aceptas que los tokens actuales dejen de valer al desplegar?
2. ¿Solo ADMIN ahora, o cambio para todos los roles (con política de 8 caracteres para no administradores)?
3. ¿Límite de intentos 5/15 min en memoria, aceptando que es por instancia?
4. ¿Código `400 INVALID_CURRENT_PASSWORD` para la contraseña actual incorrecta (frente a 401/403)?
5. ¿El procedimiento de recuperación fuera de línea queda solo documentado?
