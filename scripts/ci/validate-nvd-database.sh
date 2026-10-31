#!/usr/bin/env bash
# Valida una base de datos NVD de OWASP Dependency-Check 12.2.2 antes de transferirla (cache de Actions u otro medio).
#
# Que comprueba (detalle, razones y limitaciones en scripts/ci/NVD_VALIDATOR.md):
#   - odc.mv.db existe, es un archivo regular (no un enlace) y no esta vacio; SHA-256 y tamano se calculan con
#     comprobacion de errores.
#   - Los marcadores INTERNOS de Dependency-Check, leidos de la tabla `properties` sobre una COPIA y en solo lectura:
#     version = 5.6, nvd.api.last.checked y nvd.api.last.modified presentes, con fecha valida, no futura y coherentes.
#     Dependency-Check los guarda al FINAL de una actualizacion completa; una descarga interrumpida deja registros pero
#     no los marcadores.
#   - El marcador del PROYECTO (nexusmarket-nvd-update.properties), distinto de los internos (modo verify). Su formato,
#     campos, fechas, SHA-256 y tamano se comprueban ANTES de abrir la base con H2; despues se contrastan con ella las
#     fechas y los recuentos que declara. Eso NO resuelve la autenticidad: un marcador forjado con un hash coincidente
#     sigue sin ser confiable.
#   - Archivos adicionales en el directorio de datos (ver "Archivos adicionales" en NVD_VALIDATOR.md).
#   - Recuentos de filas: solo se exige >= 1 en vulnerability y cpeEntry. NO existe un umbral demostrado de "base
#     completa"; los recuentos se muestran para calibrar con una base real. Un hash correcto solo prueba que el archivo
#     no cambio, nunca que la base este completa.
#
# Modos:
#   create : se ejecuta justo despues de la actualizacion y ANTES de escribir el marcador. Exige --job-start-epoch.
#            Con --emit FICHERO escribe, SOLO si la base es valida y de forma atomica, los datos validados (contrato del
#            marcador, ver NVD_VALIDATOR.md). Antes de validar borra cualquier FICHERO previo.
#   verify : se ejecuta al restaurar. Exige el marcador del proyecto completo y coherente con la base.
#
# --valid-hours H (OBLIGATORIO, sin valor por defecto): ventana en la que Dependency-Check OMITE la actualizacion
#   (nvd.api.check.validforhours, 4 por defecto en 12.2.2). H = 0 es la politica estricta: junto con
#   -DnvdValidForHours=0 en el plugin (que hace que nunca se omita) exige que nvd.api.last.checked sea posterior al
#   inicio, es decir, "esta ejecucion actualizo". H > 0 acepta una actualizacion omitida y se avisa: NO demuestra que la
#   ejecucion actual haya actualizado la base. El validador se fia de --job-start-epoch; que sea el inicio real lo
#   garantiza el workflow, no este script.
#
# Parametros operativos:  --mode --dir --valid-hours --job-start-epoch --max-age-days --expected-version --emit
#                         --java --h2-jar --core-jar   (y las variables JAVA, JAVA_HOME, M2_REPO)
# Parametros EXCLUSIVOS DE PRUEBAS (se rechazan sin NVD_VALIDATOR_TEST=1):  --now-epoch --max-job-seconds
#                         --expected-schema.  NVD_VALIDATOR_TEST=1 es un mecanismo de pruebas, NO una autenticacion ni
#                         una frontera de seguridad: cualquiera que controle el entorno puede activarlo. Si se usa algun
#                         parametro de pruebas, la salida lo declara y el resultado no sirve para aprobar una base real.
#
# Codigos de salida: 0 = valida (por estas comprobaciones); 1 = rechazada; 2 = uso o entorno incorrecto (no se pudo
# validar); 3 = no aprobada automaticamente: hay archivos que exigen revision humana. Solo el 0 es una aprobacion. No
# existe ningun modo que apruebe una base sin leer sus marcadores internos.
#
# Uso: bash scripts/ci/validate-nvd-database.sh --mode create|verify --dir DIR --valid-hours H [--job-start-epoch N] [opciones]
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER="$SCRIPT_DIR/NvdDbInspect.java"
MARKER_NAME="nexusmarket-nvd-update.properties"
CLOCK_SKEW=300   # segundos de desfase de reloj tolerados (igual que el CI actual)

