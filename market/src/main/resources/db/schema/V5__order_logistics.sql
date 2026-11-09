-- ============================================================================
-- NexusMarket - V5: gestion logistica de pedidos
-- ============================================================================
-- Cambios ADITIVOS: no se elimina ni se modifica ningun dato existente.
--
--  * orders.order_code: codigo legible del pedido (NM-PED-AAAA-XXXXXX), unico. El UUID order_id sigue siendo la clave
--    primaria y la referencia de ordenes, envios, facturas y devoluciones. Los pedidos existentes reciben un codigo
--    secuencial por orden de creacion (NM-PED-<anio>-000001...) dentro de esta misma migracion.
--  * orders.assigned_operator_id / assigned_at: operador logistico responsable (NULL mientras no se asigne).
--  * order_pickups: unidades recogidas por linea de pedido (la cantidad pedida sale de order_items).
--  * order_incidents: novedades logisticas con su atencion (nunca se borran al resolverse).
--  * order_events: historial cronologico del pedido (solo se agregan filas).
-- ============================================================================

ALTER TABLE `orders` ADD COLUMN `order_code` varchar(24) NULL;
ALTER TABLE `orders` ADD COLUMN `assigned_operator_id` varchar(36) NULL;
ALTER TABLE `orders` ADD COLUMN `assigned_at` datetime(6) NULL;

UPDATE `orders` o
JOIN (SELECT `order_id`, ROW_NUMBER() OVER (ORDER BY `created_at`, `order_id`) AS rn FROM `orders`) n
  ON n.`order_id` = o.`order_id`
SET o.`order_code` = CONCAT('NM-PED-', YEAR(o.`created_at`), '-', LPAD(n.rn, 6, '0'))
WHERE o.`order_code` IS NULL;

ALTER TABLE `orders` ADD UNIQUE KEY `uk_orders_order_code` (`order_code`);
ALTER TABLE `orders` ADD KEY `idx_orders_assigned_operator` (`assigned_operator_id`);

CREATE TABLE `order_pickups` (
  `pickup_id` varchar(36) NOT NULL,
  `order_id` varchar(36) NOT NULL,
  `product_id` varchar(36) NOT NULL,
  `picked_quantity` int NOT NULL,
  `updated_by` varchar(36) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`pickup_id`),
  UNIQUE KEY `uk_order_pickups_line` (`order_id`,`product_id`),
  CONSTRAINT `fk_order_pickups_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`),
  CONSTRAINT `chk_order_pickups_qty` CHECK ((`picked_quantity` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `order_incidents` (
  `incident_id` varchar(36) NOT NULL,
  `order_id` varchar(36) NOT NULL,
  `product_id` varchar(36) DEFAULT NULL,
  `type` varchar(40) NOT NULL,
  `description` varchar(1000) NOT NULL,
  `reported_by` varchar(36) NOT NULL,
  `reported_at` datetime(6) NOT NULL,
  `status` varchar(20) NOT NULL,
  `responsible_id` varchar(36) DEFAULT NULL,
  `resolution_notes` varchar(1000) DEFAULT NULL,
  `resolved_by` varchar(36) DEFAULT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`incident_id`),
  KEY `idx_order_incidents_order` (`order_id`),
  KEY `idx_order_incidents_status` (`status`),
  CONSTRAINT `fk_order_incidents_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `order_events` (
  `event_id` varchar(36) NOT NULL,
  `order_id` varchar(36) NOT NULL,
  `incident_id` varchar(36) DEFAULT NULL,
  `event_type` varchar(40) NOT NULL,
  `actor_id` varchar(36) DEFAULT NULL,
  `actor_role` varchar(30) DEFAULT NULL,
  `detail` varchar(1000) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`event_id`),
  KEY `idx_order_events_order` (`order_id`,`created_at`),
  CONSTRAINT `fk_order_events_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
