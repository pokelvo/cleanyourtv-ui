#!/bin/bash
# Démarrage du conteneur (en root) : installe ce que l'image n'a pas, donne /data à PUID:PGID, puis lance run.sh
# sous cet utilisateur, pour que les fichiers restent modifiables depuis le compte du NAS.
set -u
log() { echo "[$(date '+%F %T')] $*"; }

if ! command -v git >/dev/null || ! command -v unzip >/dev/null || ! command -v zip >/dev/null || ! command -v python3 >/dev/null; then
  log "installation de git, zip, unzip, python3"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq --no-install-recommends git zip unzip python3 ca-certificates curl >/dev/null \
    || log "ERREUR : installation des paquets impossible"
fi

mkdir -p /data/home /data/logs /data/out /data/secrets
chown -R "${PUID:-1000}:${PGID:-10}" /data

exec setpriv --reuid="${PUID:-1000}" --regid="${PGID:-10}" --clear-groups \
  env HOME=/data/home ANDROID_HOME=/data/android-sdk JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}" \
      PATH="${JAVA_HOME:-/opt/java/openjdk}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      TZ="${TZ:-Europe/Paris}" bash /app/run.sh
