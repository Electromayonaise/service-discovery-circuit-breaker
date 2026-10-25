#!/usr/bin/env bash
# Llama repetidamente a un endpoint de fx-catalog-service y muestra, por cada llamada,
# la latencia, la respuesta y el estado del Circuit Breaker "courseService" (via Actuator).
# Equivalente en Bash de demo-circuit-breaker.ps1 (requiere curl y python3).
#
# Uso:  ./scripts/demo-circuit-breaker.sh [-p /catalog] [-n 10] [-d 500] [-b http://localhost:8002] [-w 90] [-r]
#   -w  caracteres de la respuesta que se muestran por llamada
#   -r  termina antes de -n llamadas si el circuito, tras haber salido de CLOSED, vuelve a CLOSED
path="/catalog"; calls=10; delay_ms=500; base_url="http://localhost:8002"; width=90; until_recovered=false
while getopts "p:n:d:b:w:r" opt; do
    case $opt in
        p) path=$OPTARG ;;
        n) calls=$OPTARG ;;
        d) delay_ms=$OPTARG ;;
        b) base_url=$OPTARG ;;
        w) width=$OPTARG ;;
        r) until_recovered=true ;;
        *) echo "Uso: $0 [-p path] [-n llamadas] [-d delay_ms] [-b base_url] [-w ancho] [-r]" >&2; exit 2 ;;
    esac
done

circuit_state() {
    curl -s --max-time 2 "$base_url/actuator/circuitbreakers" | python3 -c '
import json, sys
try:
    s = json.load(sys.stdin)["circuitBreakers"]["courseService"]
    print("%s (fallidas=%s/%s, rechazadas=%s)" % (s["state"], s["failedCalls"], s["bufferedCalls"], s["notPermittedCalls"]))
except Exception:
    print("desconocido")'
}

for ((i = 1; i <= calls; i++)); do
    start=$(date +%s%N)
    body=$(curl -s --max-time 30 "$base_url$path") || body="ERROR: curl exit $?"
    elapsed=$(( ($(date +%s%N) - start) / 1000000 ))
    (( ${#body} > width )) && body="${body:0:width}..."
    if [[ $body == Fallback* || $body == *"(Fallback"* || $body == ERROR* ]]; then color=33; else color=32; fi
    state=$(circuit_state)
    printf "#%-3s %6s ms  [%s]  \033[%sm%s\033[0m\n" "$i" "$elapsed" "$state" "$color" "$body"
    if $until_recovered; then
        [[ $state == CLOSED* ]] && ${left_closed:-false} && break
        [[ $state != CLOSED* ]] && left_closed=true
    fi
    sleep "$(awk "BEGIN{print $delay_ms/1000}")"
done