# Contrato del marcador del proyecto: todas las claves son obligatorias y no se admiten otras.
MARKER_KEYS=(dependency_check_version update_started_epoch completed_epoch completed_at run_id commit
             odc_sha256 odc_size_bytes nvd_api_last_checked nvd_api_last_modified
             count_vulnerability count_cpeEntry count_knownExploited)

mode=""
dir=""
expected_version="12.2.2"
expected_schema="5.6"          # exclusivo de pruebas si se cambia
now_epoch=""                   # exclusivo de pruebas si se indica
job_start=""
valid_hours=""                 # obligatorio: sin valor por defecto
max_age_days=7
max_job_seconds=21600          # limite de un job en runners hospedados de GitHub (6 h); exclusivo de pruebas si se cambia
emit=""
java_bin=""
h2_jar=""
core_jar=""
test_only_used=""

# Limpieza: solo lo creado por ESTA ejecucion y solo si se comprobo que es seguro borrarlo.
tmp=""; tmp_ok=0; emit_tmp=""
cleanup() {
  if [ -n "$emit_tmp" ] && [ -f "$emit_tmp" ] && [ ! -L "$emit_tmp" ]; then rm -f -- "$emit_tmp"; fi
  if [ "$tmp_ok" -eq 1 ] && [ -n "$tmp" ] && [ -d "$tmp" ] && [ ! -L "$tmp" ]; then rm -rf -- "$tmp"; fi
}
trap cleanup EXIT

usage_error() {
  echo "::error::validate-nvd-database.sh: $*" >&2
  echo "Uso: $0 --mode create|verify --dir DIR --valid-hours H [--job-start-epoch N] [--expected-version V] [--max-age-days D] [--emit FICHERO (solo create)] [--java RUTA] [--h2-jar RUTA] [--core-jar RUTA]" >&2
  exit 2
}

# Parametros exclusivos de pruebas: solo con NVD_VALIDATOR_TEST=1 (mecanismo de pruebas, no una frontera de seguridad).
test_only() {
  if [ "${NVD_VALIDATOR_TEST:-}" != 1 ]; then
    usage_error "$1 es un parametro exclusivo de pruebas y no se admite en uso operativo (requiere NVD_VALIDATOR_TEST=1; eso es un mecanismo de pruebas, no una autenticacion)."
  fi
  test_only_used="$test_only_used $1"
}

while [ $# -gt 0 ]; do
  # Una opcion sin valor al final haria fallar "shift 2" y el bucle no terminaria nunca.
  case "$1" in
    --mode|--dir|--expected-version|--job-start-epoch|--valid-hours|--max-age-days|--emit|--java|--h2-jar|--core-jar|--now-epoch|--max-job-seconds|--expected-schema)
      [ $# -ge 2 ] || usage_error "la opcion $1 requiere un valor." ;;
  esac
  case "$1" in
    --mode) mode="${2:-}"; shift 2 ;;
    --dir) dir="${2:-}"; shift 2 ;;
    --expected-version) expected_version="${2:-}"; shift 2 ;;
    --job-start-epoch) job_start="${2:-}"; shift 2 ;;
    --valid-hours) valid_hours="${2:-}"; shift 2 ;;
    --max-age-days) max_age_days="${2:-}"; shift 2 ;;
    --emit) emit="${2:-}"; shift 2 ;;
    --java) java_bin="${2:-}"; shift 2 ;;
    --h2-jar) h2_jar="${2:-}"; shift 2 ;;
    --core-jar) core_jar="${2:-}"; shift 2 ;;
    --now-epoch) test_only --now-epoch; now_epoch="${2:-}"; shift 2 ;;
    --max-job-seconds) test_only --max-job-seconds; max_job_seconds="${2:-}"; shift 2 ;;
    --expected-schema) test_only --expected-schema; expected_schema="${2:-}"; shift 2 ;;
    --file-only) usage_error "--file-only fue eliminado: ninguna base se aprueba sin leer sus marcadores internos (nvd.api.last.*, esquema, recuentos). Si falta H2, el resultado es 'no se pudo validar' (codigo 2), nunca 'valida'." ;;
    *) usage_error "argumento desconocido: $1" ;;
  esac
done

