#!/usr/bin/env bash
# Diagnostica por que NexusMarket no funciona y dice la CAUSA y que hacer, eslabon por eslabon:
#   1 Docker -> 2 variables (.env) -> 3 contenedores -> 4 puertos -> 5 respuesta HTTP -> 6 causa en el log de la aplicacion.
# Solo LEE: no cambia nada, no reinicia nada y NUNCA imprime el valor de una contrasena, secreto o token.
#
# Uso (desde cualquier carpeta; en Windows, con Git Bash):
#   bash scripts/diagnose.sh                 # revisa la pila local con el .env de la raiz del repositorio
#   bash scripts/diagnose.sh RUTA/al/.env    # revisa con otro archivo de variables
#   bash scripts/diagnose.sh --explain       # lee un log por la entrada estandar y explica la causa (util para pegar un error)
# Variable opcional: NEXUS_BASE_URL (por defecto http://localhost:8080).
# Codigo de salida: 0 = todo bien, 1 = hay al menos un fallo.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${NEXUS_BASE_URL:-http://localhost:8080}"
fails=0
warns=0

ok()   { printf 'OK      %s\n' "$*"; }
info() { printf '        %s\n' "$*"; }
warn() { warns=$((warns + 1)); printf 'AVISO   %s\n' "$1"; [ -n "${2:-}" ] && printf '        Que hacer: %s\n' "$2"; return 0; }
fail() {
  fails=$((fails + 1)); printf 'FALLA   %s\n' "$1"
  [ -n "${2:-}" ] && printf '        Causa: %s\n' "$2"
  [ -n "${3:-}" ] && printf '        Que hacer: %s\n' "$3"
  return 0
}

