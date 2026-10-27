#!/usr/bin/env bash
# Comprueba que las pruebas de integracion que requieren MySQL REAL se ejecutaron de verdad.
#
# Las clases gated usan @EnabledIfEnvironmentVariable(named = "NEXUS_TEST_MYSQL_URL"): si la variable falta,
# JUnit las omite y Maven termina en verde. Este script falla si alguna de esas clases no tiene informe de
# Surefire, no ejecuto ninguna prueba, o tiene pruebas omitidas, fallidas o con error.
#
# Uso (desde market/): bash ../scripts/ci/verify-mysql-tests-ran.sh [directorio-de-informes]
set -euo pipefail

REPORTS="${1:-target/surefire-reports}"
TESTS="src/test/java"

mapfile -t gated < <(grep -rl 'NEXUS_TEST_MYSQL_URL' "$TESTS" --include='*.java' | sort)
if [ "${#gated[@]}" -eq 0 ]; then
  echo "::error::No se encontro ninguna clase de prueba que use NEXUS_TEST_MYSQL_URL en $TESTS"
  exit 1
fi

failed=0
total=0
for file in "${gated[@]}"; do
  class="${file#"$TESTS"/}"
  class="${class%.java}"
  class="${class//\//.}"
  report="$REPORTS/$class.txt"
  if [ ! -f "$report" ]; then
    echo "::error::Sin informe de Surefire para $class (no se ejecuto)"
    failed=1
    continue
  fi
  line="$(grep -m1 'Tests run:' "$report" || true)"
  run="$(sed -E 's/.*Tests run: ([0-9]+),.*/\1/' <<<"$line")"
  fails="$(sed -E 's/.*Failures: ([0-9]+),.*/\1/' <<<"$line")"
  errors="$(sed -E 's/.*Errors: ([0-9]+),.*/\1/' <<<"$line")"
  skipped="$(sed -E 's/.*Skipped: ([0-9]+).*/\1/' <<<"$line")"
  echo "$class: ejecutadas=$run fallos=$fails errores=$errors omitidas=$skipped"
  if ! [[ "$run" =~ ^[0-9]+$ ]] || [ "$run" -eq 0 ] || [ "$fails" != 0 ] || [ "$errors" != 0 ] || [ "$skipped" != 0 ]; then
    echo "::error::$class no esta completa y verde: $line"
    failed=1
  else
    total=$((total + run))
  fi
done

if [ "$failed" -ne 0 ]; then
  echo "::error::Las pruebas MySQL no se ejecutaron por completo (revise NEXUS_TEST_MYSQL_URL y el servicio MySQL)."
  exit 1
fi
echo "OK: ${#gated[@]} clases MySQL, $total pruebas ejecutadas, 0 omitidas."
