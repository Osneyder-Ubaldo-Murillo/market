# Software Architecture – NexusMarket

## Visión General

NexusMarket sigue una **Arquitectura Hexagonal (Puertos y Adaptadores)** combinada con **Domain‑Driven Design (DDD)**.

El objetivo principal es aislar el dominio del negocio (marketplace) de los detalles tecnológicos (bases de datos, frameworks, protocolos de comunicación, etc.), garantizando que las reglas de negocio sean independientes, fácilmente mantenibles y totalmente testeables.

## Principios Arquitectónicos

- **Domain‑first**: todo el conocimiento del negocio reside en el dominio.
- **Separación de preocupaciones**: cada capa tiene una responsabilidad bien definida.
- **Inversión de dependencias**: las dependencias siempre apuntan hacia el dominio.
- **Independencia tecnológica**: el dominio no conoce Spring, JPA, MySQL, MongoDB, REST, ni ningún otro framework o protocolo.
- **Alta cohesión y bajo acoplamiento** entre los componentes.
- **Límites explícitos** entre capas: dominio, aplicación, infraestructura y adaptadores.


## Capas de la Arquitectura
Aplicación
│
├── Adaptadores (Adapters)
│ ├── Entrada (in) → Controladores REST, mensajería, eventos
│ └── Salida (out) → Persistencia (MySQL, MongoDB), servicios externos (pagos, envíos, notificaciones)
│
├── Dominio (Domain)
│ ├── Modelos (agregados, entidades)
│ ├── Objetos de Valor (Value Objects)
│ ├── Enums y Catálogos
│ ├── Especificaciones (Specifications)
│ ├── Servicios de Dominio
│ ├── Puertos (Ports) – Entrada (casos de uso) y Salida (repositorios, servicios externos)
│ └── Excepciones de negocio
│
└── Infraestructura (Infrastructure)
├── Configuración (Spring, seguridad, bases de datos)
├── Seguridad (JWT, autenticación)
└── Conectores (DataSources, plantillas REST)


## Flujo de Dependencias

Todas las dependencias apuntan hacia el dominio:
Controlador REST (Adapter de entrada)
│
▼
Puerto de entrada (interfaz de caso de uso)
│
▼
Servicio de Dominio (implementa la lógica de negocio)
│
▼
Puerto de salida (interfaz de repositorio o servicio externo)
│
▼
Adaptador de salida (implementación concreta: JPA, MongoDB, REST Client, etc.)
│
▼
Recurso externo (MySQL, MongoDB, pasarela de pagos, transportadora, etc.)


