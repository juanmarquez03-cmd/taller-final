#!/usr/bin/env bash
# Consulta /api/version a través de Traefik y muestra qué versión y qué pod responde.
#
#   bash scripts/probar.sh        consulta cada 0,5 s sin parar (Ctrl+C para salir).
#                                 Útil durante el cambio de versión.
#   bash scripts/probar.sh 20     hace 20 peticiones y cuenta cuántas atendió cada
#                                 pod. Útil para comprobar el round robin.
#
# Por defecto entra por el master. Otra dirección:  TRAEFIK=http://IP:30080 bash scripts/probar.sh

TRAEFIK="${TRAEFIK:-http://100.105.119.107:30080}"
N="$1"

consultar() {
  r=$(curl -s --max-time 2 "$TRAEFIK/api/version") || { echo "$(date +%H:%M:%S) | [sin respuesta]"; return; }
  version=$(echo "$r" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
  contenedor=$(echo "$r" | sed -n 's/.*"contenedor":"\([^"]*\)".*/\1/p')
  echo "$(date +%H:%M:%S) | v$version | $contenedor"
}

if [ -z "$N" ]; then
  while true; do
    consultar
    sleep 0.5
  done
else
  salida=$(for i in $(seq 1 "$N"); do consultar; done)
  echo "$salida"
  echo "----- peticiones por pod -----"
  echo "$salida" | awk -F' [|] ' '{print $3}' | sort | uniq -c
fi