[ "$mode" = create ] || [ "$mode" = verify ] || usage_error "--mode debe ser create o verify"
[ -n "$dir" ] || usage_error "falta --dir"
[ -d "$dir" ] || usage_error "--dir no es un directorio: $dir"
dir_abs="$(cd "$dir" && pwd -P)" || usage_error "no se pudo resolver --dir"

# --emit: validar el destino ANTES de borrar nada, y borrar el archivo previo ANTES de validar la base, para que un
# fallo posterior nunca deje un archivo antiguo que un paso siguiente pueda confundir con el resultado actual.
if [ -n "$emit" ]; then
  [ "$mode" = create ] || usage_error "--emit solo existe en el modo create"
  emit_name="$(basename -- "$emit")"
  case "$emit_name" in ''|.|..|/) usage_error "--emit no es un nombre de archivo valido: $emit" ;; esac
  emit_dir_abs="$(cd "$(dirname -- "$emit")" 2>/dev/null && pwd -P)" || usage_error "el directorio de --emit no existe: $(dirname -- "$emit")"
  emit_abs="$emit_dir_abs/$emit_name"
  case "$emit_abs/" in "$dir_abs"/*) usage_error "--emit no puede estar dentro del directorio de datos (acabaria en la cache): $emit" ;; esac
  [ ! -L "$emit_abs" ] || usage_error "--emit es un enlace simbolico: $emit"
  [ ! -d "$emit_abs" ] || usage_error "--emit es un directorio: $emit"
  if [ -e "$emit_abs" ]; then
    [ -f "$emit_abs" ] || usage_error "--emit existe y no es un archivo regular: $emit"
    rm -f -- "$emit_abs" || usage_error "no se pudo borrar el archivo --emit previo: $emit"
  fi
  [ ! -e "$emit_abs" ] || usage_error "el archivo --emit previo sigue existiendo: $emit"
fi

# Validacion estricta de argumentos numericos (sin ceros a la izquierda: bash los leeria como octal).
[ -n "$valid_hours" ] || usage_error "falta --valid-hours (obligatorio, sin valor por defecto): use 0 para exigir que ESTA ejecucion actualizo la base, o N > 0 para aceptar una actualizacion omitida por la ventana de Dependency-Check."
[[ "$valid_hours" =~ ^(0|[1-9][0-9]{0,3})$ ]] || usage_error "--valid-hours debe ser un entero sin ceros a la izquierda (0 a 9999): '$valid_hours'"
[[ "$max_age_days" =~ ^(0|[1-9][0-9]{0,4})$ ]] || usage_error "--max-age-days debe ser un entero sin ceros a la izquierda: '$max_age_days'"
[[ "$max_job_seconds" =~ ^[1-9][0-9]{0,7}$ ]] || usage_error "--max-job-seconds debe ser un entero positivo sin ceros a la izquierda: '$max_job_seconds'"
[ -n "$now_epoch" ] || now_epoch="$(date -u +%s)"
[[ "$now_epoch" =~ ^[1-9][0-9]{0,9}$ ]] || usage_error "la fecha actual (--now-epoch / date) no es un entero valido: '$now_epoch'"
if [ "$mode" = create ]; then
  [[ "$job_start" =~ ^[1-9][0-9]{0,9}$ ]] || usage_error "el modo create exige --job-start-epoch (segundos UTC del inicio de la actualizacion)"
fi
command -v sha256sum >/dev/null 2>&1 || usage_error "falta sha256sum"

failures=0
fail() { echo "::error::RECHAZADA: $*"; failures=$((failures + 1)); }
warn() { echo "::warning::$*"; }
finish() {
  if [ "$failures" -gt 0 ]; then
    echo "Resultado: RECHAZADA ($failures problema(s))."
    exit 1
  fi
}
age_days_of() { local s=$((now_epoch - $1)); [ "$s" -lt 0 ] && s=0; echo $((s / 86400)); }

# SHA-256 y tamano con comprobacion de errores: devuelven un valor valido o fallan (nunca una cadena vacia o rara).
sha_of() {
  local o
  o="$(sha256sum -- "$1" 2>/dev/null)" || return 1
  o="${o#\\}"            # sha256sum antepone "\" si la ruta tiene barras invertidas
  o="${o%% *}"
  [[ "$o" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s' "$o"
}
size_of() {
  local o
  o="$(wc -c < "$1" 2>/dev/null)" || return 1
  [[ "$o" =~ ^[[:space:]]*([0-9]+)[[:space:]]*$ ]] || return 1
  printf '%s' "${BASH_REMATCH[1]}"
}

echo "Politica efectiva: modo=$mode valid-hours=$valid_hours max-age-days=$max_age_days expected-version=$expected_version"
if [ "$valid_hours" -gt 0 ]; then
  warn "valid-hours=$valid_hours: una actualizacion OMITIDA por la ventana de Dependency-Check se acepta; esto NO demuestra que la ejecucion actual haya actualizado la base. Use 0 (y -DnvdValidForHours=0 en el plugin) para exigirlo."
fi
if [ -n "$test_only_used" ]; then
  warn "MODO DE PRUEBAS: parametros exclusivos de pruebas en uso ($test_only_used ). Este resultado NO sirve para aprobar una base real."
fi

db="$dir/odc.mv.db"

# ---------------------------------------------------------------- archivo de la base
if [ -L "$db" ]; then
  fail "odc.mv.db es un enlace simbolico; solo se acepta un archivo regular."
elif [ ! -f "$db" ]; then
  fail "no existe odc.mv.db en $dir."
elif [ ! -s "$db" ]; then
  fail "odc.mv.db esta vacio."
fi
finish

actual_sha="$(sha_of "$db")" || usage_error "no se pudo calcular el SHA-256 de odc.mv.db (sha256sum fallo o devolvio un valor que no son 64 hexadecimales)."
actual_size="$(size_of "$db")" || usage_error "no se pudo calcular el tamano de odc.mv.db (wc fallo o no devolvio un entero no negativo)."
echo "odc.mv.db: $actual_size bytes, sha256=$actual_sha"

# ---------------------------------------------------------------- archivos adicionales
# Clasificacion (ver NVD_VALIDATOR.md): esperados (odc.mv.db y el marcador) | a revisar por una persona (cualquier otro
# archivo "odc.*", enlaces simbolicos) | no reconocidos (se listan; no se ha verificado que sean inocuos).
review=(); unknown=()
while IFS= read -r -d '' p; do
  name="${p##*/}"
  case "$name" in odc.mv.db|"$MARKER_NAME") continue ;; esac
  if [ -L "$p" ]; then review+=("$name (enlace simbolico)")
  elif [[ "$name" == odc.* ]]; then review+=("$name")
  elif [ -d "$p" ]; then unknown+=("$name/ (directorio)")
  else unknown+=("$name"); fi
