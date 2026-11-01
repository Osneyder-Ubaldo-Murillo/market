#!/usr/bin/env bash
# Pruebas automatizadas de scripts/ci/validate-nvd-database.sh.
#
# - Usan bases H2 SINTETICAS creadas en un directorio temporal con el esquema real de Dependency-Check 12.2.2 (el
#   data/initialize.sql del jar core). No descargan nada, no tocan ninguna base real ni usan red.
# - IMPORTANTE: el caso positivo solo valida la LOGICA del validador. Una base sintetica aceptada NO demuestra que una
#   base NVD real este completa; eso solo puede comprobarse con una base completa real (ver NVD_VALIDATOR.md).
# - Cada caso negativo comprueba el codigo de salida Y el MOTIVO del rechazo, para que no pase por una razon equivocada.
# - El validador solo admite --now-epoch, --max-job-seconds y --expected-schema con NVD_VALIDATOR_TEST=1 (mecanismo de
#   pruebas, no una frontera de seguridad). Las funciones val/raw lo activan; valnt/rawnt lo quitan para probar el
#   rechazo y el uso operativo.
#
# Uso: bash scripts/ci/test/test-validate-nvd-database.sh [filtro]
#   filtro (opcional): expresion regular; solo se ejecutan las secciones cuyo nombre la cumpla
#   (positivo, archivo, archivos-extra, internos, esquema, minimos, plugin-propios, entorno, politica, parametros-prueba,
#    integridad, orden, emit, temporales, file-only, verify, parcial, ida-y-vuelta).
# Entorno opcional: JAVA (ejecutable de Java 11+), M2_REPO (por defecto ~/.m2/repository), TMPDIR.
set -uo pipefail

FILTER="${1:-}"
TESTDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALIDATOR="$TESTDIR/../validate-nvd-database.sh"
BUILDER="$TESTDIR/NvdFixtureBuilder.java"
JAVA_BIN="${JAVA:-java}"
M2="${M2_REPO:-$HOME/.m2/repository}"
H2_JAR="$M2/com/h2database/h2/2.4.240/h2-2.4.240.jar"
CORE_JAR="$M2/org/owasp/dependency-check-core/12.2.2/dependency-check-core-12.2.2.jar"
for f in "$H2_JAR" "$CORE_JAR" "$VALIDATOR" "$BUILDER"; do
  [ -f "$f" ] || { echo "Falta $f (las pruebas no descargan nada)."; exit 2; }
done
if command -v cygpath >/dev/null 2>&1; then SEP=';'; native() { cygpath -m "$1"; }; else SEP=':'; native() { printf '%s' "$1"; }; fi
CP="$(native "$H2_JAR")$SEP$(native "$CORE_JAR")"
REAL_MKTEMP="$(command -v mktemp)"
REAL_SHA="$(command -v sha256sum)"
REAL_WC="$(command -v wc)"

ROOT="$(mktemp -d)"
trap 'rm -rf -- "$ROOT"' EXIT

NOW="$(date -u +%s)"
JOB_START=$((NOW - 1800))
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
GOOD_CHECKED="$(iso $((NOW - 60)))"
GOOD_MODIFIED="$(iso $((NOW - 3600)))"
COMMIT="$(printf 'a%.0s' $(seq 1 40))"
MAX_JOB=21600

passed=0; failed=0; skipped=0
rc=0; out=""
VPATH=""   # directorio de "shims" que se antepone al PATH del validador (simulacion de fallos de mktemp/cp/mv/sha256sum/wc)

mk() { # nombre version checked modified vulnerabilidades cpe kev
  local d="$ROOT/$1"; shift
  rm -rf -- "$d"
  if ! "$JAVA_BIN" -cp "$CP" "$(native "$BUILDER")" "$(native "$d")" "$@" >"$d.build.log" 2>&1; then
    echo "No se pudo crear la fixture $d:"; cat "$d.build.log"; exit 2
  fi
}
# val: invocacion de PRUEBAS (activa NVD_VALIDATOR_TEST=1, fija --now-epoch y --valid-hours 4 por defecto; los argumentos
# adicionales pueden sobreescribirlos porque el ultimo gana).
val() { # modo dir [extra...]
  local m="$1" d="$2"; shift 2
  local pre=""; [ -n "$VPATH" ] && pre="$VPATH:"
  if [ "$m" = create ]; then
    out="$(NVD_VALIDATOR_TEST=1 PATH="$pre$PATH" bash "$VALIDATOR" --mode create --dir "$d" --job-start-epoch "$JOB_START" --now-epoch "$NOW" --valid-hours 4 --java "$JAVA_BIN" --h2-jar "$H2_JAR" --core-jar "$CORE_JAR" "$@" 2>&1)"
  else
    out="$(NVD_VALIDATOR_TEST=1 PATH="$pre$PATH" bash "$VALIDATOR" --mode verify --dir "$d" --now-epoch "$NOW" --valid-hours 4 --java "$JAVA_BIN" --h2-jar "$H2_JAR" --core-jar "$CORE_JAR" "$@" 2>&1)"
  fi
  rc=$?
}
# valnt: uso OPERATIVO (sin NVD_VALIDATOR_TEST y sin parametros de pruebas: usa el reloj real).
valnt() { # modo dir [extra...]
  local m="$1" d="$2"; shift 2
  if [ "$m" = create ]; then
    out="$(env -u NVD_VALIDATOR_TEST bash "$VALIDATOR" --mode create --dir "$d" --job-start-epoch "$JOB_START" --valid-hours 4 --java "$JAVA_BIN" --h2-jar "$H2_JAR" --core-jar "$CORE_JAR" "$@" 2>&1)"
  else
    out="$(env -u NVD_VALIDATOR_TEST bash "$VALIDATOR" --mode verify --dir "$d" --valid-hours 4 --java "$JAVA_BIN" --h2-jar "$H2_JAR" --core-jar "$CORE_JAR" "$@" 2>&1)"
  fi
  rc=$?
}
raw()   { out="$(NVD_VALIDATOR_TEST=1 bash "$VALIDATOR" "$@" 2>&1)"; rc=$?; }          # sin argumentos por defecto, con modo de pruebas
rawnt() { out="$(env -u NVD_VALIDATOR_TEST bash "$VALIDATOR" "$@" 2>&1)"; rc=$?; }    # sin argumentos por defecto, sin modo de pruebas
ok()   { passed=$((passed + 1)); echo "  ok   - $1"; }
bad()  { failed=$((failed + 1)); echo "  FALLA - $1"; echo "$out" | sed 's/^/         | /'; }
skip() { skipped=$((skipped + 1)); echo "  OMITIDA - $1 ($2)"; }
expect_ok() { # nombre : codigo 0
  if [ "$rc" -eq 0 ]; then ok "$1"; else out="(codigo de salida $rc, se esperaba 0)"$'\n'"$out"; bad "$1"; fi
}
expect_fail() { # nombre motivo : codigo 1 Y el mensaje de rechazo concreto
  if [ "$rc" -ne 1 ]; then out="(codigo de salida $rc, se esperaba 1)"$'\n'"$out"; bad "$1"
  elif ! grep -F -- "RECHAZADA" <<<"$out" | grep -q -F -- "$2"; then
    out="(rechazada, pero no por el motivo esperado: '$2')"$'\n'"$out"; bad "$1"
  else ok "$1  [motivo: $2]"; fi
}
expect_usage() { # nombre [motivo] : codigo 2 (entorno o uso incorrecto, nunca "valida")
  if [ "$rc" -ne 2 ]; then out="(codigo de salida $rc, se esperaba 2)"$'\n'"$out"; bad "$1"
  elif [ -n "${2:-}" ] && ! grep -q -F -- "$2" <<<"$out"; then out="(codigo 2, pero falta el motivo '$2')"$'\n'"$out"; bad "$1"
  else ok "$1${2:+  [motivo: $2]}"; fi
}
expect_review() { # nombre motivo : codigo 3 (no aprobada automaticamente; revision humana)
  if [ "$rc" -ne 3 ]; then out="(codigo de salida $rc, se esperaba 3)"$'\n'"$out"; bad "$1"
  elif ! grep -q -F -- "REVISION HUMANA REQUERIDA" <<<"$out" || ! grep -q -F -- "$2" <<<"$out"; then out="(codigo 3, pero falta el motivo '$2')"$'\n'"$out"; bad "$1"
  elif grep -q -F -- "Resultado: ACEPTADA" <<<"$out"; then out="(codigo 3 pero aparece ACEPTADA)"$'\n'"$out"; bad "$1"
  else ok "$1  [motivo: $2]"; fi
}
expect_has() { # nombre texto
  if grep -q -F -- "$2" <<<"$out"; then ok "$1"; else out="(falta el texto '$2')"$'\n'"$out"; bad "$1"; fi
}
expect_not_has() { # nombre texto
  if grep -q -F -- "$2" <<<"$out"; then out="(no deberia aparecer '$2')"$'\n'"$out"; bad "$1"; else ok "$1"; fi
}
copy() { rm -rf -- "$ROOT/$2"; cp -r -- "$ROOT/$1" "$ROOT/$2"; }

