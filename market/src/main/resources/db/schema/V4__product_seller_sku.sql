-- ============================================================================
-- NexusMarket - V4: codigo comercial del producto (seller_sku)
-- ============================================================================
-- seller_sku: codigo legible que define el vendedor para cada producto (p. ej. TEC-001). Es unico dentro del catalogo de un
-- mismo vendedor; dos vendedores pueden usar el mismo codigo. El UUID product_id sigue siendo la clave primaria y la
-- referencia de inventario, carritos, pedidos y facturas: no cambia.
-- Los productos existentes quedan con seller_sku NULL (sin codigo asignado): MySQL permite varios NULL en una clave unica,
-- de modo que no hay duplicados ni se inventan codigos. El vendedor puede asignarlo despues editando el producto.
--
-- specifications_json se amplia de TINYTEXT (255 bytes) a TEXT (65535): las variantes del producto se guardan ahi y hasta
-- 20 variantes de 50 caracteres no caben en 255 bytes. Ampliar el tipo no modifica ni elimina ningun dato.
-- ============================================================================

ALTER TABLE `products` ADD COLUMN `seller_sku` varchar(40) NULL;
ALTER TABLE `products` ADD UNIQUE KEY `uk_products_seller_sku` (`seller_id`, `seller_sku`);
ALTER TABLE `products` MODIFY COLUMN `specifications_json` text NOT NULL;