done < <(find "$dir" -mindepth 1 -maxdepth 1 -print0 2>/dev/null | sort -z)
for n in "${review[@]+"${review[@]}"}"; do
  warn "REVISION HUMANA: '$n' no deberia quedar tras una actualizacion limpia (artefacto de H2 o de Dependency-Check: trazas de error, bloqueo de actualizacion o fichero temporal)."
done
for n in "${unknown[@]+"${unknown[@]}"}"; do
  warn "Archivo no reconocido en el directorio de datos (no se valida; no se ha verificado que sea inocuo): $n"
done

# ---------------------------------------------------------------- marcador del proyecto (solo verify), SIN abrir H2
declare -A m=()
marker_struct_ok=0
if [ "$mode" = verify ]; then
  marker="$dir/$MARKER_NAME"
  if [ ! -f "$marker" ] || [ -L "$marker" ]; then
    fail "falta el marcador del proyecto ($MARKER_NAME)."
  elif [ -n "$(tr -cd '\r' < "$marker")" ]; then   # tr trabaja por bytes; el grep de Git Bash descarta el \r
    fail "el marcador del proyecto contiene finales de linea CRLF; el contrato exige LF (un valor con \\r nunca coincidiria)."
  else
    marker_struct_ok=1
    while IFS= read -r line || [ -n "$line" ]; do
      [ -z "$line" ] && continue
      if [[ "$line" =~ ^([A-Za-z0-9_]+)=(.*)$ ]]; then
        key="${BASH_REMATCH[1]}"; val="${BASH_REMATCH[2]}"
        known=0
        for k in "${MARKER_KEYS[@]}"; do [ "$k" = "$key" ] && known=1; done
        if [ "$known" -eq 0 ]; then
          fail "clave desconocida en el marcador: $key (el contrato no admite otras)."; marker_struct_ok=0
        elif [ -n "${m[$key]+x}" ]; then
          fail "clave duplicada en el marcador: $key."; marker_struct_ok=0
        else
          m["$key"]="$val"
        fi
      else
        fail "linea del marcador con formato invalido (se esperaba clave=valor)."; marker_struct_ok=0
      fi
    done < "$marker"
    for k in "${MARKER_KEYS[@]}"; do
      if [ -z "${m[$k]+x}" ]; then fail "falta la clave '$k' en el marcador."; marker_struct_ok=0; fi
    done
  fi

  if [ "$marker_struct_ok" -eq 1 ]; then
    [ "${m[dependency_check_version]}" = "$expected_version" ] || fail "el marcador es de Dependency-Check '${m[dependency_check_version]}', se esperaba '$expected_version'."
    m_started=""; m_completed=""
    if [[ "${m[update_started_epoch]}" =~ ^[1-9][0-9]{0,9}$ ]]; then m_started="${m[update_started_epoch]}"
    else fail "update_started_epoch del marcador no es una fecha valida ('${m[update_started_epoch]}')."; fi
    if [[ "${m[completed_epoch]}" =~ ^[1-9][0-9]{0,9}$ ]]; then m_completed="${m[completed_epoch]}"
    else fail "completed_epoch del marcador no es una fecha valida ('${m[completed_epoch]}')."; fi
    if [ -n "$m_completed" ]; then
      if [ "$m_completed" -gt $((now_epoch + CLOCK_SKEW)) ]; then
        fail "la fecha de finalizacion del marcador esta en el futuro."
      else
        d="$(age_days_of "$m_completed")"
        [ "$d" -le "$max_age_days" ] || fail "la base tiene $d dias (maximo $max_age_days)."
      fi
      expected_at="$(date -u -d "@$m_completed" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || true)"
      [ -n "$expected_at" ] && [ "${m[completed_at]}" = "$expected_at" ] || fail "completed_at ('${m[completed_at]}') no coincide con completed_epoch."
    fi
    if [ -n "$m_started" ] && [ -n "$m_completed" ]; then
      if [ "$m_started" -gt "$m_completed" ]; then
        fail "update_started_epoch es posterior a completed_epoch: temporalmente imposible."
      elif [ $((m_completed - m_started)) -gt $((max_job_seconds + CLOCK_SKEW)) ]; then
        fail "la actualizacion declarada dura mas de $max_job_seconds s (limite de un job de GitHub): update_started_epoch no es creible."
      fi
    fi
    [[ "${m[run_id]}" =~ ^[0-9]+$ ]] || fail "run_id del marcador no es numerico."
    [[ "${m[commit]}" =~ ^[0-9a-f]{40}$ ]] || fail "commit del marcador no es un SHA de 40 hexadecimales."
    if [[ "${m[odc_sha256]}" =~ ^[0-9a-f]{64}$ ]]; then
      [ "${m[odc_sha256]}" = "$actual_sha" ] || fail "el SHA-256 de odc.mv.db no coincide con el del marcador (archivo alterado o de otra ejecucion)."
    else
      fail "odc_sha256 del marcador no es un SHA-256 valido."
    fi
    if [[ "${m[odc_size_bytes]}" =~ ^[0-9]+$ ]]; then
      [ "${m[odc_size_bytes]}" = "$actual_size" ] || fail "el tamano de odc.mv.db ($actual_size) no coincide con el del marcador (${m[odc_size_bytes]}): archivo truncado o alterado."
    else
      fail "odc_size_bytes del marcador no es un entero."
    fi
    for k in count_vulnerability count_cpeEntry count_knownExploited; do
      [[ "${m[$k]}" =~ ^[0-9]+$ ]] || fail "$k del marcador no es un entero."
    done
  fi