# Contrato del marcador (13 claves). Valores por defecto = los de la fixture "good"; se pueden sobreescribir con k=v.
write_marker() { # dir [clave=valor ...]
  local d="$1"; shift
  local sha size
  sha="$(sha256sum "$d/odc.mv.db" | cut -d' ' -f1)"; size="$(wc -c < "$d/odc.mv.db" | tr -d ' ')"
  {
    echo "dependency_check_version=12.2.2"
    echo "update_started_epoch=$JOB_START"
    echo "completed_epoch=$((NOW - 30))"
    echo "completed_at=$(iso $((NOW - 30)))"
    echo "run_id=123456789"
    echo "commit=$COMMIT"
    echo "odc_sha256=$sha"
    echo "odc_size_bytes=$size"
    echo "nvd_api_last_checked=$GOOD_CHECKED"
    echo "nvd_api_last_modified=$GOOD_MODIFIED"
    echo "count_vulnerability=3"
    echo "count_cpeEntry=2"
    echo "count_knownExploited=1"
  } > "$d/nexusmarket-nvd-update.properties"
  local kv
  for kv in "$@"; do set_marker "$d" "${kv%%=*}" "${kv#*=}"; done
}
set_marker() { sed -i "s|^$2=.*|$2=$3|" "$1/nexusmarket-nvd-update.properties"; }
# Marcador construido como lo hara el workflow: lo emitido por el validador + el contexto de la ejecucion.
marker_from_emit() { # dir archivo-emit
  {
    cat "$2"
    echo "dependency_check_version=12.2.2"
    echo "completed_epoch=$((NOW - 30))"
    echo "completed_at=$(iso $((NOW - 30)))"
    echo "run_id=123456789"
    echo "commit=$COMMIT"
  } > "$1/nexusmarket-nvd-update.properties"
}
snapshot() { (cd "$1" && find . -type f | sort | xargs sha256sum 2>/dev/null); }
zeros64() { printf "$1%.0s" $(seq 1 64); }
mkshim() { # nombre-directorio nombre-comando contenido
  mkdir -p "$ROOT/shims/$1"; printf '#!/usr/bin/env bash\n%s\n' "$3" > "$ROOT/shims/$1/$2"; chmod +x "$ROOT/shims/$1/$2"
}
# Shim que falla/miente en la N-esima llamada (o en todas con "all") y delega en el comando real en las demas.
mkshim_nth() { # nombre-directorio comando real N cuerpo
  local c="$ROOT/shims/$1.count"; rm -f "$c"
  local cond="[ \"\$n\" -eq $4 ]"; [ "$4" = all ] && cond="true"
  mkshim "$1" "$2" 'REAL="'"$3"'"; c="'"$c"'"; n=$(( $(cat "$c" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$c"; if '"$cond"'; then '"$5"'; else exec "$REAL" "$@"; fi'
}
run_section() {
  if [ -n "$FILTER" ] && ! [[ "$1" =~ $FILTER ]]; then return; fi
  echo "== $1"; "$2"
}

sec_positivo() {
  local snap_before snap_after want_sha want_size
  snap_before="$(snapshot "$ROOT/good")"
  val create "$ROOT/good" --emit "$ROOT/emit.txt"
  expect_ok "positivo sintetico aceptado (valida solo la logica; NO prueba que una base real este completa)"
  expect_has "el resultado incluye el aviso de que no prueba que la base este completa" "NO demuestra que la base NVD este completa"
  expect_has "muestra los marcadores internos leidos" "last.checked=$GOOD_CHECKED"
  expect_has "muestra los recuentos informativos" "vulnerability=3 cpeEntry=2 knownExploited=1"
  expect_has "muestra la politica efectiva" "Politica efectiva: modo=create valid-hours=4"
  snap_after="$(snapshot "$ROOT/good")"
  if [ "$snap_before" = "$snap_after" ]; then ok "solo lectura: ni el SHA-256 ni la lista de archivos del original cambian"; else out="antes:$snap_before"$'\n'"despues:$snap_after"; bad "el original cambio"; fi
  want_sha="$(sha256sum "$ROOT/good/odc.mv.db" | cut -d' ' -f1)"; want_size="$(wc -c < "$ROOT/good/odc.mv.db" | tr -d ' ')"
  out="$(cat "$ROOT/emit.txt" 2>&1)"
  if grep -q "^odc_sha256=$want_sha$" "$ROOT/emit.txt" && grep -q "^odc_size_bytes=$want_size$" "$ROOT/emit.txt" \
     && grep -q "^count_vulnerability=3$" "$ROOT/emit.txt" && grep -q "^update_started_epoch=$JOB_START$" "$ROOT/emit.txt" \
     && grep -q "^nvd_api_last_checked=$GOOD_CHECKED$" "$ROOT/emit.txt"; then
    ok "--emit contiene el SHA-256, el tamano, los recuentos, las fechas internas y update_started_epoch reales"
  else bad "--emit"; fi
  mk kev0 5.6 "$GOOD_CHECKED" "$GOOD_MODIFIED" 1 1 0
  val create "$ROOT/kev0"; expect_ok "knownExploited vacia no bloquea (es informativa)"
}

sec_archivo() {
  mkdir -p "$ROOT/nodb"; val create "$ROOT/nodb"; expect_fail "sin odc.mv.db" "no existe odc.mv.db"
  mkdir -p "$ROOT/emptydb"; : > "$ROOT/emptydb/odc.mv.db"; val create "$ROOT/emptydb"; expect_fail "odc.mv.db vacio" "esta vacio"
  copy good trunc; head -c 14000 "$ROOT/good/odc.mv.db" > "$ROOT/trunc/odc.mv.db"; val create "$ROOT/trunc"; expect_fail "archivo truncado" "no se pudo abrir odc.mv.db en solo lectura"
  copy good hdr; head -c 8192 /dev/zero | dd of="$ROOT/hdr/odc.mv.db" conv=notrunc 2>/dev/null; val create "$ROOT/hdr"; expect_fail "cabecera de H2 destruida" "no se pudo abrir odc.mv.db en solo lectura"
  copy good junk; head -c 20000 /dev/urandom > "$ROOT/junk/odc.mv.db"; val create "$ROOT/junk"; expect_fail "contenido aleatorio" "no se pudo abrir odc.mv.db en solo lectura"
  if [ "$CAN_SYMLINK" = 1 ]; then
    mkdir -p "$ROOT/symdir"; cp "$ROOT/good/odc.mv.db" "$ROOT/symdir/real.db"; ln -s "$ROOT/symdir/real.db" "$ROOT/symdir/odc.mv.db"
    val create "$ROOT/symdir"; expect_fail "odc.mv.db como enlace simbolico" "enlace simbolico"
  else skip "odc.mv.db como enlace simbolico" "este sistema no permite crear enlaces simbolicos"; fi
  copy good stale_marker; echo "basura=1" > "$ROOT/stale_marker/nexusmarket-nvd-update.properties"
  val create "$ROOT/stale_marker"; expect_ok "create ignora un marcador previo restaurado de la cache"
}

sec_archivos_extra() {
  local d
  # Elementos que exigen revision humana (artefactos de H2 / Dependency-Check): codigo 3, nunca ACEPTADA, sin --emit.
  for d in odc.trace.db odc.update.lock odc.lock.db odc.mv.db.tempFile odc.temp.db; do
    copy good "x_$d"; echo "x" > "$ROOT/x_$d/$d"
    rm -f "$ROOT/xe.env"
    val create "$ROOT/x_$d" --emit "$ROOT/xe.env"; expect_review "create con '$d': revision humana" "$d"
    if [ ! -e "$ROOT/xe.env" ]; then ok "...y no se publica ningun --emit"; else bad "se publico --emit con '$d' presente"; fi
  done
  copy good x_verify; write_marker "$ROOT/x_verify"; echo x > "$ROOT/x_verify/odc.trace.db"
  val verify "$ROOT/x_verify"; expect_review "verify con odc.trace.db: revision humana" "odc.trace.db"
  expect_has "...el aviso explica por que" "no deberia quedar tras una actualizacion limpia"
  # Un fallo real tiene prioridad (codigo 1) y sigue listando lo que hay que revisar.
  mk x_nomark 5.6 ABSENT ABSENT 3 2 1; echo x > "$ROOT/x_nomark/odc.trace.db"
  val create "$ROOT/x_nomark"; expect_fail "base rechazada con odc.trace.db: manda el rechazo (1)" "falta nvd.api.last.checked"
  expect_has "...y el archivo sigue listado para revision" "REVISION HUMANA: 'odc.trace.db'"
  # No reconocidos (incluidos ocultos y directorios): se listan, no se ignoran, y no bloquean (no se ha verificado que sean danos).
  copy good x_hidden; echo x > "$ROOT/x_hidden/.oculto"; echo x > "$ROOT/x_hidden/notas.txt"; mkdir -p "$ROOT/x_hidden/subdir"
  val create "$ROOT/x_hidden"; expect_ok "archivos no reconocidos (oculto, visible, directorio): no bloquean"
  expect_has "...el oculto se lista" "no reconocido en el directorio de datos (no se valida; no se ha verificado que sea inocuo): .oculto"
  expect_has "...el visible se lista" "inocuo): notas.txt"
  expect_has "...el directorio se lista" "subdir/ (directorio)"
  expect_not_has "...y no se piden revision humana" "REVISION HUMANA REQUERIDA"
  # El marcador del proyecto es un archivo esperado: no se avisa de el.
  copy good x_marker; write_marker "$ROOT/x_marker"; val verify "$ROOT/x_marker"
  expect_not_has "el marcador del proyecto no se lista como no reconocido" "no reconocido"
  if [ "$CAN_SYMLINK" = 1 ]; then
    copy good x_link; echo x > "$ROOT/x_link/real.txt"; ln -s "$ROOT/x_link/real.txt" "$ROOT/x_link/enlace"
    val create "$ROOT/x_link"; expect_review "enlace simbolico dentro del directorio de datos" "enlace (enlace simbolico)"
  else skip "enlace simbolico dentro del directorio de datos" "este sistema no permite crear enlaces simbolicos"; fi
}

