#!/usr/bin/env bash
# Marco familiar: copia de seguridad cifrada de lo que NO está en git
# (configuración de Home Assistant, secretos, familia.json, firma de la app...).
#
# - Cifra con gpg (AES256) usando la frase de COPIA_PASS (solo legible por root).
#   Nunca se escribe en disco nada sin cifrar.
# - Deja el resultado en COPIA_DESTINO como copia-marco-AAAA-MM-DD_HHMM.tar.gz.gpg
#   y borra las copias con más de COPIA_SEMANAS semanas.
# - Se lanza como root (familia-copia.service) para poder leer la config de HA.
#
# Restaurar: gpg -d copia-marco-....tar.gz.gpg | sudo tar xzf - -C /
set -euo pipefail
umask 077

DESTINO="${COPIA_DESTINO:-/home/familia/copias}"
PASS="${COPIA_PASS:-/root/copia-marco.pass}"
SEMANAS="${COPIA_SEMANAS:-8}"
DUENO="${COPIA_DUENO:-familia}"

# Rutas relativas a / (separadas por espacios si se cambian con COPIA_RUTAS)
if [[ -n "${COPIA_RUTAS:-}" ]]; then
  read -r -a RUTAS <<< "$COPIA_RUTAS"
else
  RUTAS=(
    srv/familia/homeassistant
    home/familia/marco-familiar-dashboard/servidor/secrets
    home/familia/marco-familiar-dashboard/servidor/config/familia.json
    home/familia/marco-familiar-dashboard/servidor/config/turnos-colores.json
    home/familia/marco-familiar-dashboard/android/dashboard.properties
    home/familia/.android
    home/familia/mosquitto.tgz
    home/familia/mosquitto-passwd
    home/familia/.claude/projects
    home/familia/rescate-hp
  )
fi

if [[ ! -s "$PASS" ]]; then
  echo "ERROR: falta la frase de cifrado en $PASS" >&2
  exit 1
fi

existentes=()
for r in "${RUTAS[@]}"; do
  if [[ -e "/$r" ]]; then
    existentes+=("$r")
  else
    echo "Aviso: /$r no existe, se salta" >&2
  fi
done
if [[ ${#existentes[@]} -eq 0 ]]; then
  echo "ERROR: no hay nada que copiar" >&2
  exit 1
fi

mkdir -p "$DESTINO"
final="$DESTINO/copia-marco-$(date +%F_%H%M).tar.gz.gpg"
tmp="$final.parcial"
trap 'rm -f "$tmp"' EXIT

# tar devuelve 1 si un archivo cambia mientras se lee (la base de datos de HA):
# eso se acepta; cualquier otro fallo de tar o de gpg, no.
set +e
tar czf - -C / --warning=no-file-changed "${existentes[@]}" \
  | gpg --batch --yes --pinentry-mode loopback --passphrase-file "$PASS" \
        --symmetric --cipher-algo AES256 -o "$tmp"
estados=("${PIPESTATUS[@]}")
set -e
if [[ ${estados[0]} -gt 1 || ${estados[1]} -ne 0 ]]; then
  echo "ERROR: tar=${estados[0]} gpg=${estados[1]}; no se guarda la copia" >&2
  exit 1
fi

mv "$tmp" "$final"
chown "$DUENO:" "$final"
chmod 600 "$final"
echo "Copia hecha: $final ($(du -h "$final" | cut -f1))"

# Limpieza: fuera las copias de más de N semanas
find "$DESTINO" -maxdepth 1 -name 'copia-marco-*.tar.gz.gpg' -mtime "+$((SEMANAS * 7))" -print -delete \
  | sed 's/^/Borrada por antigua: /'
