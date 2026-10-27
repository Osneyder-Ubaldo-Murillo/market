# Registros huérfanos de registros incompletos

## Origen

Antes de la corrección, el registro de comprador/vendedor escribía en pasos separados, cada
uno con su propia transacción (usuario → perfil → credenciales), y un fallo en un paso
posterior (p. ej. `Data too long for column 'city'`) dejaba los pasos anteriores
confirmados. Aparecen así:

- **Usuario sin credenciales** (`users` sin fila en `user_credentials`): no puede iniciar
  sesión y su correo/documento quedan ocupados, por lo que no puede volver a registrarse.
- **Usuario BUYER sin perfil** (`users` sin fila en `buyers`), o **SELLER sin perfil**
  (`users` sin fila en `sellers`).

Con la corrección estos casos ya no se generan (el registro es todo o nada), pero las bases
que ejecutaron la versión anterior pueden contener huérfanos.

> **No se borra ni se altera nada automáticamente.** Ninguna migración ni arranque de la
> aplicación toca estos datos. Esta es una guía de revisión manual.

## 1. Identificar (solo lectura)

```sql
-- A. Usuarios sin credenciales (cualquier rol)
SELECT u.id, u.email, u.role, u.status, u.created_at
FROM users u
LEFT JOIN user_credentials c ON c.user_id = u.id
WHERE c.user_id IS NULL;

-- B. Compradores (rol BUYER) sin perfil de comprador
SELECT u.id, u.email, u.created_at
FROM users u
LEFT JOIN buyers b ON b.user_id = u.id
WHERE u.role = 'BUYER' AND b.buyer_id IS NULL;

-- C. Vendedores (rol SELLER) sin perfil de vendedor
SELECT u.id, u.email, u.created_at
FROM users u
LEFT JOIN sellers s ON s.user_id = u.id
WHERE u.role = 'SELLER' AND s.seller_id IS NULL;

-- D. Filas hijas sin usuario (no deberían existir)
SELECT b.buyer_id, b.user_id FROM buyers b LEFT JOIN users u ON u.id = b.user_id WHERE u.id IS NULL;
SELECT s.seller_id, s.user_id FROM sellers s LEFT JOIN users u ON u.id = s.user_id WHERE u.id IS NULL;
SELECT c.user_id FROM user_credentials c LEFT JOIN users u ON u.id = c.user_id WHERE u.id IS NULL;
```

Un resultado vacío en todas las consultas significa que no hay huérfanos. Los usuarios de
personal (ADMIN, SUPERVISOR, LOGISTICS_OPERATOR) creados fuera del registro público también
aparecerán en **A** si no tienen credenciales: revísalos por separado, no son necesariamente
un error de registro.

## 2. Revisar antes de actuar

Para cada fila de A/B/C:

1. Confirmar que el usuario no tiene actividad: ningún pedido, carrito, producto ni
   inventario asociado (`orders.buyer_id`, `carts.buyer_id`, `products.seller_id`, ...).
2. Comparar `created_at` con el momento del fallo registrado en los logs.
3. Decidir con el responsable del negocio: eliminar el huérfano (el usuario podrá volver a
   registrarse) o completar el registro manualmente.

## 3. Reparar de forma controlada

1. **Copia de seguridad** de la base y, preferiblemente, ensayar todo primero sobre una
   copia restaurada en un servidor desechable. Nunca sobre una base compartida sin
   autorización.
2. Ejecutar en una transacción explícita, con identificadores concretos (nunca un `DELETE`
   masivo por condición):

```sql
START TRANSACTION;

-- Ejemplo para un usuario huérfano ya revisado (sustituir el id):
DELETE FROM buyers WHERE user_id = '<id-del-usuario>';
DELETE FROM sellers WHERE user_id = '<id-del-usuario>';
DELETE FROM users WHERE id = '<id-del-usuario>' AND id NOT IN (SELECT user_id FROM user_credentials);

-- Verificar que afectó exactamente lo esperado (1 fila en users) y repetir A/B/C.
-- COMMIT;   -- solo si el resultado es el esperado
-- ROLLBACK; -- en caso contrario
```

3. Volver a ejecutar las consultas de la sección 1.

No se aportan credenciales ni se asigna contraseña a usuarios huérfanos: completar el
registro de alguien exige que esa persona defina su propia contraseña (registrarse de nuevo
una vez eliminado el huérfano).

## Verificación realizada

Las consultas A–D y la plantilla de reparación se probaron sobre una base MySQL 8.0
**efímera** (contenedor desechable, sin volúmenes), con un huérfano insertado a mano; no se
ejecutó nada contra bases reales ni compartidas.