sec_internos() {
  mk nomark 5.6 ABSENT ABSENT 3 2 1;                 val create "$ROOT/nomark";  expect_fail "sin nvd.api.last.* (descarga interrumpida: hay registros pero faltan las marcas)" "falta nvd.api.last.checked"
  expect_has "...y explica que la actualizacion no llego a completarse" "no llego a completarse"
  expect_has "...y tambien falta last.modified" "falta nvd.api.last.modified"
  mk nochk 5.6 ABSENT "$GOOD_MODIFIED" 3 2 1;        val create "$ROOT/nochk";   expect_fail "falta nvd.api.last.checked" "falta nvd.api.last.checked"
  mk nomod 5.6 "$GOOD_CHECKED" ABSENT 3 2 1;         val create "$ROOT/nomod";   expect_fail "falta nvd.api.last.modified" "falta nvd.api.last.modified"
  local i=0 bad_date
  for bad_date in "abc" "" "2026-13-45T00:00:00Z" "2026-10-29" "2026-10-29T10:00:00" "2026-10-29T10:00:00Zbasura" "1970-01-01T00:00:00Z" "29/10/2026"; do
    i=$((i + 1))
    mk "bad_c$i" 5.6 "$bad_date" "$GOOD_MODIFIED" 3 2 1; val create "$ROOT/bad_c$i"; expect_fail "last.checked invalido: '$bad_date'" "nvd.api.last.checked no es una fecha valida"
  done
  mk bad_m 5.6 "$GOOD_CHECKED" "no-es-fecha" 3 2 1;  val create "$ROOT/bad_m";  expect_fail "last.modified invalido" "nvd.api.last.modified no es una fecha valida"
  mk fut_c 5.6 "$(iso $((NOW + 86400)))" "$GOOD_MODIFIED" 3 2 1; val create "$ROOT/fut_c"; expect_fail "last.checked en el futuro (+1 dia)" "nvd.api.last.checked esta en el futuro"
  mk fut_m 5.6 "$GOOD_CHECKED" "$(iso $((NOW + 86400)))" 3 2 1; val create "$ROOT/fut_m"; expect_fail "last.modified en el futuro (+1 dia)" "nvd.api.last.modified esta en el futuro"
  mk skew_ok 5.6 "$(iso $((NOW + 300)))" "$GOOD_MODIFIED" 3 2 1; val create "$ROOT/skew_ok"; expect_ok "limite: last.checked = ahora + 300 s (tolerancia de reloj)"
  mk skew_ko 5.6 "$(iso $((NOW + 301)))" "$GOOD_MODIFIED" 3 2 1; val create "$ROOT/skew_ko"; expect_fail "limite: last.checked = ahora + 301 s" "nvd.api.last.checked esta en el futuro"
  mk inc_ok 5.6 "$GOOD_CHECKED" "$(iso $((NOW - 60 + 300)))" 3 2 1; val create "$ROOT/inc_ok"; expect_ok "limite: last.modified = last.checked + 300 s"
  mk inc_ko 5.6 "$GOOD_CHECKED" "$(iso $((NOW - 60 + 301)))" 3 2 1; val create "$ROOT/inc_ko"; expect_fail "limite: last.modified = last.checked + 301 s (incoherente)" "es posterior a nvd.api.last.checked"
  mk off_ok 5.6 "$(date -u -d "@$((NOW - 60 + 3600))" +%Y-%m-%dT%H:%M:%S)+01" "$GOOD_MODIFIED" 3 2 1; val create "$ROOT/off_ok"; expect_ok "fecha con desplazamiento +01 (formato X de Dependency-Check)"
  # Politica de actualizacion con una ventana de 4 h: una actualizacion omitida por la ventana se acepta (y se avisa).
  mk st_ok 5.6 "$(iso $((JOB_START - 4 * 3600)))" "$(iso $((JOB_START - 5 * 3600)))" 3 2 1; val create "$ROOT/st_ok"; expect_ok "limite (valid-hours=4): last.checked = inicio - 4 h"
  expect_has "...y avisa de que una actualizacion omitida se acepta con esa ventana" "OMITIDA por la ventana"
  mk st_ko 5.6 "$(iso $((JOB_START - 4 * 3600 - 1)))" "$(iso $((JOB_START - 5 * 3600)))" 3 2 1; val create "$ROOT/st_ko"; expect_fail "last.checked anterior a inicio - 4 h: la actualizacion no se ejecuto (base antigua, log 'limpio')" "anterior al inicio de esta ejecucion"
  mk st_old 5.6 "$(iso $((NOW - 30 * 86400)))" "$(iso $((NOW - 31 * 86400)))" 3 2 1; val create "$ROOT/st_old"; expect_fail "base de hace 30 dias" "anterior al inicio de esta ejecucion"
  # Politica estricta (valid-hours=0): "esta ejecucion actualizo".
  mk st0_ok 5.6 "$(iso "$JOB_START")" "$(iso $((JOB_START - 3600)))" 3 2 1; val create "$ROOT/st0_ok" --valid-hours 0; expect_ok "valid-hours=0: last.checked = inicio de la ejecucion"
  expect_has "...y declara la politica estricta" "valid-hours=0"
  expect_not_has "...sin el aviso de ventana" "OMITIDA por la ventana"
  mk st0_ko 5.6 "$(iso $((JOB_START - 1)))" "$(iso $((JOB_START - 3600)))" 3 2 1; val create "$ROOT/st0_ko" --valid-hours 0; expect_fail "valid-hours=0: last.checked 1 s antes del inicio (base anterior, actualizacion omitida)" "la actualizacion NO se ejecuto en esta ejecucion"
  val create "$ROOT/st0_ko"; expect_ok "...la misma base se acepta con una ventana de 4 h (por eso se exige elegir la politica)"
  # Coherencia del inicio declarado.
  val create "$ROOT/good" --job-start-epoch $((NOW + 1000)); expect_fail "inicio de la actualizacion en el futuro" "--job-start-epoch esta en el futuro"
  val create "$ROOT/good" --job-start-epoch $((NOW + 300)); expect_ok "limite: inicio = ahora + 300 s (tolerancia de reloj)"
  val create "$ROOT/good" --job-start-epoch $((NOW - MAX_JOB - 300)); expect_ok "limite: inicio = ahora - 21600 s - 300 s"
  val create "$ROOT/good" --job-start-epoch $((NOW - MAX_JOB - 301)); expect_fail "inicio de hace mas que un job de GitHub (21600 s + 300 s)" "es anterior en mas de $MAX_JOB s"
  raw --mode create --dir "$ROOT/good" --now-epoch "$NOW" --valid-hours 4 --job-start-epoch abc; expect_usage "inicio no numerico" "--job-start-epoch"
}

sec_esquema() {
  local ver
  for ver in 5.5 5.7 ABSENT; do mk "ver_$ver" "$ver" "$GOOD_CHECKED" "$GOOD_MODIFIED" 3 2 1; val create "$ROOT/ver_$ver"; expect_fail "properties.version = $ver" "properties.version es '$ver', se esperaba '5.6'"; done
  mk zero_all 5.6 "$GOOD_CHECKED" "$GOOD_MODIFIED" 0 0 0; val create "$ROOT/zero_all"; expect_fail "marcadores validos pero sin registros" "la tabla vulnerability esta vacia"
  expect_has "...ni registros CPE" "la tabla cpeEntry esta vacia"
  mk zero_cpe 5.6 "$GOOD_CHECKED" "$GOOD_MODIFIED" 3 0 1;  val create "$ROOT/zero_cpe"; expect_fail "cpeEntry vacia" "la tabla cpeEntry esta vacia"
  mk zero_vul 5.6 "$GOOD_CHECKED" "$GOOD_MODIFIED" 0 2 1;  val create "$ROOT/zero_vul"; expect_fail "vulnerability vacia" "la tabla vulnerability esta vacia"
}

