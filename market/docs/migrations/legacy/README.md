# Migraciones históricas (NO EJECUTAR)

`V1__schema.legacy.sql` es una copia de respaldo del esquema antiguo (`db/migration/V1__schema.sql`).
No se ejecuta con ningún perfil: Flyway usa `classpath:db/schema`, y `db/migration` se excluye del jar y de la imagen Docker.

`V2__seed.sql` (datos de ejemplo con cuentas de contraseña conocida) **no se versiona** a propósito, para no publicar credenciales.
