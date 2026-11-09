# Documentación de la API con Swagger (OpenAPI)

La API se documenta con OpenAPI 3.1 (springdoc). Hay tres formas de verla, y todas salen del código:

| Qué | Dónde | Para qué |
|---|---|---|
| **Swagger UI** | `http://localhost:8080/swagger-ui.html` | leer y **probar** los endpoints desde el navegador |
| **Documento vivo (JSON)** | `http://localhost:8080/v3/api-docs` | lo que genera la aplicación en ejecución |
| **Documento publicado** | `market/docs/api/openapi.json` | para importar en Postman, Swagger Editor o generar clientes, sin tener la aplicación levantada |

## 1. Abrir Swagger UI

En desarrollo está activo. Con Docker Compose y el perfil `prod` está **desactivado por defecto** (la documentación pasaría a ser pública):
ponga `SWAGGER_ENABLED=true` en el `.env` y ejecute `docker compose up -d`. Sin esa variable, `/v3/api-docs` y `/swagger-ui.html` responden 401.

## 2. Probar un endpoint protegido (botón Authorize)

1. Abra **Autenticación y cuentas → `POST /api/auth/login`**, pulse *Try it out*, escriba un correo y una contraseña y ejecute.
2. Copie el `token` de la respuesta.
3. Pulse **Authorize** (arriba a la derecha), pegue el token **sin** escribir `Bearer` y confirme. Swagger UI lo recuerda al recargar la página.
4. Ahora cada endpoint con candado se ejecuta con el rol de esa cuenta.

Cada operación indica en su descripción qué roles pueden usarla. Si usa el rol equivocado, la respuesta es 403; sin token, 401.

| Quiere probar | Inicie sesión con una cuenta de rol |
|---|---|
| comprar (carrito, pedido, pago) | COMPRADOR |
| crear productos, inventario, publicar | VENDEDOR |
| dar de alta vendedores y personal, bodegas, facturas, reembolsos | ADMIN |
| despachar y entregar pedidos | OPERADOR logístico |
| solo consultar | SUPERVISOR |

Las cuentas de prueba de su entorno local se describen en `market/docs/ENTORNOS.md`; el administrador inicial se crea con
`market/docs/ADMIN_INICIAL_ACTIVACION.md`, y el administrador da de alta a los demás roles (`POST /api/auth/register/seller`
y `POST /api/auth/register/staff`).

## 3. Qué documenta

- **82 operaciones** agrupadas en 13 etiquetas (autenticación, carrito, inventario, facturas, pedidos y pagos, reportes, catálogo y productos,
  devoluciones y reembolsos, envíos, usuarios y bodegas, logística del operador y supervisión de pedidos; ver `LOGISTICA_PEDIDOS.md`). `reserve` y `release` del inventario existen en el código pero la seguridad
  los deniega, así que no se documentan.
- **Quién puede usar cada una** (roles) y su **resumen**.
- **8 endpoints públicos** (sin candado): registro de comprador, login, verificación y reenvío del código, recuperación de contraseña, y el
  catálogo público.
- **Ejemplos y descripciones** de cada campo de los cuerpos de petición. Las contraseñas son campos de solo escritura y su ejemplo
  es un texto como `<clave-de-al-menos-8-caracteres>`, nunca un valor real.
- **El código de éxito real** (200, 201 o 202) y las respuestas de error comunes: 400 (cuerpo inválido), 401 (sin token) y 403 (sin
  permiso), con el formato `{code, message, timestamp}`.

El pago, el envío y las notificaciones son **simulados**: la descripción general de la API lo avisa.

## 4. Mantener la documentación al día

La documentación **no puede desviarse del código**: la prueba `OpenApiDocumentationIntegrationTest` falla si

- una operación no tiene resumen, descripción o etiqueta;
- algo se documenta como público y la seguridad lo protege, o al revés (llama a cada operación sin token);
- el código de éxito documentado no es el real;
- `market/docs/api/openapi.json` no coincide con el documento que genera la aplicación.

Cuando cambie un endpoint, anote el controlador (`@Operation`, y `@ApiResponse` si no responde 200) y regenere el archivo publicado:

```bash
cd market
./mvnw test -Dtest=OpenApiDocumentationIntegrationTest -Dopenapi.update=true
```

La configuración general (descripción, esquema de seguridad Bearer, respuestas de error estándar) está en
`infrastructure/config/OpenApiConfiguration.java`.

## 5. Importar el documento en Postman

*Import* → elija `market/docs/api/openapi.json`. Postman crea una colección con las 82 operaciones, su documentación y los ejemplos
de los cuerpos. Para las protegidas, añada el token en la pestaña *Authorization* de la colección (tipo Bearer Token).

## 6. Identificadores legibles y técnicos

Los UUID siguen siendo la referencia técnica (inventario, carritos, pedidos, facturas), pero las operaciones cotidianas pueden usar
datos legibles:

| Qué | Cómo se indica | Dónde |
|---|---|---|
| Cuenta de usuario | UUID, **correo** o **número de documento** en `{userId}` | `GET /api/users/{userId}` y `PATCH /api/users/{userId}/block\|activate\|deactivate` |
| Perfil de la cuenta | `buyerId` o `sellerId` y `businessName` en la respuesta de `GET /api/users/{userId}` | para continuar con carrito, pedidos, bodegas y productos |
| Producto | **`sellerSku`**, código comercial que define el vendedor (único en su catálogo; otro vendedor puede repetirlo) | crear/editar producto; `GET /api/sellers/{sellerId}/products/by-sku/{sellerSku}`; `GET /api/products?sellerId=…&sellerSku=…` |
| Vendedor y comprador en carritos, pedidos y catálogo | `sellerName`, `sellerSku`, `buyerName` (y `buyerEmail` solo para el propio comprador, ADMIN y SUPERVISOR) | respuestas de carrito, pedido y producto |

Reglas: sin ser ADMIN o SUPERVISOR solo se puede consultar la propia cuenta, y cualquier otra referencia recibe 403 exista o no
(no sirve para averiguar qué correos están registrados). `sellerSku` va de 1 a 40 caracteres (letras, dígitos, `.`, `-`, `_`, sin
espacios), se guarda en mayúsculas y, si se omite al crear el producto, se genera uno (`SKU-XXXXXXXX`). Los productos creados antes
de la migración V4 tienen `sellerSku` nulo hasta que el vendedor lo asigne con `PUT /api/products/{productId}`.