sec_minimos() {
  local lo="--min-vulnerability 4"
  # Limite exacto: la fixture "good" tiene 3 / 2 / 1 filas.
  val create "$ROOT/good" --min-vulnerability 3 --min-cpe-entry 2 --min-known-exploited 1
  expect_ok "limite: recuentos iguales a los minimos (3 / 2 / 1): se acepta"
  expect_has "...la politica efectiva muestra los minimos" "Minimos de registros (0 = sin minimo; vulnerability y cpeEntry exigen siempre >= 1): vulnerability=3 cpeEntry=2 knownExploited=1"
  expect_has "...y el aviso final explica que los minimos solo detectan una descarga truncada" "Los minimos de registros solo detectan una descarga truncada"
  val create "$ROOT/good" --min-vulnerability 4
  expect_fail "vulnerability un registro por debajo del minimo" "la tabla vulnerability tiene 3 registros, por debajo del minimo exigido (4)"
  val create "$ROOT/good" --min-cpe-entry 3
  expect_fail "cpeEntry un registro por debajo del minimo" "la tabla cpeEntry tiene 2 registros, por debajo del minimo exigido (3)"
  val create "$ROOT/good" --min-known-exploited 2
  expect_fail "knownExploited un registro por debajo del minimo" "la tabla knownExploited tiene 1 registros, por debajo del minimo exigido (2)"
  expect_has "...el rechazo explica la causa probable" "descarga incompleta o truncada"
  val create "$ROOT/good" --min-vulnerability 4 --min-cpe-entry 3 --min-known-exploited 2
  expect_fail "las tres tablas por debajo: se informan las tres" "la tabla vulnerability tiene 3"
  expect_has "...cpeEntry tambien" "la tabla cpeEntry tiene 2"
  expect_has "...knownExploited tambien" "la tabla knownExploited tiene 1"
  # Sin minimos (valor por defecto o 0) el comportamiento es el de siempre.
  val create "$ROOT/good"; expect_ok "sin minimos: se acepta como antes"
  expect_has "...y el aviso dice que no se exigio ningun minimo" "No se exigio ningun minimo de registros"
  val create "$ROOT/good" --min-vulnerability 0 --min-cpe-entry 0 --min-known-exploited 0; expect_ok "minimos explicitos a 0: sin minimo"
  mk kev0m 5.6 "$GOOD_CHECKED" "$GOOD_MODIFIED" 1 1 0
  val create "$ROOT/kev0m"; expect_ok "knownExploited = 0 sin minimo: sigue sin bloquear"
  val create "$ROOT/kev0m" --min-known-exploited 1; expect_fail "knownExploited = 0 con minimo 1: se rechaza" "la tabla knownExploited tiene 0 registros, por debajo del minimo exigido (1)"
  # Una tabla vacia sigue rechazandose con su motivo propio, aunque haya minimo.
  mk zero_vul_m 5.6 "$GOOD_CHECKED" "$GOOD_MODIFIED" 0 2 1
  val create "$ROOT/zero_vul_m" --min-vulnerability 5; expect_fail "vulnerability vacia con minimo: manda el motivo de tabla vacia" "la tabla vulnerability esta vacia"
  # Tambien en verify, y con uso operativo (sin modo de pruebas).
  copy good v_min; write_marker "$ROOT/v_min"
  val verify "$ROOT/v_min" --min-vulnerability 3 --min-cpe-entry 2 --min-known-exploited 1; expect_ok "verify: recuentos iguales a los minimos"
  val verify "$ROOT/v_min" $lo; expect_fail "verify: vulnerability por debajo del minimo" "la tabla vulnerability tiene 3 registros, por debajo del minimo exigido (4)"
  valnt create "$ROOT/good" $lo; expect_fail "uso operativo (sin modo de pruebas): el minimo tambien rechaza" "por debajo del minimo exigido (4)"
  # El minimo no cambia lo que --emit publica ni se publica nada si se rechaza.
  rm -f "$ROOT/emit_min.txt"
  val create "$ROOT/good" $lo --emit "$ROOT/emit_min.txt"
  if [ "$rc" -eq 1 ] && [ ! -e "$ROOT/emit_min.txt" ]; then ok "rechazada por minimo: no se publica ningun --emit"; else bad "--emit tras rechazo por minimo"; fi
  val create "$ROOT/good" --min-vulnerability 3 --emit "$ROOT/emit_min.txt"
  if [ "$rc" -eq 0 ] && grep -q "^count_vulnerability=3$" "$ROOT/emit_min.txt" && [ "$(wc -l < "$ROOT/emit_min.txt" | tr -d ' ')" = 8 ]; then ok "aceptada con minimo: --emit sigue teniendo las 8 claves del contrato"; else bad "--emit con minimo"; fi
  # Argumentos invalidos: codigo 2, nunca "valida".
  local opt
  for opt in --min-vulnerability --min-cpe-entry --min-known-exploited; do
    raw --mode create --dir "$ROOT/good" --now-epoch "$NOW" --valid-hours 4 --job-start-epoch "$JOB_START" "$opt" abc; expect_usage "$opt no numerico" "$opt debe ser un entero"
    raw --mode create --dir "$ROOT/good" --now-epoch "$NOW" --valid-hours 4 --job-start-epoch "$JOB_START" "$opt" -5; expect_usage "$opt negativo" "$opt debe ser un entero"
    raw --mode create --dir "$ROOT/good" --now-epoch "$NOW" --valid-hours 4 --job-start-epoch "$JOB_START" "$opt" 03; expect_usage "$opt con cero a la izquierda (octal en bash)" "$opt debe ser un entero"
    raw --mode create --dir "$ROOT/good" --now-epoch "$NOW" --valid-hours 4 --job-start-epoch "$JOB_START" "$opt" 1000000000; expect_usage "$opt de mas de 9 cifras" "$opt debe ser un entero"
    raw --mode create --dir "$ROOT/good" --now-epoch "$NOW" --valid-hours 4 --job-start-epoch "$JOB_START" "$opt" ""; expect_usage "$opt vacio" "$opt debe ser un entero"
    raw --mode create --dir "$ROOT/good" --now-epoch "$NOW" --valid-hours 4 --job-start-epoch "$JOB_START" "$opt"; expect_usage "$opt sin valor al final" "la opcion $opt requiere un valor"
  done
  # Los valores reales de la politica de CI (fase 2) como argumentos validos: la fixture sintetica no los alcanza.
  val create "$ROOT/good" --min-vulnerability 200000 --min-cpe-entry 215000 --min-known-exploited 1400
  expect_fail "los minimos de CI (200000 / 215000 / 1400) rechazan una base sintetica diminuta" "por debajo del minimo exigido (200000)"
  # Un valor grande valido (9 cifras) no desborda la aritmetica de bash.
  val create "$ROOT/good" --min-vulnerability 999999999; expect_fail "minimo de 9 cifras: se compara sin desbordar" "por debajo del minimo exigido (999999999)"
}

sec_plugin_propios() {
  local d f
  # Archivos que Dependency-Check crea en su directorio de datos: se reconocen por nombre y tipo, y se informa de ello.
  copy good p_own
  mkdir -p "$ROOT/p_own/cache"; echo x > "$ROOT/p_own/cache/dato"
  for f in jsrepository.json jsrepository.json.properties publishedSuppressions.xml publishedSuppressions.xml.properties; do echo x > "$ROOT/p_own/$f"; done
  val create "$ROOT/p_own"; expect_ok "archivos propios de Dependency-Check: se aceptan"
  expect_has "...se informa de que se reconocen solo por nombre" "reconocidos solo por nombre (su contenido NO se valida): cache/ jsrepository.json jsrepository.json.properties publishedSuppressions.xml publishedSuppressions.xml.properties"
  expect_not_has "...y ya no se avisan como no reconocidos" "inocuo): "
  expect_not_has "...ni se piden revision humana" "REVISION HUMANA"
  # Un archivo desconocido junto a ellos sigue avisandose.
  echo x > "$ROOT/p_own/notas.txt"
  val create "$ROOT/p_own"; expect_ok "propios + uno desconocido: no bloquea"
  expect_has "...el desconocido sigue listado" "inocuo): notas.txt"
  expect_not_has "...los propios no se listan como desconocidos" "inocuo): jsrepository"
  # Nombres parecidos o de otro tipo NO se reconocen.
  copy good p_fake
  echo x > "$ROOT/p_fake/cache"                                            # "cache" como archivo, no directorio
  mkdir -p "$ROOT/p_fake/jsrepository.json"                                # directorio con nombre de archivo
  echo x > "$ROOT/p_fake/jsrepository.json.bak"
  echo x > "$ROOT/p_fake/publishedSuppressions.xml.properties.old"
  # "Cache" solo se distingue de "cache" en sistemas de archivos que diferencian mayusculas (no en Windows ni en macOS por defecto).
  CASE_SENSITIVE=0
  echo x > "$ROOT/p_fake/Cache"; [ "$(ls "$ROOT/p_fake" | grep -c -x -i cache)" = 2 ] && CASE_SENSITIVE=1
  mkdir -p "$ROOT/p_fake/cache2"
  val create "$ROOT/p_fake"; expect_ok "nombres parecidos o de otro tipo: no bloquean, pero se avisan"
  expect_has "...'cache' como archivo se avisa" "inocuo): cache"
  expect_has "...un directorio llamado jsrepository.json se avisa" "jsrepository.json/ (directorio)"
  expect_has "...jsrepository.json.bak se avisa" "inocuo): jsrepository.json.bak"
  expect_has "...una variante .old se avisa" "inocuo): publishedSuppressions.xml.properties.old"
  if [ "$CASE_SENSITIVE" = 1 ]; then expect_has "...'Cache' (mayuscula) se avisa" "inocuo): Cache"
  else skip "'Cache' (mayuscula) se avisa" "este sistema de archivos no distingue mayusculas"; fi
  expect_has "...cache2/ se avisa" "cache2/ (directorio)"
  expect_not_has "...y no se informa de ningun archivo propio reconocido" "reconocidos solo por nombre"
  if [ "$CAN_SYMLINK" = 1 ]; then
    copy good p_lnk; echo x > "$ROOT/p_lnk/real.txt"
    ln -s "$ROOT/p_lnk/real.txt" "$ROOT/p_lnk/jsrepository.json"
    val create "$ROOT/p_lnk"; expect_review "enlace simbolico con nombre de archivo propio: revision humana, no se reconoce" "jsrepository.json (enlace simbolico)"
    copy good p_lnkd; mkdir -p "$ROOT/p_lnkd/destino"; ln -s "$ROOT/p_lnkd/destino" "$ROOT/p_lnkd/cache"
    val create "$ROOT/p_lnkd"; expect_review "enlace simbolico llamado cache: revision humana, no se reconoce" "cache (enlace simbolico)"
  else skip "enlaces simbolicos con nombre de archivo propio" "este sistema no permite crear enlaces simbolicos"; fi
  # verify con archivos propios (ejecucion real del job de CI).
  copy good p_ver; write_marker "$ROOT/p_ver"; mkdir -p "$ROOT/p_ver/cache"; echo x > "$ROOT/p_ver/publishedSuppressions.xml"
  val verify "$ROOT/p_ver"; expect_ok "verify con archivos propios de Dependency-Check: se acepta"
  expect_has "...y los reconoce por nombre" "reconocidos solo por nombre (su contenido NO se valida): cache/ publishedSuppressions.xml"
}

sec_entorno() {
  raw --mode create --dir "$ROOT/good" --now-epoch "$NOW" --valid-hours 4;                                              expect_usage "create sin --job-start-epoch" "--job-start-epoch"
  raw --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --h2-jar "$ROOT/no-existe.jar" --core-jar "$CORE_JAR" --java "$JAVA_BIN"; expect_usage "jar de H2 inexistente" "no existe el jar de H2"
  raw --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --h2-jar "$H2_JAR" --core-jar "$ROOT/no-existe.jar" --java "$JAVA_BIN"; expect_usage "jar core inexistente" "no existe el jar dependency-check-core"
  raw --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --h2-jar "$H2_JAR" --core-jar "$H2_JAR" --java "$JAVA_BIN";            expect_usage "jar core sin dependencycheck.properties (classpath incorrecto)" "classpath"
  raw --mode otro --dir "$ROOT/good" --valid-hours 4;                                                                    expect_usage "modo desconocido"
  raw --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --java "$ROOT/no-existe-java" --h2-jar "$H2_JAR" --core-jar "$CORE_JAR"; expect_usage "Java inexistente" "no se encuentra Java"
  val create "$ROOT/good"; expect_has "la salida declara el entorno usado (java y jars)" "Entorno: java="
  # Una opcion sin valor al final no debe colgar el script (shift 2 fallido).
  out="$(timeout 20 bash "$VALIDATOR" --mode create --dir "$ROOT/good" --valid-hours 2>&1)"; rc=$?
  expect_usage "opcion sin valor al final (no se cuelga)" "requiere un valor"
  out="$(timeout 20 bash "$VALIDATOR" --dir 2>&1)"; rc=$?
  expect_usage "--dir sin valor al final (no se cuelga)" "requiere un valor"
}

