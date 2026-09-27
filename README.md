# NexusMarket

**NexusMarket** es una plataforma digital centralizada que actúa como intermediario comercial entre compradores y vendedores. El sistema administra integralmente la operación: desde el registro de usuarios y la publicación de productos, hasta la logística, facturación y posventa.

Este proyecto está construido siguiendo los principios de **Domain-Driven Design (DDD)** y **Arquitectura Hexagonal (Puertos y Adaptadores)** para garantizar un dominio de negocio aislado, mantenible y completamente independiente de frameworks y tecnologías externas.

---

## Arquitectura del Proyecto

La aplicación está organizada en las siguientes capas, siguiendo el patrón Hexagonal:

```
src/main/java/nexus/market/
├── adapters/          # Adaptadores de entrada (REST) y salida (persistencia/servicios)
│   ├── in/            # Controladores REST, DTOs de entrada/salida — pendiente
│   └── out/           # ✅ Implementados: persistence/mysql (JPA), persistence/mongodb
│                      # (auditoría), payment, shipping, notification, security, config
├── domain/            # Núcleo del negocio (libre de frameworks)
│   ├── models/        # Agregados y entidades (User, Order, Product, etc.)
│   ├── valueobjects/  # Objetos inmutables (Email, Money, Address, SystemRole, etc.)
│   ├── enums/         # Enums técnicos (InventoryMovementType, NotificationChannel, etc.)
│   ├── specifications/# Reglas de negocio reutilizables (AvailableInventorySpecification, etc.)
│   ├── services/      # ✅ Servicios de dominio (9 servicios, implementados)
│   ├── ports/         # ✅ Puertos de salida (19 interfaces, implementadas)
│   └── exceptions/    # Excepciones de negocio personalizadas
└── infrastructure/    # Configuración de Spring, seguridad y bases de datos — pendiente
```

### Flujo de Dependencias

Todas las dependencias apuntan hacia el dominio:

```
Controller (Adaptador Entrada) → Puerto Entrada (Caso de Uso) → Servicio Dominio →
Puerto Salida (Interfaz) → Adaptador Salida (JPA/MongoDB) → Base de Datos
```

---

## Tecnologías Utilizadas

| Tecnología             | Versión   | Propósito |
|------------------------|-----------|-----------|
| **Java**               | 17        | Lenguaje base |
| **Spring Boot**        | 4.1.0     | Framework de aplicación (Web, Security, JPA) |
| **Spring Data JPA**    | -         | Persistencia relacional (MySQL) |
| **Spring Data MongoDB**| -         | Persistencia de auditoría (NoSQL) |
| **MySQL**              | 8.x       | Base de datos principal |
| **MongoDB**            | 6.x       | Base de datos para auditoría y trazabilidad |
| **Spring Security**    | -         | Autenticación y autorización (JWT) |
| **Lombok**             | -         | Reducción de código boilerplate |
| **Maven**              | 3.9+      | Gestor de dependencias y construcción |

---

## Entregable Actual

Se ha implementado el **núcleo del dominio** y su **suite de pruebas**:

| Componente              | Ubicación                                               | Cantidad |
|-------------------------|---------------------------------------------------------|----------|
| **Models (Agregados)**  | `nexus.market.domain.models`                            | 12       |
| **Value Objects**       | `nexus.market.domain.valueobjects` (incluye catálogos)  | 46       |
| **Enums Técnicos**      | `nexus.market.domain.enums`                             | 3        |
| **Specifications**      | `nexus.market.domain.specifications`                    | 7        |
| **Exceptions**          | `nexus.market.domain.exceptions`                        | 4        |
| **Tests de dominio**    | `src/test/java/nexus/market/domain`                     | 52       |

**Total de clases del dominio:** 73

---

## Cómo Ejecutar el Proyecto (Local)

### Prerrequisitos

- **JDK 17** instalado.
- **Maven** 3.9+ instalado (o usa el wrapper `./mvnw.cmd`).
- **MySQL** 8+ corriendo en `localhost:3306` (cuando existan los adaptadores).
- **MongoDB** 6+ corriendo en `localhost:27017` (cuando existan los adaptadores).

### 1. Clonar el repositorio

```bash
git clone https://github.com/Osneyder-Ubaldo-Murillo/market
cd market
```

### 2. Configurar las variables de entorno (o `application.properties`)

Para ejecutar con MySQL y MongoDB reales, usa el perfil `jpa` y ajusta
`src/main/resources/application-jpa.properties` (ya incluye la conexión por defecto y limpia las exclusiones de auto-configuración):

