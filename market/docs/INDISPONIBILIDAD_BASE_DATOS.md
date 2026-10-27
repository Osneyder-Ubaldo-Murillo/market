# Indisponibilidad de MySQL: diagnóstico, respuesta 503 y `connection-timeout`

## 1. Reproducción (entorno aislado: MySQL 8.0 efímero + jar local, perfil `jpa,prod`)

Se detiene MySQL con la aplicación en marcha. Código **anterior** a este cambio, `connection-timeout` = 30 000 ms
(valor de `application-prod.properties`):

| Petición | Resultado |
|---|---|
| `GET /api/products` (público) | **500** `INTERNAL_ERROR` a los 30,1 s |
| `GET /api/products` con Bearer válido | 503 `SERVICE_UNAVAILABLE` a los 30,1 s (lo devuelve el filtro JWT) |
| `POST /api/auth/login` | **500** a los 30,1 s |
| `POST` de registro con datos inválidos | 400 en 0,1 s (no toca la base de datos) |
| Ruta denegada por seguridad | 401 en 0,1 s |
| 250 peticiones simultáneas a `/api/products` | 250 × 500, la más lenta 52 s |

## 2. Por qué 500 y por qué ~31 s

- **~31 s**: es el `connection-timeout` de Hikari (30 s) más la latencia normal. Con MySQL detenido, Hikari no puede
  crear conexiones y cada petición espera el tiempo completo antes de fallar.
- **500**: la cadena de causas medida es
  `JDBCConnectionException ← SQLTransientConnectionException ("Connection is not available, request timed out")
  ← CommunicationsException ← CJCommunicationsException ← ConnectException (Connection refused)`.
  Solo el filtro JWT sabía clasificar un fallo de infraestructura (503). En el resto de rutas, la excepción llegaba al
  manejador global, cuyo `@ExceptionHandler(Exception.class)` respondía **500** a todo lo no previsto (y además no
  registraba nada en el log).
- La ráfaga de 250 peticiones tarda 52 s porque el lanzador de `curl` necesita ~20 s para crearlas y cada una espera
  30 s; **no se demostró saturación de hilos de Tomcat** en este experimento (una ruta sin base de datos respondió 401
  en 0,1 s durante la ráfaga).

## 3. Respuesta 503 (implementada)