fi

# ---------------------------------------------------------------- inicio de la actualizacion (solo create), SIN abrir H2
if [ "$mode" = create ]; then
  if [ "$job_start" -gt $((now_epoch + CLOCK_SKEW)) ]; then
    fail "--job-start-epoch esta en el futuro: no puede ser posterior a la finalizacion."
  elif [ $((now_epoch - job_start)) -gt $((max_job_seconds + CLOCK_SKEW)) ]; then
    fail "--job-start-epoch es anterior en mas de $max_job_seconds s (limite de un job de GitHub): no es creible como inicio de esta ejecucion."
  fi
fi

# Si el marcador o el inicio ya son inconsistentes no se abre la base con H2: abrir un archivo que no encaja con lo que
# declara su propio marcador solo anade superficie. (No es una defensa de autenticidad: un marcador forjado con un hash
# coincidente llega a abrirse igualmente.)
if [ "$failures" -gt 0 ]; then
  echo "No se abrio la base con H2: el marcador / el inicio de la actualizacion ya son inconsistentes."
  finish
fi

# ---------------------------------------------------------------- entorno para H2
declare -A v=()
if [ -z "$java_bin" ]; then
  if [ -n "${JAVA:-}" ]; then java_bin="$JAVA"
  elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then java_bin="$JAVA_HOME/bin/java"
  else java_bin="java"; fi
