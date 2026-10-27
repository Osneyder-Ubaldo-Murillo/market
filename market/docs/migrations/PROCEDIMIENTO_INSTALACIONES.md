# Procedimiento de migración de esquema por tipo de instalación

Configuración de producción (`application-prod.properties`): Flyway activo,
`locations=classpath:db/schema` (solo `V1__initial_schema.sql`), `baseline-on-migrate=false`,
`ddl-auto=validate`. Las migraciones antiguas (`db/migration/V1__schema.sql`,
`V2__seed.sql`) siguen en el repositorio, intactas y **fuera de la ubicación activa**.

> **Incertidumbre explícita.** No hay evidencia de si alguna base (propia, de un compañero
> o de un servidor) aplicó alguna vez una versión de `V1__schema.sql` o `V2__seed.sql`. Este
> documento no asume ni que sí ni que no. Antes de tocar cualquier base que no sea nueva,
> hay que averiguarlo inspeccionándola.

Reglas para todos los casos:

- No usar `baseline`, `repair` ni editar `flyway_schema_history` o checksums para "hacer
  arrancar" la aplicación. Si hay que usarlos, es una decisión manual, con copia de
  seguridad y revisión, y fuera de este procedimiento automático.
- Hacer **copia de seguridad** antes de cualquier operación sobre una base con datos.
- Probar primero sobre una **copia** restaurada en un servidor desechable, nunca sobre la
  base real ni compartida.

## Caso 1. Base vacía (instalación nueva)

1. Crear la base y un usuario de aplicación propio con privilegios sobre esa base (no `root`).
2. Definir `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
   `SPRING_DATASOURCE_PASSWORD`, `SPRING_MONGODB_URI` y `JWT_SECRET`.
3. Arrancar con `SPRING_PROFILES_ACTIVE=jpa,prod`. Flyway aplica `V1 - initial schema` y
   Hibernate valida el esquema.

Resultado verificado (MySQL 8.0 efímero): 20 tablas + `flyway_schema_history`, 0 usuarios;
un segundo arranque informa "up to date".

No se crea ningún usuario ni administrador: el administrador inicial es un paso posterior
y separado, pendiente de aprobación.

## Caso 2. Base preexistente con tablas y **sin** historial Flyway

Flyway **se niega a migrar** ("Found non-empty schema(s) ... but no schema history table") y
la aplicación no arranca, sin modificar la base. Verificado.

Procedimiento seguro:

1. **No** activar `baseline-on-migrate`.
2. Copia de seguridad; restaurarla en una instancia desechable.
3. Averiguar qué contiene:
   - ¿Las tablas son las 20 del modelo JPA (p. ej. creadas por Hibernate con `ddl-auto=update`)?
     Compararlas con `docs/migrations/reference/V1__schema.reference.sql` (tipos, longitudes,
     nulabilidad, claves, únicos).
   - ¿Hay tablas del diseño antiguo (`inventory`, `shipping`, `payments`, `buyer_addresses`...)
     o columnas como `role_code`? Entonces la base proviene de las migraciones antiguas o de un
     diseño anterior y **no** es compatible: requiere una migración de datos específica,
     que no existe todavía.
   - ¿Hay filas de `V2` (usuarios `admin@nexusmarket.com`, etc.) con contraseña conocida?
     Deben revisarse y eliminarse o desactivarse antes de exponer la base.
4. Solo si el esquema coincide exactamente con el de referencia, y por decisión explícita
   del responsable, se puede registrar el baseline **a mano** (versión 1) en la copia
   primero, y después en la base real. Es un paso manual, no automático.

## Caso 3. Base con historial Flyway antiguo

Esperable si alguna vez se aplicó `V1__schema.sql`/`V2__seed.sql` (hipótesis sin
confirmar). Con la configuración actual la aplicación **falla al arrancar** con
`Migration checksum mismatch for migration version 1` y no modifica la base. Verificado con
un historial simulado (`1 - schema`) en una base efímera.

Procedimiento seguro:

1. No ejecutar `repair` ni cambiar checksums.
2. Copia de seguridad; restaurar en una instancia desechable.
3. Inspeccionar `flyway_schema_history` (`success`, versiones aplicadas) y las tablas
   reales. Recordar que un `V1` antiguo fallido pudo dejar la base **a medias** (el DDL de
   MySQL no es transaccional).
4. Decidir, con evidencia, entre:
   - migrar los datos útiles a una base nueva creada por el Caso 1 (recomendado si el diseño
     antiguo difiere del modelo JPA, que es lo observado en los archivos actuales); o
   - una migración de transición escrita para ese estado concreto, revisada y probada sobre
     la copia.
5. Revisar y neutralizar cualquier usuario sembrado por `V2`.

## Desarrollo

El perfil por defecto usa H2 en memoria y Flyway desactivado. Ver `docs/ENTORNOS.md`.
