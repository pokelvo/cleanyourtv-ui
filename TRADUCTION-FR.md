# Traduction française de CleanYourTV UI

Le fork `cleanyourtv-ui` gère l'anglais, le portugais, l'espagnol, l'allemand et le russe. Ce dossier ajoute le français sur le même modèle que l'allemand (`German.java`).

## Fichiers fournis

- `launcher-ui/app/src/io/cleanyourtv/ui/French.java` : toutes les chaînes affichées par l'interface (clés = texte anglais, comme `German.java`), plus les onglets, les genres, la météo, les durées, les dates relatives et les chaînes de l'intégration Plex. Compile seul avec `javac`.
- `launcher-ui/app/res/values-fr/strings.xml` : description du service des boutons de la télécommande.

Copie ces deux fichiers à leur place dans le repo cloné, puis branche le français dans le code existant comme ci-dessous.

## Branchements à faire

Le principe : partout où le code teste `Ui.de`, ajouter une branche `Ui.fr` qui utilise `French`. Les numéros de ligne changent avec les mises à jour du fork : cherche les motifs indiqués.

**Ui.java**
- Ajouter `fr` aux booléens de langue (`static volatile boolean ... fr = "fr".equals(language)`) et dans `applyLanguage()`.
- `language()` : accepter `"fr"` quand la TV est en français.
- Les deux méthodes `tr(...)` : ajouter `fr ? French.text(english) :` en tête de la chaîne de ternaires.
- `locale()` : `fr ? French.LOCALE :`.
- `date(long)` : en français, le format `"EEEE d MMMM"` (« samedi 24 octobre »).

**Prefs.java** : `language()` reconnaît `v.startsWith("fr") ? "fr"`.

**SettingsActivity.java** : ajouter `"fr"` au tableau `LANGUAGES` et le libellé `"Français"`.

**Tmdb.java** et **Recs.java** (`lang()`) : `case "fr": return "fr-FR";` et `if (Ui.fr) return French.tmdbLanguage();`.

**HomeView.java**
- `tabs()` : `Ui.fr ? French.TABS :`.
- Les trois lignes « H: … T: » (maxi / mini météo, aussi dans **DreamView.java**) : `Ui.fr ? French.highLow(max, min, gap)` avec le même espacement que la ligne voisine.

**HomeData.java**
- `condition(int, boolean)` : `if (Ui.fr) return French.condition(code, day);`.
- Format des heures de la météo : `Ui.fr ? French.HOUR_PATTERN`.
- Temps relatif (« il y a 5 min ») : `if (Ui.fr) return French.ago(minutes);`.

**Recs.java**
- `PERIOD_LABEL` : `Ui.fr ? French.PERIOD_LABEL :`.
- Nombre de saisons : `Ui.fr ? French.seasons(seasons)`.
- Durée des films : `Ui.fr ? French.duration(minutes)`.
- `genre(name)` : parcourir `French.GENRES` quand `Ui.fr`.

**ExploreActivity.java, DetailActivity.java, GridActivity.java…** : rien à faire, ces écrans passent par `Ui.tr(...)` et sont couverts par `French.text`.

## Vérification

1. Compiler : `launcher-ui/build.sh` doit passer sans erreur.
2. Chercher les chaînes oubliées : toute chaîne passée à `Ui.tr` dont la clé anglaise est absente de `French.java` s'affiche en anglais. Liste-les avec un script (clé = deuxième argument de `Ui.tr`) et ajoute-les.
3. Sur la TV en français : captures de l'accueil, d'Explorer, d'une fiche titre et des paramètres, et vérification qu'aucun texte ne déborde (le français est souvent plus long que l'anglais).
