# NexusMarket

**NexusMarket** es una plataforma digital centralizada que actúa como intermediario comercial entre compradores y vendedores. El sistema administra integralmente la operación: desde el registro de usuarios y la publicación de productos, hasta la logística, facturación y posventa.

Este proyecto está construido siguiendo los principios de **Domain-Driven Design (DDD)** y **Arquitectura Hexagonal (Puertos y Adaptadores)** para garantizar un dominio de negocio aislado, mantenible y completamente independiente de frameworks y tecnologías externas.

---

## Arquitectura del Proyecto

La aplicación está organizada en las siguientes capas, siguiendo el patrón Hexagonal:
src/main/java/nexus/market/
├── adapters/ # Adaptadores de entrada (REST) y salida (Persistencia)
│ ├── in/ # Controladores REST, DTOs de entrada/salida
│ └── out/ # Implementaciones de persistencia (MySQL, MongoDB) y servicios externos
├── domain/ # Núcleo del negocio (libre de frameworks)
│ ├── models/ # Agregados y entidades (User, Order, Product, etc.)
│ ├── valueobjects/ # Objetos inmutables (Email, Money, Address, SystemRole, etc.)
│ ├── enums/ # Enums técnicos (InventoryMovementType, NotificationChannel, etc.)
│ ├── specifications/ # Reglas de negocio reutilizables (AvailableInventorySpecification, etc.)
│ ├── services/ # Servicios de dominio (OrderService, InventoryService, etc.)
│ ├── ports/ # Puertos de entrada (casos de uso) y salida (repositorios)
│ └── exceptions/ # Excepciones de negocio personalizadas
└── infrastructure/ # Configuración de Spring, seguridad y bases de datos


### Flujo de Dependencias

Todas las dependencias apuntan hacia el dominio:
Controller (Adapter Entrada) → Puerto Entrada (Caso de Uso) → Servicio Dominio → Puerto Salida (Interfaz) → Adapter Salida (JPA/MongoDB) → Base de Datos


##  Tecnologías Utilizadas

| Tecnología             | Versión   | Propósito |
|------------------------|-----------|-----------|
| **Java**               | 17        | Lenguaje base |
| **Spring Boot**        | 3.x       | Framework de aplicación (Web, Security, JPA) |
| **Spring Data JPA**    | -         | Persistencia relacional (MySQL) |
| **Spring Data MongoDB**| -         | Persistencia de auditoría (NoSQL) |
| **MySQL**              | 8.x       | Base de datos principal |
| **MongoDB**            | 6.x       | Base de datos para auditoría y trazabilidad |
| **Spring Security**    | -         | Autenticación y autorización (JWT) |
| **Lombok**             | -         | Reducción de código boilerplate |
| **Maven**              | 3.9+      | Gestor de dependencias y construcción |


##  Entregable Actual: Primera Fase

En esta primera entrega se ha implementado el **núcleo del dominio**:

| Componente              | Ubicación                                               | Cantidad |
|-------------------------|---------------------------------------------------------|----------|
| **Models (Agregados)**  | `nexus.market.domain.models`                            | 12       |
| **Value Objects**       | `nexus.market.domain.valueobjects` (incluye catálogos)  | 34       |
| **Enums Técnicos**      | `nexus.market.domain.enums`                             | 3        |
| **Specifications**      | `nexus.market.domain.specifications`                    | 7        |

**Total de clases generadas:** ~56


##  Cómo Ejecutar el Proyecto (Local)

### Prerrequisitos

- **JDK 17** instalado.
- **Maven** 3.9+ instalado.
- **MySQL** 8+ corriendo en `localhost:3306`.
- **MongoDB** 6+ corriendo en `localhost:27017`.

### 1. Clonar el repositorio

```bash´´´
git clone https://github.com/Osneyder-Ubaldo-Murillo/market
cd nexusmarket
2. Configurar las variables de entorno (o application.properties)

Crea un archivo src/main/resources/application.properties o application.yml con las siguientes propiedades:

properties
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
3. Compilar y ejecutar
bash
mvn clean install
mvn spring-boot:run
La aplicación estará disponible en: http://localhost:8080

Documentación de Referencia
Archivo	Descripción
SDD/Software Architecture.md	Definición de la arquitectura hexagonal, principios y restricciones.
SDD/Domain Model.md	Descripción detallada de agregados y entidades del dominio.
SDD/Domain Value Objects.md	Catálogos de negocio y objetos de valor inmutables.
SDD/Domain Enums.md	Enums técnicos para movimientos, canales y severidad.
SDD/Domain Specifications.md	Reglas de negocio reutilizables (validaciones de inventario, usuarios, pedidos).
SDD/Domain Services.md	Servicios de dominio (pendiente para siguiente entrega).
SDD/Output Ports.md	Puertos de salida (pendiente para siguiente entrega).
Estado del Proyecto
Primera entrega completada: Models, Value Objects, Enums y Specifications.
Segunda entrega (pendiente): 
Tercera entrega (pendiente): 

Notas para Desarrolladores
El paquete domain NO DEBE contener ninguna dependencia de Spring, JPA, ni MongoDB.

Los Value Objects son inmutables y tienen su propia lógica de validación.

Los catálogos de negocio (SystemRole, OrderStatus, etc.) NO son enums de Java, sino clases que extienden DomainCatalog. Solo los enums técnicos (InventoryMovementType, etc.) son enums de Java.

Las specifications pueden inyectar repositorios (puertos) para validar reglas que requieren consultas a la base de datos.

Contribuciones
Este proyecto es de carácter educativo y está siendo desarrollado siguiendo las mejores prácticas de ingeniería de software.
Si deseas contribuir o reportar un error, por favor abre un issue o un pull request.

Licencia
Este proyecto se encuentra bajo la licencia MIT.
