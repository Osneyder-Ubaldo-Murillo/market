-- ============================================================================
-- NexusMarket - V1: esquema inicial (derivado de las entidades JPA)
-- ============================================================================
-- Ubicación activa de Flyway en producción: classpath:db/schema.
-- Fiel a las entidades (esquema que Hibernate 7.4 genera para MySQL 8.0), de modo
-- que spring.jpa.hibernate.ddl-auto=validate lo acepta. Sin datos semilla, sin
-- usuarios y sin credenciales.
-- Las migraciones anteriores (db/migration/V1__schema.sql, V2__seed.sql) NO se
-- ejecutan: quedan intactas y fuera de esta ubicación. Ver docs/migrations/.
-- ============================================================================

CREATE TABLE `users` (
  `id` varchar(36) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `document_id` varchar(40) NOT NULL,
  `email` varchar(160) NOT NULL,
  `full_name` varchar(120) NOT NULL,
  `role` varchar(30) NOT NULL,
  `status` varchar(30) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKtepomggh6kkrlbno36941a56t` (`document_id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `user_credentials` (
  `user_id` varchar(36) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `password_hash` varchar(100) NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `buyers` (
  `buyer_id` varchar(36) NOT NULL,
  `commercial_status` varchar(30) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `city` varchar(80) DEFAULT NULL,
  `complement` varchar(120) DEFAULT NULL,
  `country` varchar(80) DEFAULT NULL,
  `neighborhood` varchar(120) DEFAULT NULL,
  `number` varchar(30) DEFAULT NULL,
  `postal_code` varchar(10) DEFAULT NULL,
  `state` varchar(80) DEFAULT NULL,
  `street` varchar(120) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  PRIMARY KEY (`buyer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `buyer_additional_addresses` (
  `buyer_id` varchar(36) NOT NULL,
  `city` varchar(80) DEFAULT NULL,
  `complement` varchar(120) DEFAULT NULL,
  `country` varchar(80) DEFAULT NULL,
  `neighborhood` varchar(120) DEFAULT NULL,
  `number` varchar(30) DEFAULT NULL,
  `postal_code` varchar(10) DEFAULT NULL,
  `state` varchar(80) DEFAULT NULL,
  `street` varchar(120) DEFAULT NULL,
  `position` int NOT NULL,
  PRIMARY KEY (`buyer_id`,`position`),
  CONSTRAINT `FK88l4krywyqprmbh8qv6g0f9qh` FOREIGN KEY (`buyer_id`) REFERENCES `buyers` (`buyer_id`),
  CONSTRAINT `buyer_additional_addresses_chk_1` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `sellers` (
  `seller_id` varchar(36) NOT NULL,
  `business_name` varchar(160) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `status` varchar(30) NOT NULL,
  `tax_id` varchar(40) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` varchar(36) NOT NULL,
  PRIMARY KEY (`seller_id`),
  UNIQUE KEY `UKmkienosu0ytw43s0g5r2t6kgf` (`tax_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `seller_warehouses` (
  `seller_id` varchar(36) NOT NULL,
  `warehouse_id` varchar(36) DEFAULT NULL,
  `position` int NOT NULL,
  PRIMARY KEY (`seller_id`,`position`),
  CONSTRAINT `FK2uhcjfxa6xxlxdyiiuummedkn` FOREIGN KEY (`seller_id`) REFERENCES `sellers` (`seller_id`),
  CONSTRAINT `seller_warehouses_chk_1` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `warehouses` (
  `warehouse_id` varchar(36) NOT NULL,
  `city` varchar(80) DEFAULT NULL,
  `complement` varchar(120) DEFAULT NULL,
  `country` varchar(80) DEFAULT NULL,
  `neighborhood` varchar(120) DEFAULT NULL,
  `number` varchar(30) DEFAULT NULL,
  `postal_code` varchar(10) DEFAULT NULL,
  `state` varchar(80) DEFAULT NULL,
  `street` varchar(120) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `name` varchar(120) NOT NULL,
  `seller_id` varchar(36) DEFAULT NULL,
  `status` varchar(30) NOT NULL,
  `type` varchar(30) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`warehouse_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `products` (
  `product_id` varchar(36) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `description` varchar(500) NOT NULL,
  `name` varchar(120) NOT NULL,
  `price_amount` decimal(15,2) NOT NULL,
  `price_currency` varchar(3) NOT NULL,
  `product_type` varchar(30) NOT NULL,
  `seller_id` varchar(36) NOT NULL,
  `specifications_json` tinytext NOT NULL,
  `status` varchar(30) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `inventories` (
  `inventory_id` varchar(36) NOT NULL,
  `product_id` varchar(36) NOT NULL,
  `quantity` int NOT NULL,
  `reserved_quantity` int NOT NULL,
  `status` varchar(30) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `warehouse_id` varchar(36) NOT NULL,
  PRIMARY KEY (`inventory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `inventory_movements` (
  `inventory_id` varchar(36) NOT NULL,
  `metadata_json` tinytext,
  `occurred_at` datetime(6) DEFAULT NULL,
  `quantity` int DEFAULT NULL,
  `type` enum('ADJUSTMENT','INCOME','RELEASE','RESERVATION','RETURN','SALE') DEFAULT NULL,
  `position` int NOT NULL,
  PRIMARY KEY (`inventory_id`,`position`),
  CONSTRAINT `FKoxyuibrvriwucfhbhi5qc25l9` FOREIGN KEY (`inventory_id`) REFERENCES `inventories` (`inventory_id`),
  CONSTRAINT `inventory_movements_chk_1` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `carts` (
  `cart_id` varchar(36) NOT NULL,
  `buyer_id` varchar(36) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `currency` varchar(3) NOT NULL,
  `total_amount` decimal(15,2) NOT NULL,
  `total_currency` varchar(3) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`cart_id`),
  UNIQUE KEY `UKlhsynm4v4ttbdtlbgkmyl9bfy` (`buyer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cart_items` (
  `cart_id` varchar(36) NOT NULL,
  `product_id` varchar(36) DEFAULT NULL,
  `product_name` varchar(120) DEFAULT NULL,
  `product_type` varchar(30) DEFAULT NULL,
  `quantity` int DEFAULT NULL,
  `total_amount` decimal(15,2) DEFAULT NULL,
  `total_currency` varchar(3) DEFAULT NULL,
  `unit_amount` decimal(15,2) DEFAULT NULL,
  `unit_currency` varchar(3) DEFAULT NULL,
  `position` int NOT NULL,
  PRIMARY KEY (`cart_id`,`position`),
  CONSTRAINT `FKpcttvuq4mxppo8sxggjtn5i2c` FOREIGN KEY (`cart_id`) REFERENCES `carts` (`cart_id`),
  CONSTRAINT `cart_items_chk_1` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `orders` (
  `order_id` varchar(36) NOT NULL,
  `buyer_id` varchar(36) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `delivered_at` datetime(6) DEFAULT NULL,
  `invoice_id` varchar(36) DEFAULT NULL,
  `city` varchar(80) DEFAULT NULL,
  `complement` varchar(120) DEFAULT NULL,
  `country` varchar(80) DEFAULT NULL,
  `neighborhood` varchar(120) DEFAULT NULL,
  `number` varchar(30) DEFAULT NULL,
  `postal_code` varchar(10) DEFAULT NULL,
  `state` varchar(80) DEFAULT NULL,
  `street` varchar(120) DEFAULT NULL,
  `status` varchar(30) NOT NULL,
  `total_amount` decimal(15,2) NOT NULL,
  `total_currency` varchar(3) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `order_items` (
  `order_id` varchar(36) NOT NULL,
  `product_id` varchar(36) DEFAULT NULL,
  `product_name` varchar(120) DEFAULT NULL,
  `product_type` varchar(30) DEFAULT NULL,
  `quantity` int DEFAULT NULL,
  `total_amount` decimal(15,2) DEFAULT NULL,
  `total_currency` varchar(3) DEFAULT NULL,
  `unit_amount` decimal(15,2) DEFAULT NULL,
  `unit_currency` varchar(3) DEFAULT NULL,
  `position` int NOT NULL,
  PRIMARY KEY (`order_id`,`position`),
  CONSTRAINT `FKbioxgbv59vetrxe0ejfubep1w` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`),
  CONSTRAINT `order_items_chk_1` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `invoices` (
  `invoice_id` varchar(36) NOT NULL,
  `buyer_id` varchar(36) NOT NULL,
  `issue_date` datetime(6) NOT NULL,
  `order_id` varchar(36) NOT NULL,
  `seller_id` varchar(36) NOT NULL,
  `status` varchar(30) NOT NULL,
  `total_amount` decimal(15,2) NOT NULL,
  `total_currency` varchar(3) NOT NULL,
  PRIMARY KEY (`invoice_id`),
  UNIQUE KEY `UKe718q5klx5pempy28p2nx88a6` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `invoice_items` (
  `invoice_id` varchar(36) NOT NULL,
  `product_id` varchar(36) DEFAULT NULL,
  `product_name` varchar(120) DEFAULT NULL,
  `product_type` varchar(30) DEFAULT NULL,
  `quantity` int DEFAULT NULL,
  `total_amount` decimal(15,2) DEFAULT NULL,
  `total_currency` varchar(3) DEFAULT NULL,
  `unit_amount` decimal(15,2) DEFAULT NULL,
  `unit_currency` varchar(3) DEFAULT NULL,
  `position` int NOT NULL,
  PRIMARY KEY (`invoice_id`,`position`),
  CONSTRAINT `FK46ae0lhu1oqs7cv91fn6y9n7w` FOREIGN KEY (`invoice_id`) REFERENCES `invoices` (`invoice_id`),
  CONSTRAINT `invoice_items_chk_1` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `returns` (
  `return_id` varchar(36) NOT NULL,
  `order_id` varchar(36) NOT NULL,
  `request_date` datetime(6) NOT NULL,
  `resolution_date` datetime(6) DEFAULT NULL,
  `status` varchar(30) NOT NULL,
  PRIMARY KEY (`return_id`),
  UNIQUE KEY `UK77s3t0fidlqyc4628fdscnclg` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `return_items` (
  `return_id` varchar(36) NOT NULL,
  `product_id` varchar(36) DEFAULT NULL,
  `product_name` varchar(120) DEFAULT NULL,
  `quantity` int DEFAULT NULL,
  `reason` varchar(40) DEFAULT NULL,
  `position` int NOT NULL,
  PRIMARY KEY (`return_id`,`position`),
  CONSTRAINT `FK5a4moov85fw9ixxf9l0mki44o` FOREIGN KEY (`return_id`) REFERENCES `returns` (`return_id`),
  CONSTRAINT `return_items_chk_1` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `refunds` (
  `refund_id` varchar(36) NOT NULL,
  `amount` decimal(15,2) NOT NULL,
  `currency` varchar(3) NOT NULL,
  `order_id` varchar(36) NOT NULL,
  `processed_date` datetime(6) DEFAULT NULL,
  `request_date` datetime(6) NOT NULL,
  `return_id` varchar(36) DEFAULT NULL,
  `status` varchar(30) NOT NULL,
  PRIMARY KEY (`refund_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `shippings` (
  `shipping_id` varchar(36) NOT NULL,
  `carrier` varchar(80) DEFAULT NULL,
  `estimated_date` date DEFAULT NULL,
  `tracking_number` varchar(64) DEFAULT NULL,
  `order_id` varchar(36) NOT NULL,
  `status` varchar(30) NOT NULL,
  `warehouse_id` varchar(36) NOT NULL,
  PRIMARY KEY (`shipping_id`),
  UNIQUE KEY `UKtu4d98vdobypyhhq3d7am3qo` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