sec_politica() {
  rawnt --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --java "$JAVA_BIN" --h2-jar "$H2_JAR" --core-jar "$CORE_JAR"
  expect_usage "create sin --valid-hours: no hay valor silencioso" "falta --valid-hours"
  expect_not_has "...ni se valida nada" "Resultado: ACEPTADA"
  rawnt --mode verify --dir "$ROOT/good" --java "$JAVA_BIN" --h2-jar "$H2_JAR" --core-jar "$CORE_JAR"; expect_usage "verify sin --valid-hours" "falta --valid-hours"
  rawnt --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours ""; expect_usage "--valid-hours vacio" "falta --valid-hours"
  local v
  for v in abc -1 1.5 08 00 10000 "4 "; do
    rawnt --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours "$v" --java "$JAVA_BIN" --h2-jar "$H2_JAR" --core-jar "$CORE_JAR"
    expect_usage "--valid-hours '$v' invalido" "--valid-hours debe ser un entero"
  done
  rawnt --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --max-age-days 08; expect_usage "--max-age-days con cero a la izquierda" "--max-age-days debe ser un entero"
  rawnt --mode create --dir "$ROOT/good" --job-start-epoch 0 --valid-hours 4; expect_usage "--job-start-epoch = 0" "el modo create exige --job-start-epoch"
  # 0 = estricta: sin aviso. >0: aviso explicito de que no demuestra que la ejecucion actual actualizo.
  mk pol0 5.6 "$(iso "$JOB_START")" "$(iso $((JOB_START - 3600)))" 3 2 1
  val create "$ROOT/pol0" --valid-hours 0; expect_ok "valid-hours=0 aceptado"
  expect_not_has "...sin advertencia de ventana" "NO demuestra que la ejecucion actual haya actualizado la base"
  expect_has "...y la politica efectiva lo muestra" "Politica efectiva: modo=create valid-hours=0"
  val create "$ROOT/good" --valid-hours 4; expect_ok "valid-hours=4 aceptado"
  expect_has "...pero advierte que NO demuestra que la ejecucion actual actualizo la base" "NO demuestra que la ejecucion actual haya actualizado la base"
  copy good pol_v; write_marker "$ROOT/pol_v"
  val verify "$ROOT/pol_v" --valid-hours 4; expect_ok "verify con valid-hours=4 aceptado"
  expect_has "...tambien advierte" "NO demuestra que la ejecucion actual haya actualizado la base"
  val verify "$ROOT/pol_v" --valid-hours 0; expect_ok "verify con valid-hours=0"
  expect_not_has "...sin advertencia" "NO demuestra que la ejecucion actual haya actualizado la base"
}

sec_parametros_prueba() {
  local p
  # Rechazo sin NVD_VALIDATOR_TEST=1.
  rawnt --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --now-epoch "$NOW"; expect_usage "--now-epoch sin modo de pruebas: rechazado" "--now-epoch es un parametro exclusivo de pruebas"
  rawnt --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --max-job-seconds 99999999; expect_usage "--max-job-seconds sin modo de pruebas" "--max-job-seconds es un parametro exclusivo de pruebas"
  rawnt --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --expected-schema 5.5; expect_usage "--expected-schema sin modo de pruebas" "--expected-schema es un parametro exclusivo de pruebas"
  expect_has "...y el mensaje aclara que NVD_VALIDATOR_TEST no es una autenticacion" "no una autenticacion"
  for p in 0 "" no yes; do
    out="$(NVD_VALIDATOR_TEST="$p" bash "$VALIDATOR" --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --now-epoch "$NOW" 2>&1)"; rc=$?
    expect_usage "NVD_VALIDATOR_TEST='$p' no activa el modo de pruebas (solo '1')" "exclusivo de pruebas"
  done
  # Con NVD_VALIDATOR_TEST=1 se admiten y el resultado lo declara.
  val create "$ROOT/good"; expect_ok "con NVD_VALIDATOR_TEST=1 y --now-epoch: aceptado"
  expect_has "...y la salida declara el MODO DE PRUEBAS" "MODO DE PRUEBAS: parametros exclusivos de pruebas en uso"
  expect_has "...y el resultado final avisa de que no sirve para aprobar una base real" "NO sirve para aprobar una base real"
  mk ver_5.5 5.5 "$GOOD_CHECKED" "$GOOD_MODIFIED" 3 2 1
  val create "$ROOT/ver_5.5" --expected-schema 5.5; expect_ok "con el modo de pruebas, --expected-schema 5.5 acepta un esquema 5.5"
  rawnt --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --java "$JAVA_BIN" --h2-jar "$H2_JAR" --core-jar "$CORE_JAR"
  expect_ok "uso operativo (sin parametros de pruebas ni NVD_VALIDATOR_TEST, reloj real)"
  expect_not_has "...sin aviso de modo de pruebas" "MODO DE PRUEBAS"
  copy good op_v; write_marker "$ROOT/op_v"
  valnt verify "$ROOT/op_v"; expect_ok "uso operativo de verify (reloj real)"
  expect_not_has "...sin aviso de modo de pruebas" "MODO DE PRUEBAS"
  # Los parametros operativos siguen funcionando sin el modo de pruebas.
  valnt verify "$ROOT/op_v" --max-age-days 30 --expected-version 12.2.2; expect_ok "parametros operativos (--max-age-days, --expected-version) sin modo de pruebas"
  expect_has "...y la politica efectiva los refleja" "max-age-days=30"
}

sec_integridad() {
  # sha256sum y wc fallan o devuelven valores no validos: nunca se emite ni se publica un marcador.
  copy good i_data; local d="$ROOT/emit_i"; mkdir -p "$d"
  local n label
  for n in 1 2 3; do
    case "$n" in 1) label="de odc.mv.db";; 2) label="de la copia";; 3) label="tras la validacion";; esac
    mkshim_nth "sha_fail$n" sha256sum "$REAL_SHA" "$n" 'exit 1'
    VPATH="$ROOT/shims/sha_fail$n" val create "$ROOT/i_data" --emit "$d/e.env"; expect_usage "sha256sum falla en la llamada $n ($label)" "SHA-256"
    if [ -z "$(ls -A "$d")" ]; then ok "...no se publica ningun marcador"; else out="$(ls -A "$d")"; bad "residuos tras fallo de sha256sum"; fi
  done
  mkshim_nth sha_garbage sha256sum "$REAL_SHA" 1 'echo "zzzz  x"; exit 0'
  VPATH="$ROOT/shims/sha_garbage" val create "$ROOT/i_data" --emit "$d/e.env"; expect_usage "sha256sum devuelve basura" "SHA-256"
  mkshim_nth sha_short sha256sum "$REAL_SHA" 1 'echo "abc123  x"; exit 0'
  VPATH="$ROOT/shims/sha_short" val create "$ROOT/i_data" --emit "$d/e.env"; expect_usage "sha256sum devuelve menos de 64 hexadecimales" "SHA-256"
  mkshim_nth sha_empty sha256sum "$REAL_SHA" 1 'exit 0'
  VPATH="$ROOT/shims/sha_empty" val create "$ROOT/i_data" --emit "$d/e.env"; expect_usage "sha256sum no devuelve nada (codigo 0)" "SHA-256"
  mkshim_nth sha_upper sha256sum "$REAL_SHA" 1 'echo "$(printf "A%.0s" $(seq 1 64))  x"; exit 0'
  VPATH="$ROOT/shims/sha_upper" val create "$ROOT/i_data" --emit "$d/e.env"; expect_usage "sha256sum devuelve 64 caracteres que no son minusculas hexadecimales" "SHA-256"
  mkshim_nth sha_long sha256sum "$REAL_SHA" 1 'echo "$(printf "a%.0s" $(seq 1 65))  x"; exit 0'
  VPATH="$ROOT/shims/sha_long" val create "$ROOT/i_data" --emit "$d/e.env"; expect_usage "sha256sum devuelve 65 hexadecimales" "SHA-256"
  mkshim_nth sha_bs sha256sum "$REAL_SHA" all 'o="$("$REAL" "$@")"; printf "%s%s\n" "\\" "$o"; exit 0'
  VPATH="$ROOT/shims/sha_bs" val create "$ROOT/i_data"; expect_ok "sha256sum con '\\' inicial (rutas con barras invertidas) se acepta tras normalizarlo"
  mkshim_nth wc_fail wc "$REAL_WC" 1 'exit 1'
  VPATH="$ROOT/shims/wc_fail" val create "$ROOT/i_data" --emit "$d/e.env"; expect_usage "wc falla" "tamano de odc.mv.db"
  local w
  for w in abc -5 "" "12 34" "1.5"; do
    mkshim_nth wc_bad wc "$REAL_WC" 1 'printf "%s\n" "'"$w"'"; exit 0'
    VPATH="$ROOT/shims/wc_bad" val create "$ROOT/i_data" --emit "$d/e.env"; expect_usage "wc devuelve '$w'" "tamano de odc.mv.db"
  done
  if [ -z "$(ls -A "$d")" ]; then ok "...ninguno de los fallos de wc publica un marcador"; else out="$(ls -A "$d")"; bad "residuos tras fallo de wc"; fi
  mkshim_nth wc_pad wc "$REAL_WC" all 'o="$("$REAL" "$@")"; echo "   $o  "; exit 0'
  VPATH="$ROOT/shims/wc_pad" val create "$ROOT/i_data"; expect_ok "wc con espacios alrededor (formato de otros sistemas) se acepta"
  # verify tambien comprueba los errores.
  copy good i_ver; write_marker "$ROOT/i_ver"
  mkshim_nth sha_fail1 sha256sum "$REAL_SHA" 1 'exit 1'   # se recrean: reinician el contador de llamadas
  mkshim_nth wc_fail wc "$REAL_WC" 1 'exit 1'
  VPATH="$ROOT/shims/sha_fail1" val verify "$ROOT/i_ver"; expect_usage "verify: sha256sum falla" "SHA-256"
  VPATH="$ROOT/shims/wc_fail" val verify "$ROOT/i_ver"; expect_usage "verify: wc falla" "tamano de odc.mv.db"
  # Una copia distinta del original (cp 'termina bien' pero escribe otra cosa) no se valida.
  mkshim cp_other cp 'shift; printf "otra cosa" > "$2"; exit 0'
  VPATH="$ROOT/shims/cp_other" val create "$ROOT/i_data"; expect_usage "la copia difiere del original" "difiere del original"
}