## Estructura de Paquetes (Java)
nexus.market
├── NexusMarketApplication.java // Punto de entrada Spring Boot
│
├── adapters
│ ├── in
│ │ └── rest
│ │ ├── controllers // Controladores REST
│ │ ├── requests // DTOs de entrada
│ │ ├── responses // DTOs de salida
│ │ └── mappers // Conversión DTO ↔ Modelo de dominio
│ │
│ └── out
│ ├── persistence
│ │ ├── mysql
│ │ │ ├── adapters // Implementan puertos de salida (repositorios)
│ │ │ ├── entities // Entidades JPA
│ │ │ ├── repositories // Interfaces Spring Data JPA
│ │ │ └── mappers // Conversión Entity ↔ Modelo de dominio
│ │ └── mongodb
│ │ ├── adapters // Auditoría
│ │ ├── documents // Documentos MongoDB
│ │ ├── repositories // Interfaces Spring Data MongoDB
│ │ └── mappers
│ ├── payment // Adaptador para pasarela de pagos
│ ├── shipping // Adaptador para transportadoras
│ └── notification // Adaptador para envío de correos/SMS
│
├── domain
│ ├── models // Agregados y entidades de dominio
│ │ ├── User.java
│ │ ├── Buyer.java
│ │ ├── Seller.java
│ │ ├── Warehouse.java
│ │ ├── Product.java
│ │ ├── Inventory.java
│ │ ├── Cart.java
│ │ ├── Order.java
│ │ ├── Invoice.java
│ │ ├── Shipping.java
│ │ ├── Return.java
│ │ └── Refund.java
│ │
│ ├── valueobjects // Value Objects inmutables
│ │ ├── DomainCatalog.java (abstracto)
│ │ ├── SystemRole.java
│ │ ├── UserStatus.java
│ │ ├── CommercialStatus.java
│ │ ├── SellerStatus.java
│ │ ├── ProductStatus.java
│ │ ├── InventoryStatus.java
│ │ ├── OrderStatus.java
│ │ ├── ProductType.java
│ │ ├── WarehouseType.java
│ │ ├── WarehouseStatus.java
│ │ ├── ShippingStatus.java
│ │ ├── ReturnStatus.java
│ │ ├── RefundStatus.java
│ │ ├── InvoiceStatus.java
│ │ ├── ReturnReason.java
│ │ ├── OperationType.java
│ │ ├── Email.java
│ │ ├── DocumentId.java
│ │ ├── FullName.java
│ │ ├── BusinessName.java
│ │ ├── TaxId.java
│ │ ├── Address.java
│ │ ├── Money.java
│ │ ├── Quantity.java
│ │ ├── ProductName.java
│ │ ├── ProductDescription.java
│ │ ├── UserId.java
│ │ ├── BuyerId.java
│ │ ├── SellerId.java
│ │ ├── WarehouseId.java
│ │ ├── ProductId.java
│ │ ├── InventoryId.java
│ │ ├── CartId.java
│ │ ├── OrderId.java
│ │ ├── InvoiceId.java
│ │ ├── ShippingId.java
│ │ ├── ReturnId.java
│ │ └── RefundId.java
│ │
│ ├── enums // Enums técnicos sin metadata
│ │ ├── InventoryMovementType.java
│ │ ├── NotificationChannel.java
│ │ └── AuditSeverity.java
│ │
│ ├── specifications // Reglas de negocio reutilizables
│ │ ├── AvailableInventorySpecification.java
│ │ ├── ActiveUserSpecification.java
│ │ ├── ActiveBuyerSpecification.java
│ │ ├── OrderModifiableSpecification.java
│ │ ├── RefundEligibilitySpecification.java
│ │ ├── UniqueUserSpecification.java
│ │ └── ProductPublishableSpecification.java
│ │
│ ├── services // Servicios de dominio
│ │ ├── UserManagementService.java
│ │ ├── ProductCatalogService.java
│ │ ├── InventoryService.java
│ │ ├── CartService.java
│ │ ├── OrderService.java
│ │ ├── ShippingService.java
│ │ ├── ReturnRefundService.java
│ │ ├── InvoiceService.java
│ │ └── AuditService.java
│ │
│ ├── ports
│ │ ├── in // Puertos de entrada (casos de uso)
│ │ │ ├── RegisterSellerUseCase.java
│ │ │ ├── CreateOrderUseCase.java
│ │ │ ├── UpdateOrderStatusUseCase.java
│ │ │ └── ...
│ │ └── out // Puertos de salida
│ │ ├── UserRepositoryPort.java
│ │ ├── BuyerRepositoryPort.java
│ │ ├── SellerRepositoryPort.java
│ │ ├── WarehouseRepositoryPort.java
│ │ ├── ProductRepositoryPort.java
│ │ ├── InventoryRepositoryPort.java
│ │ ├── CartRepositoryPort.java
│ │ ├── OrderRepositoryPort.java
│ │ ├── InvoiceRepositoryPort.java
│ │ ├── ShippingRepositoryPort.java
│ │ ├── ReturnRepositoryPort.java
│ │ ├── RefundRepositoryPort.java
│ │ ├── AuditLogRepositoryPort.java
│ │ ├── PaymentServicePort.java
│ │ ├── ShippingProviderPort.java
│ │ ├── NotificationPort.java
│ │ ├── PasswordServicePort.java
│ │ ├── JwtServicePort.java
│ │ └── BusinessConfigurationPort.java
│ │
│ └── exceptions // Excepciones de negocio
│ ├── BusinessException.java
│ ├── InsufficientInventoryException.java
│ ├── OrderNotModifiableException.java
│ ├── UserAlreadyExistsException.java
│ └── ...
│
└── infrastructure
├── config // Configuración Spring
│ ├── WebConfig.java
│ ├── DatabaseConfig.java
│ ├── SecurityConfig.java
│ └── ...
├── security // JWT, filtros, etc.
│ ├── JwtFilter.java
│ ├── JwtTokenProvider.java
│ └── ...
└── database // Inicialización y conexiones
├── MySQLInitializer.java
└── MongoInitializer.java

## Restricciones Arquitectónicas

1. La lógica de negocio reside **exclusivamente** en el dominio.
2. Los controladores no contienen reglas de negocio; solo delegan en puertos de entrada.
3. Los DTOs de entrada/salida **nunca** entran al dominio; se convierten en modelos de dominio en los adaptadores.
4. Las entidades de persistencia (JPA) **no** se exponen en la API; se mapean a modelos de dominio.
5. El dominio se comunica con el exterior únicamente a través de puertos (interfaces).
6. Los adaptadores implementan puertos, pero nunca definen reglas de negocio.
7. La infraestructura depende del dominio, nunca al revés.
8. Cada dependencia apunta hacia el dominio.
9. Las entidades de dominio no conocen Spring, JPA, MongoDB, HTTP, etc.
10. El dominio debe ser completamente testeable sin necesidad de levantar la infraestructura.


## Beneficios

- **Independencia tecnológica**: se puede cambiar de base de datos, framework de seguridad o proveedores externos sin afectar al dominio.
- **Mantenibilidad a largo plazo**: el código de negocio está aislado y es fácil de entender y modificar.
- **Testabilidad**: el dominio se prueba con unit tests sin necesidad de bases de datos o servidores.
- **Alineación con DDD**: el modelo de dominio refleja fielmente el lenguaje ubicuo del negocio.