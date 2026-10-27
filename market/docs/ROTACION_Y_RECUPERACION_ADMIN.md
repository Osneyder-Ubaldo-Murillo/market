# Rotación y recuperación de la contraseña del administrador

Diseño y decisiones: `CAMBIO_CONTRASENA_PROPUESTA.md`. Alcance actual: **solo ADMIN**, y solo su **propia** contraseña.

## 1. Rotación (cambio normal, con la contraseña actual)

`POST /api/auth/password` con `Authorization: Bearer <token del administrador>` y cuerpo:

```json
{ "currentPassword": "<la actual>", "newPassword": "<la nueva>" }
```

- **No lleva identificador de usuario**: el destino es siempre el dueño del token. Un `userId` que llegue en el cuerpo se
  ignora (con prueba).
- **Nueva contraseña**: ≥ 12 caracteres, ≤ 72 bytes UTF-8 (nunca se trunca), sin valores de ejemplo (`password`, `admin`,
  `nexusmarket`, `123456`...), sin el correo ni su parte local, con ≥ 6 caracteres distintos y distinta de la actual.
- **Respuestas**:

| Código | Significado |
|---|---|
| 200 | Cambiada. Devuelve un **token nuevo** (`token`, `userId`, `role`); el anterior deja de valer |
| 400 `INVALID_CURRENT_PASSWORD` | La contraseña actual no es correcta (no se cambia nada) |
| 400 `PASSWORD_POLICY_VIOLATION` / `VALIDATION_ERROR` | La nueva no cumple la política o el cuerpo es inválido |
| 400 `NEW_PASSWORD_SAME_AS_CURRENT` | La nueva es igual a la actual |
| 401 | Sin token, token inválido, **token anterior a un cambio de contraseña** o usuario no activo |
| 403 | El usuario autenticado no es administrador |
| 429 `TOO_MANY_ATTEMPTS` | Se agotaron los 5 intentos de la ventana de 15 min; cabecera `Retry-After` en segundos |
| 503 `SERVICE_UNAVAILABLE` | MySQL no disponible; reintente (`Retry-After`) |

- **Cómo se invalidan las sesiones**: los tokens llevan el claim `pwv` (HMAC-SHA256, con el secreto JWT, del hash vigente).
  En cada petición el filtro compara la huella con la del hash actual y responde 401 si no coincide. Los tokens emitidos antes
  del despliegue de esta función no llevan `pwv`: un usuario **con credenciales** debe iniciar sesión de nuevo una vez.
- **Límite de intentos**: 5 por usuario en 15 minutos; el intento se reserva ANTES de verificar la contraseña, de modo que una
  ráfaga simultánea no lo elude, y durante el bloqueo se rechaza incluso la contraseña correcta. **El contador está en memoria:
  es local a cada instancia (con N instancias el máximo efectivo es N × 5) y se pierde al reiniciar.**
- **Concurrencia**: la fila de credenciales se bloquea (`SELECT … FOR UPDATE`); de dos cambios simultáneos con la misma
  contraseña actual solo uno tiene éxito (el otro recibe `INVALID_CURRENT_PASSWORD`).
- **Auditoría**: `PASSWORD_CHANGE` y `PASSWORD_CHANGE_FAILED` (id del usuario; nunca contraseñas, hashes ni tokens).

### Procedimiento recomendado tras la activación del administrador inicial

1. Active el bootstrap solo en el primer arranque (`ADMIN_INICIAL_ACTIVACION.md`) y desactívelo de inmediato.
2. Inicie sesión con la contraseña del archivo de secreto, **cambie la contraseña con este endpoint** y destruya el archivo.
3. Guarde la nueva contraseña en el gestor de secretos de la organización. No la escriba en `.env`, historiales del shell
   ni tickets; use un cliente que lea el cuerpo desde un archivo o la entrada estándar.

## 2. Recuperación (no se dispone de la contraseña actual) — procedimiento fuera de línea

**No existe endpoint de recuperación ni bypass de autenticación, y no se implementará uno público.** Perder la contraseña
exige un procedimiento operativo con la misma autoridad que la administración de la base de datos y de los secretos de
despliegue, ejecutado por **dos personas** (doble control) y registrado en el sistema de cambios de la organización:

1. **Copia de seguridad** de la base de datos y ensayo previo sobre una copia restaurada en un servidor desechable.
2. Genere el hash BCrypt de la contraseña nueva **sin escribirla en la línea de comandos** ni en el historial (p. ej. una
   herramienta interactiva que la lea de la entrada estándar). El hash debe tener el prefijo `$2a$` (o `$2b$`/`$2y$` según
   la herramienta; si la herramienta emite `$2y$`, sustitúyalo por `$2a$`) y 60 caracteres. Verifique el procedimiento en la
   copia antes de aplicarlo en producción: no se ha automatizado ni probado con una herramienta concreta en este repositorio.
   La contraseña nueva debe cumplir la política del administrador (sección 1).
3. En una sola transacción, tomando el mismo candado que el aprovisionamiento de administradores:

```sql
SELECT GET_LOCK('nexusmarket:admin-bootstrap', 30);       -- debe devolver 1
START TRANSACTION;
SELECT id FROM users WHERE email = '<correo del administrador>' AND role = 'ADMIN' FOR UPDATE;   -- 1 fila
UPDATE user_credentials SET password_hash = '<hash nuevo>' WHERE user_id = '<id obtenido arriba>';  -- 1 fila afectada
COMMIT;                                                   -- si no es exactamente 1 fila: ROLLBACK
SELECT RELEASE_LOCK('nexusmarket:admin-bootstrap');
```

4. **Efecto**: al cambiar el hash cambia la huella `pwv`, así que **todos los tokens previos de ese administrador dejan de
   valer** (401), sin reiniciar la aplicación.
5. Inicie sesión con la contraseña nueva e inmediatamente **cámbiela** con el endpoint (sección 1), de modo que la conozca
   solo el administrador. Destruya cualquier copia de la contraseña temporal.
6. Si el administrador estaba **bloqueado o inactivo**, el estado se restablece aparte (otro administrador o un
   procedimiento autorizado); este procedimiento no lo reactiva. El bootstrap tampoco lo recupera (decisión de diseño).
7. Registre el cambio (quién, cuándo, motivo, aprobador). No quedará evento `PASSWORD_CHANGE` en la auditoría de la
   aplicación porque el cambio no pasó por ella: la bitácora operativa es la fuente.

## 3. Límites

- Solo ADMIN. Compradores, vendedores, operadores y supervisores no tienen cambio de contraseña (decisión vigente).
- Contador de intentos local a cada instancia; sin estado compartido.
- Un token robado **después** del cambio sigue siendo válido hasta su expiración (24 h) o hasta el siguiente cambio.
- El cambio añade una lectura por clave primaria en cada petición autenticada (para comprobar la huella).