fi
command -v "$java_bin" >/dev/null 2>&1 || [ -x "$java_bin" ] || usage_error "no se encuentra Java ($java_bin); no se pueden leer los marcadores internos."
m2="${M2_REPO:-$HOME/.m2/repository}"
[ -n "$h2_jar" ] || h2_jar="$m2/com/h2database/h2/2.4.240/h2-2.4.240.jar"
[ -n "$core_jar" ] || core_jar="$m2/org/owasp/dependency-check-core/$expected_version/dependency-check-core-$expected_version.jar"
[ -f "$h2_jar" ] || usage_error "no existe el jar de H2 ($h2_jar). Este script no descarga nada."
[ -f "$core_jar" ] || usage_error "no existe el jar dependency-check-core ($core_jar). Este script no descarga nada."
echo "Entorno: java=$java_bin h2=$h2_jar core=$core_jar"

# Directorio temporal: se comprueba el resultado de mktemp y que sea un directorio nuevo, vacio, distinto de "/" y de
# --dir, ANTES de marcarlo como borrable por la limpieza.
tmp="$(mktemp -d)" || usage_error "mktemp -d fallo: no se puede crear la copia de trabajo."
[ -n "$tmp" ] || usage_error "mktemp -d no devolvio ninguna ruta."
[ -d "$tmp" ] && [ ! -L "$tmp" ] || usage_error "mktemp -d devolvio algo que no es un directorio: $tmp"
tmp_abs="$(cd "$tmp" && pwd -P)" || usage_error "no se pudo resolver el directorio temporal."
case "$tmp_abs" in
  /|"$dir_abs") tmp=""; usage_error "el directorio temporal no es seguro ($tmp_abs)." ;;
esac
if [ -n "$(ls -A -- "$tmp" 2>/dev/null)" ]; then tmp=""; usage_error "el directorio temporal no esta vacio; no es nuevo."; fi
tmp_ok=1
cp -- "$db" "$tmp/odc.mv.db" || usage_error "no se pudo copiar odc.mv.db al directorio temporal."
[ -s "$tmp/odc.mv.db" ] || usage_error "la copia de odc.mv.db esta vacia."
copy_sha="$(sha_of "$tmp/odc.mv.db")" || usage_error "no se pudo calcular el SHA-256 de la copia de odc.mv.db."
[ "$copy_sha" = "$actual_sha" ] || usage_error "la copia de odc.mv.db difiere del original (SHA-256 distinto): no se valida una copia incompleta."

if command -v cygpath >/dev/null 2>&1; then
  sep=';'; native() { cygpath -m "$1"; }
else
  sep=':'; native() { printf '%s' "$1"; }
fi
cp_arg="$(native "$h2_jar")$sep$(native "$core_jar")"
inspect_out="$("$java_bin" -cp "$cp_arg" "$(native "$HELPER")" "$(native "$tmp")" 2>"$tmp/inspect.err")"
inspect_rc=$?
if [ "$inspect_rc" -eq 2 ]; then
  cat "$tmp/inspect.err" >&2
  usage_error "entorno incorrecto para leer la base (classpath); no se puede validar."
fi
while IFS= read -r line; do
  line="${line%$'\r'}"   # defensa: un Java que termine las lineas con \r\n no debe cambiar los valores
  [ -n "$line" ] && v["${line%%=*}"]="${line#*=}"
done <<<"$inspect_out"

# La copia es lo que se abre; el original no debe haber cambiado durante la validacion.
after_sha="$(sha_of "$db")" || usage_error "no se pudo recalcular el SHA-256 de odc.mv.db tras la validacion."
[ "$after_sha" = "$actual_sha" ] || fail "odc.mv.db cambio mientras se validaba."

