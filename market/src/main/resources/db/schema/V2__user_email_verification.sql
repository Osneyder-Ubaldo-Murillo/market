-- ============================================================================
-- NexusMarket - V2: verificación del correo electrónico
-- ============================================================================
-- email_verified_at: fecha en que el usuario verificó su correo; NULL = sin verificar.
-- Los usuarios que ya existen se consideran verificados (se les asigna su fecha de creación): no se les bloquea el
-- acceso. Los registros nuevos nacen sin verificar y no pueden iniciar sesión hasta hacerlo.
-- No se borra ni se modifica ninguna otra columna ni dato.
-- ============================================================================

ALTER TABLE `users` ADD COLUMN `email_verified_at` datetime(6) NULL;

UPDATE `users` SET `email_verified_at` = `created_at` WHERE `email_verified_at` IS NULL;
