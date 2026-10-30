-- ============================================================================
-- NexusMarket - V3: identificador de la transaccion de cobro del pedido
-- ============================================================================
-- payment_transaction_id: identificador que devolvio la pasarela al cobrar; lo usan los reembolsos y la cancelacion de
-- pedidos pagados. NULL en los pedidos anteriores a este cambio y en los que aun no se han pagado (los reembolsos de
-- pedidos antiguos usan la referencia derivada del pedido, como hasta ahora).
-- No se borra ni se modifica ningun dato existente.
-- ============================================================================

ALTER TABLE `orders` ADD COLUMN `payment_transaction_id` varchar(64) NULL;