sec_orden() {
  # El marcador y el inicio se validan ANTES de abrir H2: con un Java inexistente, un marcador inconsistente se rechaza (1)
  # sin llegar a pedir Java (2); uno consistente si llega y falla por el entorno (2).
  copy good o_sha; write_marker "$ROOT/o_sha" "odc_sha256=$(zeros64 0)"
  val verify "$ROOT/o_sha" --java "$ROOT/no-existe-java"; expect_fail "marcador con SHA-256 distinto: se rechaza sin abrir H2 (Java inexistente no se llega a pedir)" "el SHA-256 de odc.mv.db no coincide"
  expect_has "...y lo declara" "No se abrio la base con H2"
  copy good o_sz; write_marker "$ROOT/o_sz" odc_size_bytes=1
  val verify "$ROOT/o_sz" --java "$ROOT/no-existe-java"; expect_fail "tamano distinto: sin abrir H2" "el tamano de odc.mv.db"
  copy good o_old; write_marker "$ROOT/o_old" "completed_epoch=$((NOW - 9 * 86400))" "completed_at=$(iso $((NOW - 9 * 86400)))" "update_started_epoch=$((NOW - 9 * 86400 - 100))"
  val verify "$ROOT/o_old" --java "$ROOT/no-existe-java"; expect_fail "marcador antiguo: sin abrir H2" "la base tiene 9 dias"
  copy good o_unk; write_marker "$ROOT/o_unk"; echo "extra=1" >> "$ROOT/o_unk/nexusmarket-nvd-update.properties"
  val verify "$ROOT/o_unk" --java "$ROOT/no-existe-java"; expect_fail "clave desconocida: sin abrir H2" "clave desconocida en el marcador: extra"
  copy good o_ok; write_marker "$ROOT/o_ok"
  val verify "$ROOT/o_ok" --java "$ROOT/no-existe-java"; expect_usage "marcador consistente: SI se llega a pedir Java (control)" "no se encuentra Java"
  val create "$ROOT/good" --job-start-epoch $((NOW + 1000)) --java "$ROOT/no-existe-java"; expect_fail "create: inicio futuro, sin abrir H2" "--job-start-epoch esta en el futuro"
  expect_has "...y lo declara" "No se abrio la base con H2"
  copy good o_x; write_marker "$ROOT/o_x" "nvd_api_last_checked=$(iso $((NOW - 90)))"
  val verify "$ROOT/o_x"; expect_fail "discrepancia marcador/base (necesita H2): si se abre" "nvd_api_last_checked del marcador"
  expect_not_has "...y no dice que no se abrio" "No se abrio la base con H2"
  echo "  (esto NO resuelve la autenticidad: un marcador forjado con SHA-256 y tamano coincidentes llega a abrirse con H2)"
}

sec_emit() {
  local d="$ROOT/emitdir"; mkdir -p "$d"
  echo "STALE" > "$d/e.env"
  val create "$ROOT/good" --emit "$d/e.env"; expect_ok "emit: validacion correcta con un archivo previo"
  if ! grep -q STALE "$d/e.env" && grep -q "^odc_sha256=" "$d/e.env"; then ok "...el archivo previo se sustituye por el resultado actual"; else out="$(cat "$d/e.env")"; bad "archivo previo no sustituido"; fi
  if [ "$(ls -A "$d")" = "e.env" ]; then ok "...sin archivos temporales residuales"; else out="$(ls -A "$d")"; bad "residuos en el directorio de --emit"; fi
  echo "STALE" > "$d/e.env"
  mk emit_nomark 5.6 ABSENT ABSENT 3 2 1
  val create "$ROOT/emit_nomark" --emit "$d/e.env"; expect_fail "emit: la validacion nueva falla" "falta nvd.api.last.checked"
  if [ -z "$(ls -A "$d")" ]; then ok "...no queda ningun marcador antiguo ni temporal (el archivo previo se elimino)"; else out="$(ls -A "$d"; cat "$d/e.env" 2>/dev/null)"; bad "queda un archivo --emit tras un fallo"; fi
  echo "STALE" > "$d/e.env"
  raw --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --now-epoch "$NOW" --valid-hours 4 --h2-jar "$ROOT/no-existe.jar" --core-jar "$CORE_JAR" --java "$JAVA_BIN" --emit "$d/e.env"
  expect_usage "emit: fallo de entorno (codigo 2)"
  if [ ! -e "$d/e.env" ]; then ok "...tambien elimina el archivo previo"; else bad "quedo el archivo previo tras un fallo de entorno"; fi
  mk emit_trunc 5.6 "$GOOD_CHECKED" "$GOOD_MODIFIED" 3 2 1; head -c 14000 "$ROOT/emit_trunc/odc.mv.db" > "$ROOT/emit_trunc/x" && mv "$ROOT/emit_trunc/x" "$ROOT/emit_trunc/odc.mv.db"
  echo "STALE" > "$d/e.env"; val create "$ROOT/emit_trunc" --emit "$d/e.env"; expect_fail "emit: base truncada" "no se pudo abrir"
  if [ ! -e "$d/e.env" ]; then ok "...no queda ningun marcador"; else bad "quedo un marcador para una base truncada"; fi

  copy good e_in; mkdir -p "$ROOT/e_in/sub"; local before; before="$(snapshot "$ROOT/e_in")"
  val create "$ROOT/e_in" --emit "$ROOT/e_in/out.env"; expect_usage "emit dentro del directorio de datos" "no puede estar dentro del directorio de datos"
  val create "$ROOT/e_in" --emit "$ROOT/e_in/odc.mv.db"; expect_usage "emit apuntando a odc.mv.db (no debe borrar la base)" "no puede estar dentro del directorio de datos"
  val create "$ROOT/e_in" --emit "$ROOT/e_in/sub/../out.env"; expect_usage "emit dentro del directorio mediante '..'" "no puede estar dentro del directorio de datos"
  if [ "$before" = "$(snapshot "$ROOT/e_in")" ]; then ok "...el directorio de datos queda intacto"; else out="$(snapshot "$ROOT/e_in")"; bad "el directorio de datos cambio"; fi
  val create "$ROOT/good" --emit "$ROOT/no-existe/e.env"; expect_usage "emit en un directorio que no existe" "el directorio de --emit no existe"
  mkdir -p "$ROOT/e_dir"; val create "$ROOT/good" --emit "$ROOT/e_dir"; expect_usage "emit es un directorio" "es un directorio"
  if [ "$CAN_SYMLINK" = 1 ]; then
    echo x > "$ROOT/e_target"; ln -s "$ROOT/e_target" "$ROOT/e_link"
    val create "$ROOT/good" --emit "$ROOT/e_link"; expect_usage "emit es un enlace simbolico" "enlace simbolico"
    if [ "$(cat "$ROOT/e_target")" = x ]; then ok "...el destino del enlace no se toca"; else bad "se escribio a traves del enlace"; fi
  else skip "emit es un enlace simbolico" "este sistema no permite crear enlaces simbolicos"; fi
  copy good v_emit; write_marker "$ROOT/v_emit"
  val verify "$ROOT/v_emit" --emit "$d/x.env"; expect_usage "emit en el modo verify" "--emit solo existe en el modo create"
}

sec_temporales() {
  # Simulacion de fallos con "shims" de mktemp/cp/mv antepuestos al PATH del validador.
  copy good s_data; local sha_before; sha_before="$(sha256sum "$ROOT/s_data/odc.mv.db" | cut -d' ' -f1)"
  mkshim mktemp_fail mktemp 'exit 1'
  VPATH="$ROOT/shims/mktemp_fail" val create "$ROOT/s_data"; expect_usage "mktemp -d falla" "mktemp -d fallo"
  mkshim mktemp_empty mktemp '[ "$1" = -d ] && { echo; exit 0; }; exec "'"$REAL_MKTEMP"'" "$@"'
  VPATH="$ROOT/shims/mktemp_empty" val create "$ROOT/s_data"; expect_usage "mktemp -d devuelve una ruta vacia" "no devolvio ninguna ruta"
  mkshim mktemp_nodir mktemp '[ "$1" = -d ] && { echo "'"$ROOT"'/no-existe-dir"; exit 0; }; exec "'"$REAL_MKTEMP"'" "$@"'
  VPATH="$ROOT/shims/mktemp_nodir" val create "$ROOT/s_data"; expect_usage "mktemp -d devuelve una ruta que no existe" "no es un directorio"
  mkshim mktemp_root mktemp '[ "$1" = -d ] && { echo "/"; exit 0; }; exec "'"$REAL_MKTEMP"'" "$@"'
  VPATH="$ROOT/shims/mktemp_root" val create "$ROOT/s_data"; expect_usage "mktemp -d devuelve '/' (no debe llegar a borrarse)" "no es seguro"
  mkshim mktemp_data mktemp '[ "$1" = -d ] && { echo "'"$ROOT"'/s_data"; exit 0; }; exec "'"$REAL_MKTEMP"'" "$@"'
  VPATH="$ROOT/shims/mktemp_data" val create "$ROOT/s_data"; expect_usage "mktemp -d devuelve el propio directorio de datos (no debe borrarlo)" "no es seguro"
  if [ -f "$ROOT/s_data/odc.mv.db" ] && [ "$(sha256sum "$ROOT/s_data/odc.mv.db" | cut -d' ' -f1)" = "$sha_before" ]; then ok "...el directorio de datos y su base siguen intactos"; else bad "el directorio de datos fue alterado o borrado"; fi
  mkdir -p "$ROOT/tmp_pre" && echo x > "$ROOT/tmp_pre/previo"
  mkshim mktemp_used mktemp '[ "$1" = -d ] && { echo "'"$ROOT"'/tmp_pre"; exit 0; }; exec "'"$REAL_MKTEMP"'" "$@"'
  VPATH="$ROOT/shims/mktemp_used" val create "$ROOT/s_data"; expect_usage "mktemp -d devuelve un directorio que no esta vacio" "no esta vacio"
  if [ -f "$ROOT/tmp_pre/previo" ]; then ok "...su contenido no se borra"; else bad "se borro un directorio que no era nuevo"; fi
  mkshim cp_fail cp 'exit 1'
  VPATH="$ROOT/shims/cp_fail" val create "$ROOT/s_data"; expect_usage "cp falla" "no se pudo copiar"
  mkshim cp_empty cp 'shift; : > "$2"; exit 0'
  VPATH="$ROOT/shims/cp_empty" val create "$ROOT/s_data"; expect_usage "cp 'termina bien' pero deja una copia vacia" "copia de odc.mv.db esta vacia"
  # Fallos al escribir --emit.
  local d="$ROOT/emitdir2"; mkdir -p "$d"; echo STALE > "$d/e.env"
  mkshim mv_fail mv 'exit 1'
  VPATH="$ROOT/shims/mv_fail" val create "$ROOT/s_data" --emit "$d/e.env"; expect_usage "mv falla al publicar --emit" "no se pudo publicar el archivo --emit"
  if [ -z "$(ls -A "$d")" ]; then ok "...no queda ni resultado ni temporal ni archivo previo"; else out="$(ls -A "$d")"; bad "residuos tras fallo de mv"; fi
  mkshim emit_mktemp_fail mktemp '[ "$1" = -d ] && exec "'"$REAL_MKTEMP"'" "$@"; exit 1'
  VPATH="$ROOT/shims/emit_mktemp_fail" val create "$ROOT/s_data" --emit "$d/e.env"; expect_usage "no se puede crear el temporal de --emit" "no se pudo crear el archivo temporal de --emit"
  if [ -z "$(ls -A "$d")" ]; then ok "...no se publica nada"; else bad "residuos"; fi
  mkshim emit_mktemp_dev mktemp '[ "$1" = -d ] && exec "'"$REAL_MKTEMP"'" "$@"; echo /dev/full'
  VPATH="$ROOT/shims/emit_mktemp_dev" val create "$ROOT/s_data" --emit "$d/e.env"; expect_usage "el temporal de --emit no es un archivo regular" "no creo el archivo temporal"
  if [ -z "$(ls -A "$d")" ]; then ok "...no se publica nada"; else bad "residuos"; fi
  skip "error de escritura real del temporal de --emit (disco lleno)" "no se puede simular de forma portable; el codigo comprueba el resultado de la redireccion"
}