# ---------------------------------------------------------------- 6 (funcion): causa a partir de un log
# Lee un log por la entrada estandar y, si reconoce un patron conocido, imprime la causa y la solucion.
# Devuelve 0 si explico algo y 1 si no reconocio nada.
explain_log() {
  local log found=1
  log="$(cat)"
  has() { printf '%s' "$log" | grep -qiE "$1"; }
  say() { found=0; printf '        Causa: %s\n        Que hacer: %s\n' "$1" "$2"; }

  if has "Could not resolve placeholder"; then
    local v; v="$(printf '%s' "$log" | grep -oE "Could not resolve placeholder '[A-Za-z0-9_.:-]+'" | head -1 | sed -E "s/.*'([^']+)'.*/\1/")"
    say "Falta la variable de entorno ${v:-?} (la aplicacion no tiene valor por defecto para ella)." \
        "Defínela en el .env (ver .env.example) y recrea la aplicacion: docker compose up -d"
  fi
  if has "pago simulado est.* bloqueado"; then
    say "El pago simulado esta bloqueado con el perfil prod." "Pon NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED=true en el .env (solo demostracion) y ejecuta docker compose up -d"
  fi
  if has "transportadora simulada est.* bloqueada"; then
    say "La transportadora simulada esta bloqueada con el perfil prod." "Pon NEXUSMARKET_SHIPPING_SIMULATED_ALLOWED=true en el .env (solo demostracion) y ejecuta docker compose up -d"
  fi
  if has "notificaciones por log est.* bloqueadas"; then
    say "Las notificaciones por log estan bloqueadas con el perfil prod (escriben los codigos en el log)." "Pon NEXUSMARKET_NOTIFICATIONS_LOGGING_ALLOWED=true en el .env (solo demostracion) y ejecuta docker compose up -d"
  fi
  if has "jwt\.secret"; then
    say "JWT_SECRET falta, es corto (menos de 32 bytes), de ejemplo o demasiado predecible." "Genera uno aleatorio (openssl rand -hex 48), ponlo en JWT_SECRET del .env y ejecuta docker compose up -d"
  fi
  if has "contraseña de ejemplo|igual al usuario"; then
    say "SPRING_DATASOURCE_PASSWORD (MYSQL_PASSWORD) es una contraseña de ejemplo o igual al usuario." "Usa una contraseña propia y aleatoria en el .env"
  fi
  if has "Access denied for user"; then
    say "MySQL rechaza el usuario o la contraseña. Lo habitual: el volumen de MySQL se creo antes con OTRAS credenciales y MySQL conserva las originales." \
        "Restaura la contraseña original del .env, o borra los datos y empieza de cero: docker compose down -v (BORRA la base de datos)"
  fi
  if has "Communications link failure|Connection refused|UnknownHostException: mysql|Unable to obtain connection"; then
    say "La aplicacion no pudo conectar con MySQL (aun no esta lista, o cayo)." "Mira: docker compose ps. Si mysql no esta healthy, docker compose logs mysql --tail 50. Suele resolverse esperando y reintentando."
  fi
  if has "Address already in use|Port [0-9]+ was already in use"; then
    say "El puerto esta ocupado por otro programa." "Cierra el programa que usa ese puerto (ver el paso 4 de este diagnostico) o cambia SERVER_PORT"
  fi
  if has "OutOfMemoryError|Killed|exit code 137|OOMKilled"; then
    say "Se quedo sin memoria." "Cierra programas (navegador, IDE) y sube la memoria de Docker Desktop; luego docker compose up -d"
  fi
  if has "Schema-validation|wrong column type|missing column|missing table"; then
    say "El esquema de la base no coincide con el modelo (Hibernate validate)." "La base viene de otra version. En una base de PRUEBA: docker compose down -v y volver a levantar (Flyway la crea desde cero)"
  fi
  if has "Validate failed|Migration checksum mismatch|FlywayException"; then
    say "Flyway no pudo aplicar o validar las migraciones (la base ya tenia otro historial)." "En una base de PRUEBA: docker compose down -v. En una base con datos: NO la borres; revisa market/docs/migrations/"
  fi
  if has "contraseña del administrador inicial no cumple|candado de aprovisionamiento|necesita al menos 2 conexiones|tamaño del pool"; then
    say "No se pudo crear el administrador inicial (contraseña que no cumple la politica, candado de aprovisionamiento o pool de conexiones menor de 2)." "Revisa NEXUSMARKET_BOOTSTRAP_ADMIN_* en el .env y market/docs/ADMIN_INICIAL_ACTIVACION.md; el archivo de contraseña debe existir y ser legible dentro del contenedor"
  fi
  if has "MongoTimeoutException|MongoSocketOpenException|MongoSocketReadTimeoutException"; then
    say "MongoDB (auditoria) no responde. La API sigue funcionando, pero las operaciones auditadas se enlentecen unos 2 s." "Comprueba docker compose ps y docker compose logs mongodb --tail 50"
  fi
  return $found
}

if [ "${1:-}" = "--explain" ]; then
  echo "Pega o redirige el log y termina con Ctrl+D (o usa: docker compose logs app | bash scripts/diagnose.sh --explain)"
  if explain_log; then exit 0; else echo "        No reconoci ninguna causa conocida en ese texto."; exit 1; fi
fi

ENV_FILE="${1:-$ROOT/.env}"
echo "Diagnostico de NexusMarket ($(date '+%Y-%m-%d %H:%M')) - URL: $BASE"
echo

# ---------------------------------------------------------------- 1. Docker
echo "== 1. Docker"
DOCKER_OK=0
if ! command -v docker >/dev/null 2>&1; then
  fail "Docker no esta instalado o no esta en el PATH" "Sin Docker no se puede levantar la pila con Compose" "Instala Docker Desktop"
elif ! docker info >/dev/null 2>&1; then
  fail "El motor de Docker no responde" "Docker Desktop esta cerrado o todavia arrancando (sin motor no hay contenedores; es la causa mas comun)" \
       "Abre Docker Desktop, espera a que indique que el motor esta en marcha y ejecuta docker compose up -d"
else
  DOCKER_OK=1; ok "el motor de Docker responde"
fi

