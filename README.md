# NexusMarket

**NexusMarket** es una plataforma digital centralizada que actúa como intermediario comercial entre compradores y vendedores. El sistema administra integralmente la operación: desde el registro de usuarios y la publicación de productos, hasta la logística, facturación y posventa.

Este proyecto está construido siguiendo los principios de **Domain-Driven Design (DDD)** y **Arquitectura Hexagonal (Puertos y Adaptadores)** para garantizar un dominio de negocio aislado, mantenible y completamente independiente de frameworks y tecnologías externas.

---

## Estado actual

Proyecto **académico y funcional**. Está implementado el núcleo de dominio, la capa de aplicación, la API REST con
seguridad JWT, la persistencia (MySQL con JPA/Flyway y auditoría en MongoDB) y el despliegue con Docker. Las pruebas se
ejecutan con `./mvnw test` (ver la sección de pruebas); el número de pruebas cambia con el código, así que no se fija aquí.

| Área | Estado |
|---|---|
| Dominio (agregados, value objects, specifications, 9 servicios, 19 puertos de salida) | Implementado |
| Capa de aplicación (11 casos de uso) y API REST (55 endpoints, validación y errores uniformes) | Implementado |
| Autenticación JWT (HS256), BCrypt, roles, propiedad del recurso, límite de intentos de login | Implementado |
| Persistencia MySQL (repositorios JPA), esquema Flyway, bloqueos pesimistas de inventario, pedidos, devoluciones y reembolsos | Implementado |
| Auditoría en MongoDB (perfil `prod`) o en log (desarrollo) | Implementado |
| **Pagos** | **Simulados** (no cobran). Bloqueados con el perfil `prod` salvo autorización expresa |
| **Envíos** | **Simulados** (número de seguimiento ficticio) |
| **Notificaciones** | **Simuladas**: se escriben en el log (con el destinatario enmascarado) |
| Integraciones reales (pasarela de pago, correo, SMS, transportadora) | Pendiente; los puertos y adaptadores son intercambiables |

### Limitaciones conocidas

- El identificador de transacción del cobro se guarda en el pedido (`orders.payment_transaction_id`, migración `V3`) y lo usan
  los reembolsos; los pedidos anteriores a `V3` usan una referencia derivada (`CHARGE-<pedido>`, auditada; solo válida con el simulador). Ver `market/docs/PAGOS_IDEMPOTENCIA.md`.
- Hay dos caminos de despacho (`/api/orders/{id}/dispatch` y `/api/shippings/{id}/...`); ambos dejan pedido y envío coherentes
  (un pedido tiene un solo envío; despachar el envío también despacha el pedido).
- **Cancelar un pedido ya pagado** reembolsa el pago y repone el stock; si la pasarela falla, no se cancela nada.
- **Devoluciones:** un pedido admite una devolución; los productos deben ser del pedido y lo devuelto (sumando líneas repetidas) no
  puede superar lo comprado. El carrito admite como máximo 1000 unidades por producto.
- El límite de intentos de login es en memoria y por instancia.
- Listados paginados (`?page=0&size=20`, máximo 100, total en la cabecera `X-Total-Count`): catálogo público
  (`GET /api/products`, con búsqueda por nombre `?q=`), pedidos (`GET /api/buyers/{id}/orders`) y facturas
  (`GET /api/buyers/{id}/invoices`) del comprador.
- Recuperación de contraseña (`POST /api/auth/password/forgot` y `/reset`): el código es un JWT de 30 minutos y un solo uso,
  entregado por `NotificationPort`. **Con el adaptador de log, el código queda escrito en el log**; sustitúyalo por un canal
  real antes de usar la aplicación con usuarios reales. No aplica al ADMIN (ver `market/docs/ROTACION_Y_RECUPERACION_ADMIN.md`).
- **Bloqueo por intentos fallidos:** 3 inicios de sesión fallidos consecutivos bloquean la cuenta (`BLOCKED`); solo un ADMIN la reactiva
  (`PATCH /api/users/{id}/activate`). El ADMIN no se bloquea por este camino. Quien conoce un email puede bloquear esa cuenta
  fallando a propósito (riesgo asumido; el límite por IP lo frena).
- **Verificación de correo:** las cuentas de registro público (comprador y vendedor) no pueden iniciar sesión hasta verificar el
  correo (`POST /api/auth/email/verify`; reenvío en `/api/auth/email/resend`). Requiere la migración `V2` (`users.email_verified_at`);
  los usuarios existentes quedan verificados. Igual que la recuperación, el código se escribe en el log con el adaptador actual.
- Aún no hay logout ni refresh de token.
- Las pruebas de MySQL real y de concurrencia solo se ejecutan si se define `NEXUS_TEST_MYSQL_URL` (el CI lo hace).

---

## Arquitectura del Proyecto

Arquitectura hexagonal (puertos y adaptadores) con DDD:

```
src/main/java/nexus/market/
├── domain/            # Núcleo del negocio: sin Spring, JPA ni MongoDB
│   ├── models/        # Agregados y entidades (User, Order, Product, Refund...)
│   ├── valueobjects/  # Objetos inmutables y catálogos de negocio (Email, Money, SystemRole...)
│   ├── enums/         # Enums técnicos
│   ├── specifications/# Reglas de negocio reutilizables
│   ├── services/      # 9 servicios de dominio
│   ├── ports/in|out/  # Casos de uso (entrada) y 19 puertos de salida
│   └── exceptions/
├── application/       # Casos de uso transaccionales (@Service/@Transactional de Spring), AccessPolicy (propiedad del
│                      # recurso), limitadores de intentos y notificador
├── adapters/
│   ├── in/rest/       # Controladores, DTO con Bean Validation, manejo global de errores, filtro JWT (security/)
│   └── out/           # persistence/mysql (JPA), persistence/mongodb, payment, shipping, notification,
│                      # audit, security (JWT, BCrypt) y config
└── infrastructure/    # SecurityConfiguration, DomainConfiguration, bootstrap del administrador inicial, guardas de producción
```

