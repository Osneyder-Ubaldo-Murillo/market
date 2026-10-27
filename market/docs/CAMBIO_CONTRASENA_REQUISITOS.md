# Tarea separada: cambio y recuperación de contraseña (requisitos)

**Solo requisitos. No forma parte del bootstrap del administrador y no está implementada.** Hoy no existe
ningún endpoint para cambiar o restablecer contraseñas; el administrador inicial no podría rotar la suya.

## Alcance a diseñar y probar

1. **Cambio autenticado** (el usuario conoce su contraseña actual).
2. **Restablecimiento por un administrador** de la contraseña de otro usuario (no del propio, ni de otro
   administrador sin un procedimiento reforzado).
3. **Recuperación de administrador** cuando no queda ninguno utilizable (procedimiento independiente,
   autorizado, fuera de línea; ver `ADMIN_INICIAL_PROPUESTA.md`, sección 6).
4. Recuperación por correo ("olvidé mi contraseña") para compradores y vendedores: decidir si entra o no.

## Autenticación

- Cambio propio: exigir la contraseña actual (verificada con BCrypt) además del token; no basta el JWT.
- Los tokens emitidos antes del cambio deben dejar de ser válidos. El JWT actual es *stateless* y no tiene
  revocación: requiere definir una marca de versión de credenciales (probablemente un campo nuevo, es decir,
  cambio de esquema que necesita aprobación previa) o aceptar su expiración natural (86 400 s).
- Limitar intentos (por usuario e IP) y registrar los fallidos; respuestas que no revelen si el correo existe.

## Autorización

- Un usuario solo cambia su propia contraseña; solo ADMIN puede restablecer la de otros (y no la de otro
  ADMIN salvo procedimiento reforzado); SUPERVISOR no tiene escritura (regla vigente).
- Política vigente de contraseña: mínimo 8 (12 para ADMIN), máximo 72 bytes UTF-8 sin truncar, distinta de
  la actual; las mismas validaciones antes de escribir.
- Los restablecimientos entregan un secreto de un solo uso y vida corta; nunca una contraseña en claro por
  correo ni por la API.

## Auditoría

- Registrar cambio, restablecimiento, intentos fallidos y recuperación: actor, objetivo, hora, IP y resultado
  (sin contraseñas ni hashes). La auditoría actual se escribe después del commit y puede perderse si el
  proceso cae justo entonces (ver `TRANSACCIONES.md`); para estas operaciones sensibles valorar un *outbox*.

## Pruebas mínimas

Contraseña actual incorrecta → 401 sin cambios. Token de otro usuario → 403. Nueva contraseña débil, larga o
igual a la actual → 400 sin cambios. Tras el cambio, el login antiguo falla y el nuevo funciona; el hash
cambia y el resto de la fila no. El JWT previo deja de valer (según la decisión de revocación). Intentos
repetidos bloqueados. La contraseña no aparece en logs ni respuestas. Rollback si falla una auditoría
obligatoria. Concurrencia: dos cambios a la vez no dejan la credencial inconsistente.
