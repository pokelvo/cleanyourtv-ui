#!/usr/bin/env bash
# Met à jour CleanYourTV UI depuis le dépôt GitHub d'origine (le fork), en gardant vos réglages et vos modifications.
#
#   bash launcher-ui/update.sh           vérifie, fusionne, recompile, réinstalle sur la TV
#   bash launcher-ui/update.sh --check   dit seulement s'il y a du nouveau dans launcher-ui (ne change rien)
#
# Ce qui est gardé :
#   - vos réglages : launcher-ui/config.properties, work/, backup/ (ignorés par git, jamais touchés) ;
#   - vos modifications du code : elles sont sur votre branche locale (ma-tv). La mise à jour est fusionnée par-dessus.
# En cas de conflit (le fork a changé les mêmes lignes que vous), rien n'est modifié : le script s'arrête et liste les
# fichiers. Avant chaque fusion, un repère git "avant-maj-<date>" permet de revenir en arrière.
#
# Réglages : TV_HOST, JAVA_HOME, ANDROID_HOME, KEY_SERVICE, SCREENSAVER, depuis l'environnement ou work/update.env
# (fichier personnel, jamais commité ; mêmes noms, une ligne NOM=valeur chacun).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
BRANCH=main

say() { printf '\n== %s\n' "$*"; }
die() { printf '\nERREUR : %s\n' "$*" >&2; exit 1; }

if [ -f work/update.env ]; then
  while IFS='=' read -r k v; do
    case "$k" in TV_HOST|JAVA_HOME|ANDROID_HOME|KEY_SERVICE|SCREENSAVER) [ -n "${!k:-}" ] || export "$k=$v" ;; esac
  done < <(grep -E '^[A-Z_]+=' work/update.env | tr -d '\r')
fi
CHECK=0; [ "${1:-}" = "--check" ] && CHECK=1

# Avec le NAS (nas_url dans config.properties), c'est lui qui fusionne et compile : une fusion faite ici le
# contredirait. Mise à jour depuis la TV (picto aux flèches) ; pour le code, launcher-ui/nas.sh pull / push.
if grep -qE '^[[:space:]]*nas_url[[:space:]]*=[[:space:]]*[^[:space:]]' launcher-ui/config.properties 2>/dev/null && [ "${FORCE_PC:-0}" != 1 ]; then
  die "le NAS gère les mises à jour (nas_url). Utilisez le picto de la TV, ou bash launcher-ui/nas.sh status.
Pour forcer quand même une mise à jour depuis le PC : FORCE_PC=1 bash launcher-ui/update.sh"
fi

# --- 1. L'état du dépôt
here=$(git rev-parse --abbrev-ref HEAD)
[ "$here" != HEAD ] || die "git n'est sur aucune branche. Revenez sur la vôtre : git switch ma-tv"
[ "$here" != "$BRANCH" ] || die "vous êtes sur $BRANCH, la branche du fork. Vos modifications sont sur ma-tv : git switch ma-tv"
! git rev-parse -q --verify MERGE_HEAD >/dev/null || die "une fusion est déjà en cours. Terminez-la ou annulez-la : git merge --abort"

say "Recherche des nouveautés sur GitHub ($(git remote get-url origin), branche $BRANCH)"
git fetch -q origin "$BRANCH"
NEW=$(git log --format='  - %s (%ad)' --date=short "HEAD..origin/$BRANCH" -- launcher-ui)
if [ -z "$NEW" ]; then
  echo "CleanYourTV UI est à jour : rien de nouveau dans launcher-ui."
  exit 0
fi
echo "Nouveau dans launcher-ui :"
echo "$NEW"
[ "$CHECK" = 0 ] || exit 0

[ -n "${TV_HOST:-}" ] || die "indiquez l'IP de la TV : TV_HOST=192.168.1.50 bash launcher-ui/update.sh (ou dans work/update.env)"
PY=tools/venv/Scripts/python.exe; [ -x "$PY" ] || PY=tools/venv/bin/python
[ -x "$PY" ] || die "Python des outils introuvable (tools/venv). Voir README, section Tools."
say "Vérification que la TV ($TV_HOST) répond"
MSYS_NO_PATHCONV=1 TV_HOST="$TV_HOST" "$PY" -I tools/tv.py --probe >/dev/null 2>&1 \
  || die "la TV ne répond pas sur ADB. Allumez-la, vérifiez le débogage réseau, puis relancez. Rien n'a été modifié."

# --- 2. Vos modifications à l'abri
# Les fichiers déjà suivis par git sont enregistrés sur votre branche. Un fichier nouveau et non ignoré arrête tout :
# il faut décider s'il doit être gardé dans git (il pourrait contenir un secret).
NEWFILES=$(git ls-files --others --exclude-standard)
if [ -n "$NEWFILES" ]; then
  echo "$NEWFILES"
  die "ces fichiers ne sont ni enregistrés dans git ni ignorés. Enregistrez-les (git add + git commit) ou ajoutez-les à .gitignore, puis relancez."
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
  say "Enregistrement de vos modifications en cours sur $here"
  git commit -qam "Sauvegarde avant mise à jour ($(date '+%Y-%m-%d %H:%M'))"
fi
MARK="avant-maj-$(date '+%Y%m%d-%H%M%S')"
git tag "$MARK"

# --- 3. Fusion
say "Fusion de origin/$BRANCH dans $here"
if ! git merge --no-edit -q "origin/$BRANCH"; then
  CONFLICTS=$(git diff --name-only --diff-filter=U)
  git merge --abort
  echo "Fichiers en conflit :"
  echo "$CONFLICTS" | sed 's/^/  - /'
  die "le fork a modifié les mêmes passages que vous. La fusion est annulée, rien n'a changé (ni le code, ni la TV).
Demandez à Claude de faire la mise à jour : il résoudra ces conflits."
fi

# --- 4. Nouveaux réglages proposés par le fork
if [ -f launcher-ui/config.properties ]; then
  MISSING=$(comm -23 <(sed -nE 's/^[[:space:]]*#?[[:space:]]*([a-z_]+)[[:space:]]*=.*/\1/p' launcher-ui/config.example.properties | sort -u) \
                     <(sed -nE 's/^[[:space:]]*#?[[:space:]]*([a-z_]+)[[:space:]]*=.*/\1/p' launcher-ui/config.properties | sort -u))
  if [ -n "$MISSING" ]; then
    say "Réglages nouveaux dans config.example.properties (absents de votre config.properties, valeurs par défaut utilisées) :"
    echo "$MISSING" | sed 's/^/  - /'
  fi
fi

# --- 5. Compilation, puis installation
say "Compilation avec votre config.properties"
if ! bash launcher-ui/build.sh; then
  git reset -q --hard "$MARK"
  die "la compilation a échoué. Le code est revenu à l'état d'avant la mise à jour ($MARK) ; la TV n'a pas changé."
fi
say "Installation sur la TV"
SKIP_BUILD=1 bash launcher-ui/build-and-install.sh || die "l'installation a échoué (voir plus haut). Le code est à jour ; pour relancer seulement l'installation :
  TV_HOST=$TV_HOST bash launcher-ui/build-and-install.sh
Pour revenir en arrière : git reset --hard $MARK"

say "Mise à jour terminée"
echo "Pour revenir à la version d'avant : git reset --hard $MARK, puis bash launcher-ui/build-and-install.sh"
