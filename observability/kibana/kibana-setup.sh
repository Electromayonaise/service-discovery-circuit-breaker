#!/bin/sh
# Carga en Kibana los objetos guardados del taller 4 (kibana-objects.ndjson): patron de indice otel-logs-*,
# busquedas, visualizaciones y el dashboard "Logs de microservicios (OpenTelemetry)".
# Lo ejecuta el contenedor kibana-setup de docker-compose.yml una vez que Kibana esta disponible. Es idempotente:
# overwrite=true reemplaza los objetos con la version del repositorio.
set -eu

KIBANA="${KIBANA_URL:-http://kibana:5601}"

echo "Esperando a Kibana en $KIBANA ..."
until curl -fs "$KIBANA/api/status?v8format=true" | grep -q '"overall":{"level":"available"'; do
  sleep 5
done

echo "Importando objetos guardados ..."
result=$(curl -fsS -X POST "$KIBANA/api/saved_objects/_import?overwrite=true" \
  -H "kbn-xsrf: true" \
  --form file=@/setup/kibana-objects.ndjson)
echo "$result"
# La API responde 200 aunque falle algun objeto: en ese caso el resultado trae "success":false y los errores
if ! echo "$result" | grep -q '"success":true'; then
  echo "La importacion fallo para al menos un objeto" >&2
  exit 1
fi

echo "Dejando otel-logs-* como patron de indice por defecto ..."
curl -fsS -X POST "$KIBANA/api/kibana/settings" \
  -H "kbn-xsrf: true" -H "Content-Type: application/json" \
  -d '{"changes":{"defaultIndex":"otel-logs"}}' > /dev/null

echo "Listo: http://localhost:5601/app/dashboards#/view/otel-dashboard-logs"