# ---------------------------------------------------------------- 2. Variables
echo "== 2. Variables del .env"
env_val() { [ -f "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '\r'; }
if [ ! -f "$ENV_FILE" ]; then
  fail "No existe el archivo $ENV_FILE" "Compose exige un .env con las contraseñas y el secreto JWT; sin el, docker compose se niega a arrancar" \
       "cp .env.example .env y rellénalo (ver market/docs/ENTORNOS.md)"
else
  missing=""
  for v in MYSQL_ROOT_PASSWORD MYSQL_USER MYSQL_PASSWORD MONGO_USER MONGO_PASSWORD JWT_SECRET; do
    [ -n "$(env_val "$v")" ] || missing="$missing $v"
  done
  if [ -n "$missing" ]; then
    fail "Faltan variables obligatorias en el .env:$missing" "Compose y la aplicacion no arrancan sin ellas (no tienen valor por defecto)" "Rellénalas en el .env"
  else
    ok "estan definidas las 6 variables obligatorias (sus valores no se muestran)"
  fi
  jwt="$(env_val JWT_SECRET)"
  if [ -n "$jwt" ] && [ "${#jwt}" -lt 32 ]; then
    fail "JWT_SECRET tiene ${#jwt} caracteres" "El minimo es 32 bytes; la aplicacion no arranca con uno mas corto" "Genera uno: openssl rand -hex 48"
  fi
  sim_bad=0
  for f in NEXUSMARKET_PAYMENT_SIMULATED_ALLOWED NEXUSMARKET_SHIPPING_SIMULATED_ALLOWED NEXUSMARKET_NOTIFICATIONS_LOGGING_ALLOWED; do
    val="$(env_val "$f" | tr 'A-Z' 'a-z')"
    if [ "$val" != "true" ]; then
      sim_bad=1
      fail "$f no es 'true' (vale '${val:-vacio}')" "Con el perfil prod los simuladores estan bloqueados y la aplicacion NO arranca sin esta autorizacion" \
           "Pon $f=true en el .env (solo para demostracion) y ejecuta docker compose up -d"
    fi
  done
  [ "$sim_bad" -eq 0 ] && ok "los tres simuladores estan autorizados para la demostracion"
fi

# ---------------------------------------------------------------- 3. Contenedores
echo "== 3. Contenedores"
APP_RUNNING=0
if [ "$DOCKER_OK" -eq 1 ]; then
  cd "$ROOT" 2>/dev/null || true
  ps_out="$(docker compose ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}' 2>/dev/null)"
  if [ -z "$ps_out" ]; then
    fail "No hay contenedores de NexusMarket creados" "La pila nunca se levanto (o se borro con docker compose down)" "docker compose up -d (la primera vez construye la imagen y tarda varios minutos)"
  else
    for svc in mysql mongodb app; do
      line="$(printf '%s\n' "$ps_out" | grep "^$svc|" | head -1)"
      state="$(echo "$line" | cut -d'|' -f2)"; health="$(echo "$line" | cut -d'|' -f3)"; code="$(echo "$line" | cut -d'|' -f4)"
      if [ -z "$line" ]; then fail "el servicio $svc no existe" "" "docker compose up -d"
      elif [ "$state" != "running" ]; then
        fail "$svc no esta en marcha (estado: $state, codigo de salida: ${code:-?})" "El contenedor se detuvo o no pudo arrancar" "Mira docker compose logs $svc --tail 50 (el paso 6 lo analiza si es la aplicacion)"
      elif [ -n "$health" ] && [ "$health" != "healthy" ]; then
        warn "$svc esta en marcha pero su salud es '$health'" "Si acaba de arrancar, espera 1 minuto; si sigue asi, docker compose logs $svc --tail 50"
      else
        ok "$svc en marcha ($health)"
      fi
      [ "$svc" = app ] && [ "$state" = running ] && APP_RUNNING=1
    done
  fi
else
  info "(omitido: Docker no responde)"
fi

# ---------------------------------------------------------------- 4. Puertos
echo "== 4. Puertos"
port_busy() {
  if command -v ss >/dev/null 2>&1; then ss -ltn 2>/dev/null | grep -qE ":$1[[:space:]]"
  else netstat -ano 2>/dev/null | tr -d '\r' | grep -qE "[:.]$1[[:space:]].*LISTENING"; fi
}
for pair in "8080:app" "3306:mysql" "27017:mongodb"; do
  p="${pair%%:*}"; svc="${pair##*:}"
  if port_busy "$p"; then
    if [ "$DOCKER_OK" -eq 0 ]; then warn "el puerto $p esta ocupado, pero Docker no responde y no puedo saber quien lo usa" "Resuelve primero el paso 1 y vuelve a ejecutar este diagnostico"
    elif docker compose ps --format '{{.Service}}|{{.State}}' 2>/dev/null | grep -q "^$svc|running"; then ok "puerto $p ocupado por $svc (correcto)"
    else fail "el puerto $p esta ocupado y $svc no esta en marcha" "Otro programa usa ese puerto, asi que $svc no puede publicarlo" "Cierra ese programa o cambia el puerto; en Windows: netstat -ano | findstr :$p"; fi
  else
    ok "puerto $p libre"
  fi
done

# ---------------------------------------------------------------- 5. HTTP
echo "== 5. Respuesta de la API"
health="$(curl -s -m 6 "$BASE/actuator/health" 2>/dev/null || true)"
if [ -z "$health" ]; then
  fail "La API no responde en $BASE" "No hay nada escuchando (la aplicacion no arranco, se detuvo o aun esta arrancando)" "Mira los pasos 1 a 4; si el contenedor corre, docker compose logs app --tail 50"
elif ! printf '%s' "$health" | grep -q '"status":"UP"'; then
  fail "La API responde pero su salud no es UP: $(printf '%s' "$health" | cut -c1-120)" "Una dependencia (MySQL o MongoDB) no esta disponible" "docker compose ps y docker compose logs app --tail 50"
else
  ok "salud: UP"
  code() { curl -s -o /dev/null -m 8 -w '%{http_code}' "$@" 2>/dev/null; }
  c="$(code "$BASE/api/products")";  [ "$c" = 200 ] && ok "catalogo publico: HTTP 200" || fail "catalogo publico: HTTP $c (esperado 200)" "La API arranco pero no puede leer la base de datos" "docker compose logs app --tail 50"
  c="$(code "$BASE/api/users/00000000-0000-0000-0000-000000000000")"; [ "$c" = 401 ] && ok "ruta protegida sin token: HTTP 401 (la seguridad esta activa)" || warn "ruta protegida sin token: HTTP $c (esperado 401)" "Revisa la configuracion de seguridad"
fi

# ---------------------------------------------------------------- 6. Causa en el log
echo "== 6. Causa segun el log de la aplicacion"
if [ "$fails" -eq 0 ]; then
  ok "todo lo anterior esta en orden: no hay nada que explicar en el log"
elif [ "$DOCKER_OK" -eq 1 ] && docker compose ps -a --format '{{.Service}}' 2>/dev/null | grep -q '^app$'; then
  applog="$(docker compose logs app --tail 300 2>&1 | tr -d '\r')"
  if [ -z "$applog" ]; then info "(la aplicacion no tiene log todavia)"
  elif printf '%s' "$applog" | explain_log; then :
  elif [ "$fails" -gt 0 ]; then
    info "No reconoci una causa conocida. Ultimas lineas de error del log:"
    printf '%s\n' "$applog" | grep -E " ERROR |Exception|APPLICATION FAILED" | tail -6 | cut -c1-190 | sed 's/^/        | /'
  else info "No hay un log con errores conocidos que explique los fallos de arriba."; fi
else
  info "(omitido: no hay contenedor de la aplicacion que leer)"
fi

echo
if [ "$fails" -eq 0 ]; then echo "RESULTADO: todo en orden ($warns aviso(s))."; exit 0
else echo "RESULTADO: $fails fallo(s) y $warns aviso(s). Empieza por el primero: suele ser la causa de los demas."; exit 1; fi