# ---------------------------------------------------------------- marcadores internos
internals_ok=0
if [ "$inspect_rc" -ne 0 ] || [ "${v[db_open]:-}" != ok ]; then
  fail "no se pudo abrir odc.mv.db en solo lectura (truncada, corrupta o con cabecera invalida): ${v[db_error]:-sin detalle}."
else
  internals_ok=1
  [ "${v[schema_version]:-}" = "$expected_schema" ] || fail "properties.version es '${v[schema_version]:-}', se esperaba '$expected_schema'."

  check_epoch=""; mod_epoch=""
  for key in nvd.api.last.checked nvd.api.last.modified; do
    # Sin ":-": un valor presente pero vacio es una fecha invalida, no una fecha ausente.
    raw="${v[$key]-ABSENT}"; ep="${v[$key.epoch]-ABSENT}"
    if [ "$raw" = ABSENT ]; then
      fail "falta $key: la actualizacion de NVD no llego a completarse (Dependency-Check lo guarda solo al final)."
    elif ! [[ "$ep" =~ ^[0-9]+$ ]] || [ "$ep" -le 0 ]; then
      fail "$key no es una fecha valida ('$raw'); formato esperado uuuu-MM-ddTHH:mm:ssX."
    elif [ "$ep" -gt $((now_epoch + CLOCK_SKEW)) ]; then
      fail "$key esta en el futuro ('$raw')."
    else
      [ "$key" = nvd.api.last.checked ] && check_epoch="$ep" || mod_epoch="$ep"
    fi
  done
  if [ -n "$check_epoch" ] && [ -n "$mod_epoch" ] && [ "$mod_epoch" -gt $((check_epoch + CLOCK_SKEW)) ]; then
    fail "nvd.api.last.modified es posterior a nvd.api.last.checked: fechas incoherentes."
  fi

  # Politica de actualizacion: "esta ejecucion actualizo" (valid_hours = 0) o "la base es reciente" (valid_hours > 0).
  window=$((valid_hours * 3600))
  if [ "$mode" = create ]; then
    if [ -n "$check_epoch" ] && [ "$check_epoch" -lt $((job_start - window)) ]; then
      if [ "$valid_hours" -eq 0 ]; then
        fail "nvd.api.last.checked es anterior al inicio de esta ejecucion: la actualizacion NO se ejecuto en esta ejecucion (la base es una anterior)."
      else
        fail "nvd.api.last.checked es anterior al inicio de esta ejecucion menos ${valid_hours} h: la actualizacion no se ejecuto (o fallo) y la base es la anterior."
      fi
    fi
  elif [ "$marker_struct_ok" -eq 1 ]; then
    if [ -n "${m_started:-}" ] && [ -n "$check_epoch" ] && [ "$check_epoch" -lt $((m_started - window)) ]; then
      fail "nvd.api.last.checked es anterior a update_started_epoch (menos ${valid_hours} h): la base no corresponde a la actualizacion que declara el marcador."
    fi
    if [ -n "${m_completed:-}" ] && [ -n "$check_epoch" ] && [ "$check_epoch" -gt $((m_completed + CLOCK_SKEW)) ]; then
      fail "el marcador del proyecto es anterior a nvd.api.last.checked: marcador y base no corresponden a la misma actualizacion."
    fi
  fi
  if [ "$mode" = verify ] && [ -n "$check_epoch" ]; then
    d="$(age_days_of "$check_epoch")"
    [ "$d" -le "$max_age_days" ] || fail "nvd.api.last.checked tiene $d dias (maximo $max_age_days): los datos de NVD estan desactualizados aunque el marcador sea reciente."
  fi

  for table in vulnerability cpeEntry knownExploited; do
    n="${v[count.$table]:-MISSING}"
    if ! [[ "$n" =~ ^[0-9]+$ ]]; then
      fail "no se pudo contar la tabla $table ($n)."
    elif [ "$table" != knownExploited ] && [ "$n" -lt 1 ]; then
      fail "la tabla $table esta vacia."
    fi
  done

  # El marcador declara datos internos: se contrastan, no se ignoran.
  if [ "$mode" = verify ] && [ "$marker_struct_ok" -eq 1 ]; then
    [ "${m[nvd_api_last_checked]}" = "${v[nvd.api.last.checked]:-}" ] || fail "nvd_api_last_checked del marcador ('${m[nvd_api_last_checked]}') no coincide con la base ('${v[nvd.api.last.checked]:-}')."
    [ "${m[nvd_api_last_modified]}" = "${v[nvd.api.last.modified]:-}" ] || fail "nvd_api_last_modified del marcador ('${m[nvd_api_last_modified]}') no coincide con la base ('${v[nvd.api.last.modified]:-}')."
    for table in vulnerability cpeEntry knownExploited; do
      [ "${m[count_$table]}" = "${v[count.$table]:-}" ] || fail "count_$table del marcador (${m[count_$table]}) no coincide con la base (${v[count.$table]:-})."
    done
  fi

  echo "Marcadores internos: esquema=${v[schema_version]:-} last.checked=${v[nvd.api.last.checked]:-} last.modified=${v[nvd.api.last.modified]:-}"
  echo "Recuentos (informativos, sin umbral demostrado): vulnerability=${v[count.vulnerability]:-?} cpeEntry=${v[count.cpeEntry]:-?} knownExploited=${v[count.knownExploited]:-?}"
  if [ "$valid_hours" -eq 0 ]; then
    echo "Politica de actualizacion: se exige que nvd.api.last.checked sea posterior al inicio (valid-hours=0)."
  else
    echo "Politica de actualizacion: ventana de ${valid_hours} h entre nvd.api.last.checked y el inicio de la actualizacion."
  fi
