#!/bin/sh
# Genera trafico continuo contra los dos servicios para que los dashboards tengan datos:
#   docker compose --profile trafico up -d trafico     (detener: docker compose --profile trafico stop trafico)
# Cada ciclo (~1 s) llama a los endpoints del catalogo y del servicio de cursos; uno de cada 10 ciclos pide un
# curso que no existe (GET /99 -> HTTP 500 en fx-course-service). Para provocar fallbacks en el catalogo y abrir el
# circuito: docker compose stop fx-course-service (y luego start).
CATALOG="${CATALOG_URL:-http://fx-catalog-service:8002}"
COURSES="${COURSE_URL:-http://fx-course-service:8001}"
PAUSE="${PAUSE_SECONDS:-1}"

call() {
  code=$(curl -s -o /dev/null -m 10 -w "%{http_code}" "$1") || code="000"
  printf '%s %s\n' "$code" "$1"
}

i=0
while true; do
  i=$((i + 1))
  call "$CATALOG/"
  call "$CATALOG/catalog"
  call "$CATALOG/firstcourse"
  call "$COURSES/courses"
  call "$COURSES/$(( (i % 3) + 1 ))"
  [ $((i % 10)) -eq 0 ] && call "$COURSES/99"
  sleep "$PAUSE"
done
