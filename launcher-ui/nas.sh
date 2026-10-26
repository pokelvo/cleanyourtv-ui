#!/usr/bin/env bash
# La branche ma-tv entre le PC et le NAS (service launcher-ui/nas). Le NAS est la référence : il fusionne les mises à
# jour du dépôt d'origine et compile pour la TV.
#
#   bash launcher-ui/nas.sh pull     récupère ma-tv du NAS (à faire avant de modifier le code sur le PC)
#   bash launcher-ui/nas.sh push     envoie au NAS les commits faits sur le PC ; la TV propose alors la mise à jour
#   bash launcher-ui/nas.sh status   où en est le service (JSON)
#
# NAS_URL et NAS_TOKEN viennent de l'environnement ou de work/update.env (fichier personnel, jamais commité).
set -euo pipefail
cd "$(dirname "$0")/.."
REF=refs/nas/ma-tv

die() { printf 'ERREUR : %s\n' "$*" >&2; exit 1; }

if [ -f work/update.env ]; then
  while IFS='=' read -r k v; do
    case "$k" in NAS_URL|NAS_TOKEN) [ -n "${!k:-}" ] || export "$k=$v" ;; esac
  done < <(grep -E '^[A-Z_]+=' work/update.env | tr -d '\r')
fi
[ -n "${NAS_URL:-}" ] && [ -n "${NAS_TOKEN:-}" ] || die "NAS_URL et NAS_TOKEN manquent (work/update.env)."

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

on_ma_tv() {
  [ "$(git rev-parse --abbrev-ref HEAD)" = ma-tv ] || die "passez d'abord sur ma-tv : git switch ma-tv"
}
clean() {
  git diff --quiet && git diff --cached --quiet || die "des modifications ne sont pas commitées. Commitez-les d'abord."
}
nas_head() {
  curl -fsS "$NAS_URL/api/status" | sed -nE 's/.*"head": *"([0-9a-f]+)".*/\1/p'
}

pull() {
  on_ma_tv
  clean
  local have
  have="$(git rev-parse -q --verify "$REF" || true)"
  curl -fsS -H "X-Token: $NAS_TOKEN" -D "$TMP/h" -o "$TMP/b" "$NAS_URL/api/bundle?have=$have" || die "le NAS ne répond pas ($NAS_URL)."
  if grep -qi '^content-type: application/json' "$TMP/h"; then
    echo "Le PC a déjà la version du NAS."
  else
    git bundle verify -q "$TMP/b" >/dev/null || die "bundle incomplet."
    git fetch -q "$TMP/b" "+ma-tv:$REF"
  fi
  if git merge-base --is-ancestor "$REF" HEAD; then
    echo "ma-tv est à jour ($(git rev-parse --short HEAD))."
  elif git merge-base --is-ancestor HEAD "$REF"; then
    git merge -q --ff-only "$REF"
    echo "ma-tv avancée à $(git rev-parse --short HEAD) :"
    git log --format='  - %s' "HEAD@{1}..HEAD" | head -20
  else
    # Le PC et le NAS ont chacun des commits : on fusionne (un conflit est laissé à résoudre).
    git merge --no-edit "$REF" || die "conflit en fusionnant la version du NAS : résolvez-le, commitez, puis nas.sh push."
    echo "Versions du PC et du NAS fusionnées. Pensez à nas.sh push."
  fi
}

push() {
  on_ma_tv
  clean
  local head
  head="$(nas_head)"
  [ -n "$head" ] || die "le NAS ne répond pas ($NAS_URL)."
  [ "$head" != "$(git rev-parse HEAD)" ] || { echo "Le NAS a déjà cette version."; return; }
  git cat-file -e "$head^{commit}" 2>/dev/null && git merge-base --is-ancestor "$head" HEAD \
    || die "le NAS a des commits que le PC n'a pas : faites d'abord bash launcher-ui/nas.sh pull"
  git bundle create -q "$TMP/b" ma-tv "^$head"
  curl -sS --fail-with-body -H "X-Token: $NAS_TOKEN" -H "Content-Type: application/octet-stream" --data-binary "@$TMP/b" \
    "$NAS_URL/api/push" -o "$TMP/r" || die "le NAS a refusé : $(cat "$TMP/r" 2>/dev/null)"
  git update-ref "$REF" HEAD
  echo "Envoyé au NAS : ma-tv = $(git rev-parse --short HEAD). Si l'appli a changé (launcher-ui/app, build.sh), la TV proposera la mise à jour."
}

case "${1:-}" in
  pull) pull ;;
  push) push ;;
  status) curl -fsS "$NAS_URL/api/status?build=$(git rev-parse HEAD)"; echo ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