Flujo: `Controller → Puerto de entrada → Servicio de aplicación (transacción) → Servicio de dominio → Puerto de salida →
Adaptador (JPA/MongoDB/simulado)`. El dominio no importa Spring; la capa de aplicación sí usa sus anotaciones.

## Tecnologías

| Tecnología | Versión | Propósito |
|---|---|---|
| Java | 17 | Lenguaje |
| Spring Boot | 4.1.2 | Web MVC, Security, Data JPA, Validation, Actuator |
| MySQL | 8.x | Base principal (Flyway valida el esquema en producción) |
| MongoDB | 6.x | Auditoría |
| java-jwt | 4.4.0 | Tokens JWT HS256 |
| springdoc-openapi | 3.2.0 | Swagger/OpenAPI (solo en desarrollo, o con `SWAGGER_ENABLED=true`) |
| Maven | wrapper | Construcción |

## Perfiles y esquema de base de datos

| Perfil | Base de datos | Esquema |
|---|---|---|
| (ninguno) | Sin DataSource: arranca solo con el dominio, sin endpoints REST | — |
| `jpa` | MySQL y MongoDB locales (`application-jpa.properties`) | Lo genera Hibernate (`ddl-auto=update`); solo para desarrollo |
| `jpa,prod` | Todo por variables de entorno, sin valores por defecto | Flyway aplica `db/schema/V1__initial_schema.sql` y Hibernate lo valida |

Las migraciones antiguas `db/migration/V1__schema.sql` y `V2__seed.sql` **no se ejecutan** (V1 es SQL inválido y V2 siembra
cuentas de contraseña conocida); Flyway apunta solo a `classpath:db/schema` y la imagen Docker las excluye. Ver
`market/docs/migrations/`.

## Cómo ejecutar

### Local (desarrollo)

```bash
cd market
# Requiere MySQL y MongoDB locales; ajusta application-jpa.properties si hace falta
./mvnw spring-boot:run -Dspring-boot.run.profiles=jpa
```

La API queda en http://localhost:8080 (Swagger en `/swagger-ui.html`). El registro público es solo de compradores; el
administrador inicial se crea con el procedimiento de `market/docs/ADMIN_INICIAL_ACTIVACION.md`.

### Docker Compose

```bash
cp .env.example .env     # y rellene las variables obligatorias (contraseñas, JWT_SECRET)
docker compose up -d
curl http://localhost:8080/actuator/health
```

El perfil `prod` **bloquea el pago simulado**: la aplicación no arranca hasta que declare
`NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED=true` en `.env`. Hágalo solo en un despliegue académico o de demostración; un
despliegue real debe sustituir el adaptador por una pasarela real. Más detalles en `market/docs/ENTORNOS.md`.

## Cómo ejecutar las pruebas

```bash
cd market
./mvnw -B -ntp test
```

- Por defecto usa H2 en memoria y no necesita servicios externos.
- Las pruebas `*MySqlTest` (Flyway + `validate`, concurrencia, pool) se **omiten** si no hay una base MySQL **desechable**:
  defina `NEXUS_TEST_MYSQL_URL`, `NEXUS_TEST_MYSQL_USER` y `NEXUS_TEST_MYSQL_PASSWORD`. Nunca las apunte a una base con datos.
  El CI las ejecuta y comprueba que no queden omitidas.

## Documentación de referencia

| Archivo | Descripción |
|---|---|
| `DIAGNOSTICO.md` | Diagnóstico y hoja de ruta (estado real y pendientes) |
| `SDD/Software Architecture/Software Architecture.md` | Arquitectura hexagonal, principios y restricciones |
| `SDD/domain/*.md` | Modelo, value objects, enums, specifications y servicios de dominio |
| `SDD/Output Ports.md` | Puertos de salida y sus adaptadores |
| `market/docs/ENTORNOS.md` | Desarrollo y producción, variables, Docker Compose |
| `market/docs/migrations/` | Esquema, migraciones antiguas y procedimientos por tipo de instalación |
| `market/docs/PAGOS_IDEMPOTENCIA.md` | Estado del pago simulado y diseño para una pasarela real |
| `market/docs/CONCURRENCIA_BLOQUEOS.md`, `TRANSACCIONES.md` | Bloqueos, orden de bloqueo y transacciones |
| `market/docs/CI_CD.md` | Pipeline, Dependency-Check, imagen Docker |

## Notas para Desarrolladores

- El paquete `domain` **NO DEBE** contener ninguna dependencia de Spring, JPA ni MongoDB.
- Los Value Objects son inmutables y tienen su propia lógica de validación.
- Los catálogos de negocio (`SystemRole`, `OrderStatus`, etc.) **NO son enums de Java**, sino clases que extienden `DomainCatalog`. Solo los enums técnicos (`InventoryMovementType`, etc.) son enums de Java.
- Las specifications pueden inyectar puertos de repositorio para validar reglas que requieren consultas.
- Cambios de esquema: solo con una migración nueva en `db/schema` (`V2__...`); nunca se edita el `V1`.

## Contribuciones

Este proyecto es de carácter educativo y está siendo desarrollado siguiendo las mejores prácticas de ingeniería de software. Si deseas contribuir o reportar un error, por favor abre un issue o un pull request.

## Licencia

Este proyecto se encuentra bajo la licencia MIT.