fi
finish

# ---------------------------------------------------------------- revision humana
if [ "${#review[@]}" -gt 0 ]; then
  echo "::error::REVISION HUMANA REQUERIDA: ${review[*]}"
  echo "Resultado: NO APROBADA AUTOMATICAMENTE. La base supero las comprobaciones, pero hay archivos que una persona debe revisar (codigo 3). No se emite ningun resultado."
  exit 3
fi

# ---------------------------------------------------------------- resultado
if [ -n "$emit" ] && [ "$mode" = create ] && [ "$internals_ok" -eq 1 ]; then
  # Nunca se publica un valor sin validar.
  [[ "$actual_sha" =~ ^[0-9a-f]{64}$ ]] && [[ "$actual_size" =~ ^[0-9]+$ ]] || usage_error "SHA-256 o tamano invalidos; no se publica ningun resultado."
  for table in vulnerability cpeEntry knownExploited; do
    [[ "${v[count.$table]:-}" =~ ^[0-9]+$ ]] || usage_error "recuento de $table invalido; no se publica ningun resultado."
  done
  # Escritura atomica: temporal en el MISMO directorio (mismo sistema de archivos) y mv solo si se escribio todo.
  emit_tmp="$(mktemp "$emit_dir_abs/.nvd-emit.XXXXXX")" || usage_error "no se pudo crear el archivo temporal de --emit."
  [ -n "$emit_tmp" ] && [ -f "$emit_tmp" ] || { emit_tmp=""; usage_error "mktemp no creo el archivo temporal de --emit."; }
  if ! {
    echo "update_started_epoch=$job_start"
    echo "odc_sha256=$actual_sha"
    echo "odc_size_bytes=$actual_size"
    echo "nvd_api_last_checked=${v[nvd.api.last.checked]}"
    echo "nvd_api_last_modified=${v[nvd.api.last.modified]}"
    echo "count_vulnerability=${v[count.vulnerability]}"
    echo "count_cpeEntry=${v[count.cpeEntry]}"
    echo "count_knownExploited=${v[count.knownExploited]}"
  } > "$emit_tmp"; then
    usage_error "no se pudo escribir el archivo temporal de --emit; no se publica ningun resultado."
  fi
  mv -f -- "$emit_tmp" "$emit_abs" || usage_error "no se pudo publicar el archivo --emit; no se publica ningun resultado."
  emit_tmp=""
  [ -s "$emit_abs" ] || { rm -f -- "$emit_abs"; usage_error "el archivo --emit quedo vacio; se elimina."; }
fi
echo "Resultado: ACEPTADA por las comprobaciones estructurales del validador."
echo "AVISO: esto NO demuestra que la base NVD este completa. No hay un umbral demostrado de registros, y el SHA-256 solo prueba que el archivo no ha cambiado."
[ -n "$test_only_used" ] && echo "AVISO: se usaron parametros exclusivos de pruebas ($test_only_used ): este resultado NO sirve para aprobar una base real."
exit 0
