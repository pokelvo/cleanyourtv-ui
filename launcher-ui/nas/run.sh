#!/bin/bash
# Sous PUID:PGID. Une seule fois : les outils Android (licence acceptée par le propriétaire le 25/10/2026) et le dépôt
# (depuis /data/seed.bundle, envoyé par le PC). Ensuite le service, relancé s'il s'arrête (server.py s'arrête de
# lui-même quand son fichier change, pour prendre la nouvelle version).
set -u
log() { echo "[$(date '+%F %T')] $*"; }
SDK=/data/android-sdk
REPO=/data/repo

if [ ! -x "$SDK/build-tools/34.0.0/aapt2" ] || [ ! -f "$SDK/platforms/android-34/android.jar" ]; then
  log "téléchargement des outils Android"
  rm -rf /tmp/clt && mkdir -p /tmp/clt "$SDK/cmdline-tools"
  if curl -fsSL -o /tmp/clt/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
     && unzip -q /tmp/clt/clt.zip -d /tmp/clt; then
    rm -rf "$SDK/cmdline-tools/latest" && mv /tmp/clt/cmdline-tools "$SDK/cmdline-tools/latest"
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "build-tools;34.0.0" "platforms;android-34" \
      | grep -vE '^\[|^$' || true
  fi
  rm -rf /tmp/clt
  [ -x "$SDK/build-tools/34.0.0/aapt2" ] && log "outils Android prêts" || log "ERREUR : outils Android absents"
fi

if [ ! -d "$REPO/.git" ] && [ -f /data/seed.bundle ]; then
  log "création du dépôt depuis seed.bundle"
  git clone -q -b ma-tv /data/seed.bundle "$REPO" \
    && git -C "$REPO" remote set-url origin https://github.com/matheus-caldeira/cleanyourtv-ui \
    && git -C "$REPO" config user.name "cytv (NAS)" \
    && git -C "$REPO" config user.email "cytv@localhost" \
    && git -C "$REPO" fetch -q origin main \
    && log "dépôt prêt sur $(git -C "$REPO" rev-parse --short HEAD)" \
    || log "ERREUR : création du dépôt"
fi

while true; do
  python3 /app/server.py
  log "service arrêté (code $?), relance dans 3 s"
  sleep 3
done