```properties
# MySQL
spring.datasource.url=jdbc:mysql://localhost:3306/nexusmarket?useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=your_password
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# MongoDB
spring.data.mongodb.uri=mongodb://localhost:27017/nexusmarket_audit

# JWT (secrets)
jwt.secret=your_super_secret_key_123456
jwt.expiration=86400000
```

### 3. Compilar y ejecutar

```bash
mvn clean install
mvn spring-boot:run
```

La aplicación estará disponible en: http://localhost:8080

---
## Cómo Ejecutar las Pruebas

```bash
cd market
./mvnw.cmd clean test
```

> Mientras no existan adaptadores de persistencia, Spring arranca sin DataSource
> (exclusiones en `application.properties`). Los tests de integración JPA usan
> H2 en memoria activando el perfil `jpa` con URL en memoria (ver
> `JpaPersistenceIntegrationTest`). Para ejecutar contra MySQL real, usa el
> perfil `jpa` con `application-jpa.properties`.

---

## Documentación de Referencia

| Archivo | Descripción |
|---------|-------------|
| `DIAGNOSTICO.md` | Diagnóstico del proyecto: fallos corregidos y hoja de ruta pendiente. |
| `SDD/Software Architecture.md` | Definición de la arquitectura hexagonal, principios y restricciones. |
| `SDD/Domain Model.md` | Descripción detallada de agregados y entidades del dominio. |
| `SDD/Domain Value Objects.md` | Catálogos de negocio y objetos de valor inmutables. |
| `SDD/Domain Enums.md` | Enums técnicos para movimientos, canales y severidad. |
| `SDD/Domain Specifications.md` | Reglas de negocio reutilizables (validaciones de inventario, usuarios, pedidos). |
| `SDD/domain/Domain Services.md` | Servicios de dominio (documentados; implementación pendiente). |
| `SDD/Output Ports.md` | Puertos de salida (documentados; implementación pendiente). |

## Estado del Proyecto

- **Primera entrega completada**: Models, Value Objects, Enums, Specifications y Exceptions.
- **Segunda entrega completada**: documentación de servicios de dominio y puertos de salida.
- **Revisión (25/09/2026)**: corrección del arranque de Spring, bug `Inventory.confirmSale` resuelto, catálogo `OperationType` completado y **52 tests de dominio pasando** (ver `DIAGNOSTICO.md`).
- **Tercera entrega — parte 1 (26/09/2026)**: **9 servicios de dominio** y **19 puertos de salida** implementados.
- **Tercera entrega — parte 2 (26/09/2026)**: **adaptadores de salida**:
  - `adapters.out.persistence.mysql` — 12 repositorios JPA (entidades + mapeo en cada `*Persistence.java`), activados con el perfil `jpa`.
  - `adapters.out.persistence.mongodb` — auditoría con el driver oficial de MongoDB, activado con el perfil `mongodb`.
  - `adapters.out.payment|shipping|notification|security|config` — pasarela y transportadora simuladas, notificaciones por log, BCrypt, JWT (HS256) y configuración por propiedades.
  - **61 tests, 0 fallos** (52 dominio + 4 adaptadores externos + 5 integración JPA sobre H2).
- **Tercera entrega — pendiente**: adaptadores de entrada (`adapters.in.*`), capa de aplicación y configuración de seguridad HTTP.

### Cómo ejecutar con base de datos real

```bash
cd market
# Ajusta credenciales en src/main/resources/application-jpa.properties
mvn spring-boot:run -Dspring-boot.run.profiles=jpa
```

> Sin el perfil `jpa`, Spring arranca sin DataSource (exclusiones en
> `application.properties`) y solo se carga el dominio: útil para pruebas.

## Notas para Desarrolladores

- El paquete `domain` **NO DEBE** contener ninguna dependencia de Spring, JPA ni MongoDB.
- Los Value Objects son inmutables y tienen su propia lógica de validación.
- Los catálogos de negocio (`SystemRole`, `OrderStatus`, etc.) **NO son enums de Java**, sino clases que extienden `DomainCatalog`. Solo los enums técnicos (`InventoryMovementType`, etc.) son enums de Java.
- Las specifications pueden inyectar repositorios (puertos) para validar reglas que requieren consultas a la base de datos (`UserLookup`, `InventoryRepository`).

## Contribuciones

Este proyecto es de carácter educativo y está siendo desarrollado siguiendo las mejores prácticas de ingeniería de software. Si deseas contribuir o reportar un error, por favor abre un issue o un pull request.

## Licencia

Este proyecto se encuentra bajo la licencia MIT.