`GlobalExceptionHandler.handleUnexpected` responde `503 SERVICE_UNAVAILABLE` en el formato de la API
(`code`, `message`, `timestamp`) con la cabecera `Retry-After: 5` cuando `DatabaseUnavailability.isUnavailable`
reconoce un fallo de **conectividad** con la base de datos. El mensaje es genérico ("Servicio temporalmente no
disponible. Intente de nuevo más tarde."): ni hosts, ni JDBC URL, ni usuario, ni nombres de clase en la respuesta. En
el log se escribe una línea `WARN` solo con los **nombres de clase** de la cadena de causas (sin mensajes, que pueden
contener hosts o parámetros).

Clasificación deliberadamente estrecha:

| Es indisponibilidad (503) | NO lo es |
|---|---|
| `SQLTransientConnectionException` (Hikari: no hay conexión) | `BusinessException` y derivadas (404/400/409/403...) |
| `SQLNonTransientConnectionException`, `SQLRecoverableException` ("Communications link failure") | `IllegalArgumentException` y validación (400) |
| cualquier `SQLException` con SQLState clase `08` (incluye "Too many connections") | `NullPointerException`, `IllegalStateException`, `ClassCastException`, `ArithmeticException` (500) |
| `JDBCConnectionException` (Hibernate), `CannotGetJdbcConnectionException` (Spring) | SQL mal formado (42), integridad (23), datos demasiado largos (22) → 500 |
| `ConnectException`, `UnknownHostException`, `NoRouteToHostException`, `SocketTimeoutException` **solo si cuelgan de una `SQLException`** | deadlock y espera de bloqueo (409, ya existente); fallo de autenticación de MySQL (28000); `SQLTimeoutException` de una sentencia; una caída de otro servicio (pasarela, Mongo) |

`CannotCreateTransactionException` por sí sola no basta: se reconoce por su causa real.

El filtro JWT **no se modificó**: conserva su clasificador propio (más amplio, con sus pruebas) y su 503.

También se añadió el registro en el log de los errores 500 inesperados (con traza), que antes no dejaban rastro.

## 4. Resultado tras el cambio (mismo experimento)

Con `connection-timeout` = 30 000 ms (sin cambiar la configuración): `GET /api/products`, `POST /api/auth/login` y
las rutas con Bearer → **503** a los ~30 s; los 400 de validación y los 401/403 siguen inmediatos; 250 × 503. **Al
restaurar MySQL, la respuesta normal vuelve de inmediato** (`/api/products` 200 en 83 ms, con Bearer 200, login 200).
Las 252 líneas de log muestran la cadena de causas anterior.

## 5. `connection-timeout` de producción: 10 000 ms (aprobado y aplicado)

`application-prod.properties`: `spring.datasource.hikari.connection-timeout=10000` (antes 30 000). Es lo único que cambia en
la configuración.

**Verificación del valor efectivo (no solo el archivo):**
- `ProductionPoolConfigurationTest` carga `application.properties` + `application-prod.properties` reales con
  `DataSourceAutoConfiguration` y comprueba en el `HikariDataSource` resuelto: `connectionTimeout` = 10 000, tamaño del
  pool 20, `minimumIdle` 5, `connection-init-sql` = `SET SESSION innodb_lock_wait_timeout=10`, `validationTimeout` ≤
  `connectionTimeout`; y que un valor explícito sigue prevaleciendo. No abre conexiones.
- En el jar real, el log de Hikari (DEBUG) registra al iniciar el pool `connectionTimeout...10000`, `maximumPoolSize...20`,
  `validationTimeout...5000`.

**Caída de MySQL con el valor de producción (MySQL efímero con volumen nombrado propio, jar local):**

| Petición con MySQL detenido | Resultado |
|---|---|
| `GET /api/products` (público) | 503 a los 10,1 s |
| `GET /api/products` con Bearer válido | 503 a los 10,1 s |
| `POST /api/auth/login` | 503 a los 10,1 s |
| Registro con datos inválidos | 400 en 0,1 s |
| Ruta denegada por seguridad / ruta sin base de datos | 401 en 0,1 s |
| 250 peticiones simultáneas | 250 × 503, la más lenta 11,8 s (antes 52 s) |

Tras restaurar MySQL: `/api/products` 200 en 82 ms, con Bearer 200, login 200 (recuperación inmediata, sin reiniciar la
aplicación). (`/actuator/health` seguía en 503 en el experimento solo porque la URI de Mongo apuntaba a un puerto cerrado a
propósito.)

**¿Falla alguna petición legítima con 10 s? (MySQL 8 real, pool 20, `innodb_lock_wait_timeout`=10)**
`ProductionPoolLoadMySqlTest` (se omite sin `NEXUS_TEST_MYSQL_URL`):

| Escenario | 10 000 ms (producción) | 5 000 ms | 2 000 ms |
|---|---|---|---|
| 60 pedidos simultáneos sobre 30 de stock | 30 aceptados + 30 `InsufficientInventoryException`; 0 fallos de pool; latencia máx. 0,6–0,8 s (3 corridas) | igual | igual |
| 30 pedidos con las conexiones y bloqueos retenidos ~5 s (peor caso forzado, pool 20) | **30 de 30 correctos**; 0 fallos de pool; latencia máx. 5,3 s (3 corridas) | **10 fallos de pool** (20 correctos) | **10 fallos de pool** (20 correctos) |

Conclusión: con carga normal, incluso 2 s bastaría; con conexiones retenidas ~5 s, 10 s absorbe la espera (5,3 s) con
margen de ~4,7 s y 5 s o menos convierten 10 de 30 peticiones legítimas en 503. No hay evidencia de que 10 s provoque fallos
legítimos.

**Margen y riesgo restante:** una retención de conexiones mayor que 10 s no es posible por bloqueos (la espera de bloqueo
se corta a los 10 s con 409), pero sí por una sentencia lenta o una pasarela lenta dentro de una transacción
(`confirmPayment` cobra dentro de la transacción). Si la monitorización muestra `SQLTransientConnectionException` en
condiciones normales, el siguiente ajuste sería subir el pool (no el timeout) o sacar el cobro externo de la transacción.
En caída de MySQL, cada petición ocupa un hilo ~10 s: con mucho tráfico se acerca a los 200 hilos de Tomcat antes de lo
deseable; un cortacircuitos sería la mitigación completa (no incluido).

**Trampa de configuración**: la variable de entorno `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` hace fallar el arranque
("Failed to bind properties under 'spring.datasource.hikari.connection' to java.sql.Connection"), porque Spring la
interpreta como la propiedad anidada `connection.timeout`. Use `SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT` (o la propiedad).

## 6. Limitaciones

- Sin una prueba de saturación real de hilos de Tomcat (no demostrada).
- `/actuator/health` seguía en 503 a los 30 s tras restaurar MySQL en el experimento porque la URI de Mongo apuntaba a un
  puerto cerrado a propósito (la salud agrega Mongo); no está relacionado con el cambio.
- El tiempo de espera de una sentencia ya conectada (`SQLTimeoutException`) sigue siendo 500.
- La incidencia intermitente de un reinicio en el primer arranque del Compose no se ha vuelto a reproducir y no se
  atribuye a ninguna causa.