sec_fileonly() {
  raw --mode verify --dir "$ROOT/good" --valid-hours 4 --file-only; expect_usage "--file-only en verify: no existe" "--file-only fue eliminado"
  expect_not_has "...no muestra ACEPTADA" "ACEPTADA"
  raw --mode create --dir "$ROOT/good" --job-start-epoch "$JOB_START" --valid-hours 4 --file-only; expect_usage "--file-only en create: no existe" "--file-only fue eliminado"
  out="$(GITHUB_ACTIONS=true bash "$VALIDATOR" --mode verify --dir "$ROOT/good" --valid-hours 4 --file-only 2>&1)"; rc=$?
  expect_usage "--file-only con GITHUB_ACTIONS=true: rechazado" "--file-only fue eliminado"
  expect_not_has "...no muestra ACEPTADA" "ACEPTADA"
  copy good fo_ok; write_marker "$ROOT/fo_ok"
  raw --mode verify --dir "$ROOT/fo_ok" --valid-hours 4 --file-only --now-epoch "$NOW"; expect_usage "--file-only con un marcador y un hash correctos: tampoco aprueba"
  if [ "$rc" -ne 0 ]; then ok "...nunca devuelve codigo de exito"; else bad "--file-only devolvio 0"; fi
  # Sin H2 disponible la respuesta es "no se pudo validar" (2), nunca "valida".
  raw --mode verify --dir "$ROOT/fo_ok" --now-epoch "$NOW" --valid-hours 4 --h2-jar "$ROOT/no-existe.jar" --core-jar "$CORE_JAR" --java "$JAVA_BIN"
  expect_usage "verify sin H2: no se puede validar (nunca aprobada)" "no existe el jar de H2"
}

sec_verify() {
  local snap_before snap_after
  copy good v_ok; write_marker "$ROOT/v_ok"
  snap_before="$(snapshot "$ROOT/v_ok")"
  val verify "$ROOT/v_ok";  expect_ok "positivo sintetico con marcador completo, hash y tamano correctos"
  snap_after="$(snapshot "$ROOT/v_ok")"
  if [ "$snap_before" = "$snap_after" ]; then ok "solo lectura: verify no cambia el directorio"; else bad "verify cambio el directorio"; fi
  val verify "$ROOT/v_ok" --valid-hours 0; expect_ok "valid-hours=0 en verify: last.checked posterior al inicio declarado"
  copy good v_nomark;   val verify "$ROOT/v_nomark";  expect_fail "falta el marcador del proyecto" "falta el marcador del proyecto"
  copy good v_ver;      write_marker "$ROOT/v_ver" dependency_check_version=12.2.1;  val verify "$ROOT/v_ver"; expect_fail "marcador de otra version de la herramienta" "el marcador es de Dependency-Check '12.2.1'"
  copy good v_sha;      write_marker "$ROOT/v_sha" "odc_sha256=$(zeros64 0)"; val verify "$ROOT/v_sha"; expect_fail "SHA-256 distinto" "el SHA-256 de odc.mv.db no coincide"
  copy good v_shabad;   write_marker "$ROOT/v_shabad" odc_sha256=XYZ;   val verify "$ROOT/v_shabad"; expect_fail "SHA-256 mal formado" "odc_sha256 del marcador no es un SHA-256 valido"
  copy good v_size;     write_marker "$ROOT/v_size" odc_size_bytes=1;   val verify "$ROOT/v_size";   expect_fail "tamano distinto" "el tamano de odc.mv.db"
  copy good v_sizebad;  write_marker "$ROOT/v_sizebad" odc_size_bytes=abc; val verify "$ROOT/v_sizebad"; expect_fail "tamano no numerico" "odc_size_bytes del marcador no es un entero"
  copy good v_altered;  write_marker "$ROOT/v_altered"; printf '\xff' | dd of="$ROOT/v_altered/odc.mv.db" bs=1 seek=20000 conv=notrunc 2>/dev/null
  val verify "$ROOT/v_altered"; expect_fail "un byte del archivo alterado despues de crear el marcador" "el SHA-256 de odc.mv.db no coincide"
  copy good v_trunc;    write_marker "$ROOT/v_trunc"; head -c 14000 "$ROOT/good/odc.mv.db" > "$ROOT/v_trunc/odc.mv.db"; val verify "$ROOT/v_trunc"; expect_fail "archivo truncado despues de crear el marcador" "el tamano de odc.mv.db"

  # Fechas del marcador.
  copy good v_future;   write_marker "$ROOT/v_future" "completed_epoch=$((NOW + 86400))" "completed_at=$(iso $((NOW + 86400)))"; val verify "$ROOT/v_future"; expect_fail "fecha de finalizacion en el futuro" "la fecha de finalizacion del marcador esta en el futuro"
  copy good v_epoch1;   write_marker "$ROOT/v_epoch1" completed_epoch=abc;  val verify "$ROOT/v_epoch1"; expect_fail "completed_epoch no numerico" "completed_epoch del marcador no es una fecha valida"
  copy good v_epoch2;   write_marker "$ROOT/v_epoch2" completed_epoch=12345678901; val verify "$ROOT/v_epoch2"; expect_fail "completed_epoch de 11 cifras" "completed_epoch del marcador no es una fecha valida"
  copy good v_epoch3;   write_marker "$ROOT/v_epoch3" completed_epoch=0;    val verify "$ROOT/v_epoch3"; expect_fail "completed_epoch = 0" "completed_epoch del marcador no es una fecha valida"
  copy good v_old;      write_marker "$ROOT/v_old" "completed_epoch=$((NOW - 9 * 86400))" "completed_at=$(iso $((NOW - 9 * 86400)))" "update_started_epoch=$((NOW - 9 * 86400 - 100))"; val verify "$ROOT/v_old"; expect_fail "marcador de hace 9 dias (maximo 7)" "la base tiene 9 dias (maximo 7)"
  copy good v_at;       write_marker "$ROOT/v_at" completed_at=2020-01-01T00:00:00Z; val verify "$ROOT/v_at"; expect_fail "completed_at incoherente con completed_epoch" "completed_at ('2020-01-01T00:00:00Z') no coincide"
  # update_started_epoch.
  copy good v_st_miss;  write_marker "$ROOT/v_st_miss"; sed -i '/^update_started_epoch=/d' "$ROOT/v_st_miss/nexusmarket-nvd-update.properties"; val verify "$ROOT/v_st_miss"; expect_fail "falta update_started_epoch" "falta la clave 'update_started_epoch'"
  copy good v_st_bad;   write_marker "$ROOT/v_st_bad" update_started_epoch=abc; val verify "$ROOT/v_st_bad"; expect_fail "update_started_epoch no numerico" "update_started_epoch del marcador no es una fecha valida"
  copy good v_st_zero;  write_marker "$ROOT/v_st_zero" update_started_epoch=0; val verify "$ROOT/v_st_zero"; expect_fail "update_started_epoch = 0" "update_started_epoch del marcador no es una fecha valida"
  copy good v_st_after; write_marker "$ROOT/v_st_after" "update_started_epoch=$((NOW - 10))"; val verify "$ROOT/v_st_after"; expect_fail "inicio posterior a la finalizacion" "update_started_epoch es posterior a completed_epoch"
  copy good v_st_lim;   write_marker "$ROOT/v_st_lim" "update_started_epoch=$((NOW - 30 - MAX_JOB - 300))"; val verify "$ROOT/v_st_lim"; expect_ok "limite: duracion declarada = 21600 s + 300 s"
  copy good v_st_long;  write_marker "$ROOT/v_st_long" "update_started_epoch=$((NOW - 30 - MAX_JOB - 301))"; val verify "$ROOT/v_st_long"; expect_fail "duracion declarada de mas que un job de GitHub" "la actualizacion declarada dura mas de $MAX_JOB s"
  # Estructura del marcador.
  copy good v_dup;      write_marker "$ROOT/v_dup"; echo "odc_size_bytes=1" >> "$ROOT/v_dup/nexusmarket-nvd-update.properties"; val verify "$ROOT/v_dup"; expect_fail "clave duplicada en el marcador" "clave duplicada en el marcador: odc_size_bytes"
  copy good v_miss;     write_marker "$ROOT/v_miss"; sed -i '/^run_id=/d' "$ROOT/v_miss/nexusmarket-nvd-update.properties"; val verify "$ROOT/v_miss"; expect_fail "falta una clave del marcador" "falta la clave 'run_id'"
  copy good v_unk;      write_marker "$ROOT/v_unk"; echo "extra=1" >> "$ROOT/v_unk/nexusmarket-nvd-update.properties"; val verify "$ROOT/v_unk"; expect_fail "clave desconocida (el contrato no admite otras)" "clave desconocida en el marcador: extra"
  copy good v_fmt;      write_marker "$ROOT/v_fmt"; echo "esto no es clave=valor?" >> "$ROOT/v_fmt/nexusmarket-nvd-update.properties"; val verify "$ROOT/v_fmt"; expect_fail "linea con formato invalido" "formato invalido"
  copy good v_crlf;     write_marker "$ROOT/v_crlf"; sed -i 's/$/\r/' "$ROOT/v_crlf/nexusmarket-nvd-update.properties"; val verify "$ROOT/v_crlf"; expect_fail "marcador con finales de linea CRLF" "finales de linea CRLF"
  copy good v_commit;   write_marker "$ROOT/v_commit" commit=zzz;           val verify "$ROOT/v_commit"; expect_fail "commit mal formado" "commit del marcador no es un SHA de 40 hexadecimales"
  copy good v_run;      write_marker "$ROOT/v_run" run_id=abc;              val verify "$ROOT/v_run"; expect_fail "run_id no numerico" "run_id del marcador no es numerico"
  # El marcador declara datos internos: se contrastan con la base.
  copy good v_x_chk;    write_marker "$ROOT/v_x_chk" "nvd_api_last_checked=$(iso $((NOW - 90)))"; val verify "$ROOT/v_x_chk"; expect_fail "nvd_api_last_checked del marcador distinto del de la base" "nvd_api_last_checked del marcador"
  copy good v_x_mod;    write_marker "$ROOT/v_x_mod" "nvd_api_last_modified=$(iso $((NOW - 7200)))"; val verify "$ROOT/v_x_mod"; expect_fail "nvd_api_last_modified del marcador distinto del de la base" "nvd_api_last_modified del marcador"
  copy good v_x_vul;    write_marker "$ROOT/v_x_vul" count_vulnerability=4; val verify "$ROOT/v_x_vul"; expect_fail "count_vulnerability del marcador distinto" "count_vulnerability del marcador (4) no coincide con la base (3)"
  copy good v_x_cpe;    write_marker "$ROOT/v_x_cpe" count_cpeEntry=9;      val verify "$ROOT/v_x_cpe"; expect_fail "count_cpeEntry del marcador distinto" "count_cpeEntry del marcador (9) no coincide"
  copy good v_x_kev;    write_marker "$ROOT/v_x_kev" count_knownExploited=0; val verify "$ROOT/v_x_kev"; expect_fail "count_knownExploited del marcador distinto" "count_knownExploited del marcador (0) no coincide"
  copy good v_x_cnt;    write_marker "$ROOT/v_x_cnt" count_cpeEntry=abc;    val verify "$ROOT/v_x_cnt"; expect_fail "recuento del marcador no numerico" "count_cpeEntry del marcador no es un entero"
  # Coherencia temporal marcador / base.
  mk v_early 5.6 "$GOOD_CHECKED" "$GOOD_MODIFIED" 3 2 1
  write_marker "$ROOT/v_early" "completed_epoch=$((NOW - 3600))" "completed_at=$(iso $((NOW - 3600)))" "update_started_epoch=$((NOW - 5400))"
  val verify "$ROOT/v_early"; expect_fail "marcador anterior a nvd.api.last.checked (no son de la misma actualizacion)" "el marcador del proyecto es anterior a nvd.api.last.checked"
  # Base ANTIGUA con un marcador RECIEN creado que declara sus mismas fechas (el caso que hay que impedir).
  mk v_stale 5.6 "$(iso $((NOW - 30 * 86400)))" "$(iso $((NOW - 31 * 86400)))" 3 2 1
  write_marker "$ROOT/v_stale" "nvd_api_last_checked=$(iso $((NOW - 30 * 86400)))" "nvd_api_last_modified=$(iso $((NOW - 31 * 86400)))"
  val verify "$ROOT/v_stale"; expect_fail "base de hace 30 dias con marcador reciente: last.checked anterior al inicio declarado" "nvd.api.last.checked es anterior a update_started_epoch"
  expect_has "...y los datos de NVD se declaran desactualizados" "nvd.api.last.checked tiene 30 dias"
  write_marker "$ROOT/v_stale" "nvd_api_last_checked=$(iso $((NOW - 30 * 86400)))" "nvd_api_last_modified=$(iso $((NOW - 31 * 86400)))" "update_started_epoch=$((NOW - 30 * 86400))"
  val verify "$ROOT/v_stale"; expect_fail "mismo caso falsificando un inicio antiguo: duracion imposible" "la actualizacion declarada dura mas de"
  expect_has "...y al ser el marcador inconsistente no se abre H2 (la comprobacion de los 30 dias necesita H2 y no se hace)" "No se abrio la base con H2"
  expect_not_has "...asi que no aparece el rechazo por antiguedad de last.checked" "nvd.api.last.checked tiene 30 dias"
  # Politica de ventana en verify.
  mk v_win 5.6 "$(iso $((JOB_START - 1)))" "$(iso $((JOB_START - 3600)))" 3 2 1
  write_marker "$ROOT/v_win" "nvd_api_last_checked=$(iso $((JOB_START - 1)))" "nvd_api_last_modified=$(iso $((JOB_START - 3600)))"
  val verify "$ROOT/v_win"; expect_ok "last.checked 1 s antes del inicio: aceptado con una ventana de 4 h"
  val verify "$ROOT/v_win" --valid-hours 0; expect_fail "...y rechazado con valid-hours=0" "nvd.api.last.checked es anterior a update_started_epoch"
}

