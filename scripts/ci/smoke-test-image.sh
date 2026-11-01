#!/usr/bin/env bash
# Prueba de arranque REAL de la imagen de NexusMarket: levanta MySQL 8 y MongoDB 6 desechables (tmpfs, sin volumenes,
# red propia, credenciales aleatorias), arranca la imagen con el perfil de produccion y comprueba su salud.
# Solo crea y elimina los recursos que ella misma nombra (prefijo nxsmoke-<fecha>-<aleatorio>).
#
# Uso: bash scripts/ci/smoke-test-image.sh nombre:etiqueta
set -euo pipefail

IMAGE="${1:?Indique la imagen a probar, p. ej. nexusmarket:ci}"
ID="nxsmoke-$(date +%s)-$RANDOM"
NET="$ID-net"
MYSQL="$ID-mysql"
MONGO="$ID-mongo"
APP="$ID-app"

cleanup() {
  status=$?
  if [ "$status" -ne 0 ] && docker inspect "$APP" >/dev/null 2>&1; then
    echo "--- ultimas lineas del log de la aplicacion (no contiene secretos) ---"
    docker logs --tail 60 "$APP" 2>&1 || true
  fi
  docker rm -f -v "$APP" "$MYSQL" "$MONGO" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

fail() { echo "::error::$*"; exit 1; }

ROOT_PW="$(openssl rand -hex 16)"
DB_PW="$(openssl rand -hex 16)"
MONGO_PW="$(openssl rand -hex 16)"
JWT="$(openssl rand -base64 48)"

docker network create "$NET" >/dev/null

docker run -d --name "$MYSQL" --network "$NET" --network-alias mysql --tmpfs /var/lib/mysql:rw \
  -e MYSQL_ROOT_PASSWORD="$ROOT_PW" -e MYSQL_DATABASE=nexusmarket \
  -e MYSQL_USER=nexus_app -e MYSQL_PASSWORD="$DB_PW" mysql:8.0 >/dev/null
docker run -d --name "$MONGO" --network "$NET" --network-alias mongodb --tmpfs /data/db:rw \
  -e MONGO_INITDB_ROOT_USERNAME=nexus_audit -e MONGO_INITDB_ROOT_PASSWORD="$MONGO_PW" mongo:6.0 >/dev/null

echo "Esperando a MySQL..."
for _ in $(seq 1 60); do
  docker exec "$MYSQL" mysql -unexus_app -p"$DB_PW" nexusmarket -e 'select 1' >/dev/null 2>&1 && break
  sleep 3
done
docker exec "$MYSQL" mysql -unexus_app -p"$DB_PW" nexusmarket -e 'select 1' >/dev/null 2>&1 || fail "MySQL no estuvo disponible"

echo "Esperando a MongoDB..."
for _ in $(seq 1 60); do
  docker exec "$MONGO" mongosh --quiet -u nexus_audit -p "$MONGO_PW" --authenticationDatabase admin \
    --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1 && break
  sleep 3
done
docker exec "$MONGO" mongosh --quiet -u nexus_audit -p "$MONGO_PW" --authenticationDatabase admin \
  --eval 'db.runCommand({ping:1}).ok' >/dev/null 2>&1 || fail "MongoDB no estuvo disponible"

docker run -d --name "$APP" --network "$NET" -p 127.0.0.1::8080 \
  -e SPRING_PROFILES_ACTIVE=jpa,prod \
  -e SPRING_DATASOURCE_URL='jdbc:mysql://mysql:3306/nexusmarket?sslMode=REQUIRED&serverTimezone=UTC' \
  -e SPRING_DATASOURCE_USERNAME=nexus_app -e SPRING_DATASOURCE_PASSWORD="$DB_PW" \
  -e SPRING_MONGODB_URI="mongodb://nexus_audit:$MONGO_PW@mongodb:27017/nexusmarket_audit?authSource=admin" \
  -e JWT_SECRET="$JWT" \
  -e NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED=true   -e NEXUSMARKET_SHIPPING_SIMULATED_ALLOWED=true \
  -e NEXUSMARKET_NOTIFICATIONS_LOGGING_ALLOWED=true \
  "$IMAGE" >/dev/null

PORT="$(docker port "$APP" 8080/tcp | head -1 | sed -E 's/.*:([0-9]+)$/\1/')"
BASE="http://127.0.0.1:$PORT"
echo "Aplicacion publicada solo en 127.0.0.1:$PORT; esperando /actuator/health..."

healthy=0
for _ in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Running}}' "$APP")" = "true" ] || fail "El contenedor de la aplicacion se detuvo"
  body="$(curl -s -m 5 "$BASE/actuator/health" || true)"
  if grep -q '"status":"UP"' <<<"$body"; then healthy=1; break; fi
  sleep 3
done
[ "$healthy" -eq 1 ] || fail "La aplicacion no llego a estado UP en 180 s"
echo "OK: /actuator/health = UP"

code() { curl -s -m 10 -o /dev/null -w '%{http_code}' "$@"; }
[ "$(code "$BASE/api/products")" = "200" ] || fail "GET /api/products (publico) no devolvio 200"
echo "OK: GET /api/products = 200 (usa MySQL)"
[ "$(code "$BASE/api/users/00000000-0000-0000-0000-000000000000")" = "401" ] || fail "Una ruta protegida sin token no devolvio 401"
echo "OK: ruta protegida sin token = 401"
swagger="$(code "$BASE/v3/api-docs")"
[ "$swagger" != "200" ] || fail "La documentacion OpenAPI esta publica en produccion"
echo "OK: /v3/api-docs no es publico ($swagger)"
[ "$(docker exec "$APP" id -u)" != "0" ] || fail "La aplicacion corre como root"
echo "OK: la aplicacion corre como usuario no root"
echo "Prueba de arranque de la imagen: CORRECTA"