sec_parcial() {
  mk v_partial 5.6 ABSENT ABSENT 3 2 1; write_marker "$ROOT/v_partial"
  val verify "$ROOT/v_partial"; expect_fail "base parcial (sin last.*) con hash, tamano y marcador consistentes: RECHAZADA" "falta nvd.api.last.checked"
  expect_has "...tambien por discrepar con lo que declara el marcador" "no coincide con la base"
  expect_not_has "...y nunca aparece ACEPTADA" "ACEPTADA"
  echo "  (no existe ningun modo que acepte esta base sin leer los marcadores internos)"
}

sec_ida_y_vuelta() {
  local d="$ROOT/rt_emit"; mkdir -p "$d"
  copy good rt; val create "$ROOT/rt" --emit "$d/e.env"; expect_ok "create --emit sobre el fixture valido"
  marker_from_emit "$ROOT/rt" "$d/e.env"
  val verify "$ROOT/rt"; expect_ok "verify acepta el marcador construido con lo emitido + el contexto del workflow"
  expect_has "...y lee los mismos marcadores internos" "last.checked=$GOOD_CHECKED"
  val verify "$ROOT/rt" --valid-hours 0; expect_ok "...tambien con valid-hours=0"
  # Con valid-hours=0 en create el recorrido completo tambien funciona.
  mk rt0 5.6 "$(iso "$JOB_START")" "$(iso $((JOB_START - 3600)))" 3 2 1
  val create "$ROOT/rt0" --valid-hours 0 --emit "$d/e0.env"; expect_ok "create --valid-hours 0 --emit"
  marker_from_emit "$ROOT/rt0" "$d/e0.env"; val verify "$ROOT/rt0" --valid-hours 0; expect_ok "...y verify --valid-hours 0 lo acepta"
  # El marcador emitido es el mismo contrato: 8 claves emitidas + 5 de contexto = 13.
  local n; n="$(cat "$d/e.env" | wc -l | tr -d ' ')"
  if [ "$n" = 8 ]; then ok "--emit escribe exactamente 8 claves (las 5 restantes las aporta el workflow)"; else out="$(cat "$d/e.env")"; bad "numero de claves emitidas: $n"; fi
  # Alterar la base despues de emitir invalida el marcador.
  printf '\xff' | dd of="$ROOT/rt/odc.mv.db" bs=1 seek=20000 conv=notrunc 2>/dev/null
  val verify "$ROOT/rt"; expect_fail "alterar la base despues de emitir el marcador" "el SHA-256 de odc.mv.db no coincide"
}

CAN_SYMLINK=0
mkdir -p "$ROOT/symtest" && echo x > "$ROOT/symtest/a" && ln -s "$ROOT/symtest/a" "$ROOT/symtest/b" 2>/dev/null && [ -L "$ROOT/symtest/b" ] && CAN_SYMLINK=1

echo "== Fixtures sinteticas (esquema real de Dependency-Check 12.2.2; sin datos de NVD)"
mk good 5.6 "$GOOD_CHECKED" "$GOOD_MODIFIED" 3 2 1

run_section "positivo"           sec_positivo
run_section "archivo"            sec_archivo
run_section "archivos-extra"     sec_archivos_extra
run_section "internos"           sec_internos
run_section "esquema"            sec_esquema
run_section "minimos"            sec_minimos
run_section "plugin-propios"     sec_plugin_propios
run_section "entorno"            sec_entorno
run_section "politica"           sec_politica
run_section "parametros-prueba"  sec_parametros_prueba
run_section "integridad"         sec_integridad
run_section "orden"              sec_orden
run_section "emit"               sec_emit
run_section "temporales"         sec_temporales
run_section "file-only"          sec_fileonly
run_section "verify"             sec_verify
run_section "parcial"            sec_parcial
run_section "ida-y-vuelta"       sec_ida_y_vuelta

echo
echo "Resumen: $passed pasadas, $failed fallidas, $skipped omitidas."
echo "Recordatorio: los casos positivos usan bases sinteticas; validan la logica del validador, no la integridad de una base NVD real."
[ "$failed" -eq 0 ]
