package io.cleanyourtv.ui;

import java.util.Locale;

/**
 * French copy for text drawn directly by the custom UI. Source keys are the existing English copy (as in German.java),
 * plus the French versions of the other per-language bits (tabs, genres, weather, dates) so each call site only needs
 * an extra `Ui.fr ? French.xxx :` branch.
 */
final class French {
    private French() {}

    static final Locale LOCALE = Locale.FRANCE;
    static final String[] TABS = {"Explorer", "Films", "Séries"};
    static final String[] PERIOD_LABEL = {"CE MATIN", "CET APRÈS-MIDI", "CE SOIR"};
    static final String[][] GENRES = {
        {"Action & Adventure", "Action & Aventure"}, {"War & Politics", "Guerre & Politique"},
        {"Sci-Fi & Fantasy", "Science-Fiction & Fantastique"}, {"Kids", "Enfants"},
        {"News", "Actualités"}, {"Soap", "Feuilleton"}, {"Talk", "Talk-show"},
    };

    /** TMDB language. */
    static String tmdbLanguage() { return "fr-FR"; }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code == 0) return day ? "Ensoleillé" : "Dégagé";
        if (code == 1) return day ? "Plutôt ensoleillé" : "Plutôt dégagé";
        if (code == 2) return "Partiellement nuageux";
        if (code == 3) return "Nuageux";
        if (code == 45 || code == 48) return "Brouillard";
        if (code >= 51 && code <= 57) return "Bruine";
        if (code == 65 || code == 67 || code == 82) return "Forte pluie";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neige";
        if (code >= 95) return "Orages";
        return "Pluie";
    }

    /** "il y a 5 min", "il y a 2 h". */
    static String ago(long minutes) {
        return minutes < 60 ? "il y a " + minutes + " min" : "il y a " + (minutes / 60) + " h";
    }

    /** "1 saison", "3 saisons". */
    static String seasons(int n) { return n == 1 ? "1 saison" : n + " saisons"; }

    /** "1 h 52", "45 min". */
    static String duration(int minutes) {
        return minutes >= 60 ? (minutes / 60) + " h " + String.format(Locale.FRANCE, "%02d", minutes % 60) : minutes + " min";
    }

    /** Hour labels in the weather row: "14 h". */
    static final String HOUR_PATTERN = "HH' h'";

    /** "Max. 18°  Min. 9°". */
    static String highLow(String max, String min, String gap) { return "Max. " + max + gap + "Min. " + min; }

    static String text(String english) {
        switch (english) {
            case " Next hours: ": return " Prochaines heures : ";
            case " Sunrise ": return " Lever du soleil ";
            case " Sunset ": return " Coucher du soleil ";
            case " ep.": return " ép.";
            case " of ": return " sur ";
            case " of 2": return " sur 2";
            case " of 3": return " sur 3";
            case " seasons": return " saisons";
            case " titles · data from TMDB": return " titres · données TMDB";
            case " titles": return " titres";
            case "": return "";
            case ", low ": return ", minimale ";
            case ", position ": return ", position ";
            case ", selected": return ", sélectionné";
            case ", tab ": return ", onglet ";
            case ". High ": return ". Maximale ";
            case ". In use": return ". En service";
            case "1 season": return "1 saison";
            case "1 title": return "1 titre";
            case "24h change": return "variation sur 24 h";
            case "A PNG's URL (empty: the app's icon)": return "URL d’un PNG (vide : icône de l’app)";
            case "ADB message too large": return "Message ADB trop volumineux";
            case "ADB refused the command": return "ADB a refusé la commande";
            case "Accept “Allow USB debugging?” on the TV (tick “Always allow”).": return "Acceptez « Autoriser le débogage USB ? » sur la TV (cochez « Toujours autoriser »).";
            case "Account, services and the menu's order": return "Compte, services et ordre du menu";
            case "Acting": return "Interprétation";
            case "Action & Adventure": return "Action & Aventure";
            case "Add to a list": return "Ajouter à une liste";
            case "Added to ": return "Ajouté à ";
            case "All Apps": return "Toutes les apps";
            case "All ages": return "Tous publics";
            case "All": return "Tout";
            case "Android didn't accept the new home screen": return "Android n’a pas accepté le nouvel écran d’accueil";
            case "Animation": return "Animation";
            case "Anime": return "Anime";
            case "Any year": return "Toutes les années";
            case "App settings": return "Paramètres de l’app";
            case "Apply": return "Appliquer";
            case "Automatic · ": return "Automatique · ";
            case "Automatic": return "Automatique";
            case "Background color, e.g. #E53935": return "Couleur de fond, par ex. #E53935";
            case "Because you watched ": return "Parce que vous avez regardé ";
            case "Before 1990": return "Avant 1990";
            case "Behind the camera": return "Derrière la caméra";
            case "Born: ": return "Naissance : ";
            case "Buy": return "Acheter";
            case "COMPARISON": return "COMPARAISON";
            case "Cancel": return "Annuler";
            case "Canceled": return "Annulée";
            case "Cartoons": return "Dessins animés";
            case "Cast": return "Distribution";
            case "Categories (one or more)": return "Catégories (une ou plusieurs)";
            case "Categories": return "Catégories";
            case "City": return "Ville";
            case "Clear": return "Dégagé";
            case "Close": return "Fermer";
            case "Cloudy": return "Nuageux";
            case "Connect TMDB account": return "Connecter un compte TMDB";
            case "Connect your TMDB account in Settings to rate.": return "Connectez votre compte TMDB dans les paramètres pour noter.";
            case "Connect your TMDB account in Settings to see favorites and ratings.": return "Connectez votre compte TMDB dans les paramètres pour voir vos favoris et vos notes.";
            case "Connect your TMDB account": return "Connectez votre compte TMDB";
            case "Connecting…": return "Connexion…";
            case "Couldn't add: ": return "Ajout impossible : ";
            case "Couldn't check Free My TV: ": return "Vérification de Free My TV impossible : ";
            case "Couldn't connect: ": return "Connexion impossible : ";
            case "Couldn't create: ": return "Création impossible : ";
            case "Couldn't favorite: ": return "Ajout aux favoris impossible : ";
            case "Couldn't fetch the services: ": return "Impossible de récupérer les services : ";
            case "Couldn't fetch titles now. Check the TV's internet.": return "Impossible de récupérer les titres pour l’instant. Vérifiez la connexion internet de la TV.";
            case "Couldn't go back to the original: ": return "Retour à l’original impossible : ";
            case "Couldn't load: ": return "Chargement impossible : ";
            case "Couldn't open ": return "Impossible d’ouvrir ";
            case "Couldn't open TV Settings": return "Impossible d’ouvrir les paramètres de la TV";
            case "Couldn't open the season: ": return "Impossible d’ouvrir la saison : ";
            case "Couldn't open this person: ": return "Impossible d’ouvrir cette personne : ";
            case "Couldn't open this title: ": return "Impossible d’ouvrir ce titre : ";
            case "Couldn't save the rating: ": return "Impossible d’enregistrer la note : ";
            case "Couldn't save: ": return "Enregistrement impossible : ";
            case "Couldn't search now: ": return "Recherche impossible pour l’instant : ";
            case "Couldn't turn on Free My TV: ": return "Impossible d’activer Free My TV : ";
            case "Create": return "Créer";
            case "Created by ": return "Créée par ";
            case "Crypto": return "Crypto";
            case "Dark": return "Sombre";
            case "Data from TMDB": return "Données TMDB";
            case "Details": return "Détails";
            case "Died: ": return "Décès : ";
            case "Directed by ": return "Réalisé par ";
            case "Directing": return "Réalisation";
            case "Disconnect": return "Déconnecter";
            case "Disney and Globoplay: the TV itself controls those buttons.": return "Disney et Globoplay : la TV gère elle-même ces boutons.";
            case "Dollar": return "Dollar";
            case "Drizzle": return "Bruine";
            case "Ended": return "Terminée";
            case "Error: ": return "Erreur : ";
            case "Everything": return "Tout";
            case "Explore movies and shows": return "Explorer films et séries";
            case "Explore": return "Explorer";
            case "Favorite": return "Favori";
            case "Favorites": return "Favoris";
            case "Fetching titles…": return "Récupération des titres…";
            case "Filters": return "Filtres";
            case "Fog": return "Brouillard";
            case "For You": return "Pour vous";
            case "For the kids": return "Pour les enfants";
            case "Free My TV's home screen crashed 3 times in a row; I switched back to the original one.": return "L’écran d’accueil Free My TV a planté 3 fois de suite ; retour à l’écran d’origine.";
            case "Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…": return "L’écran d’accueil Free My TV a rencontré un problème.\nRetour à l’écran d’accueil d’origine de la TV…";
            case "Free memory": return "Mémoire libre";
            case "Free with ads": return "Gratuit avec pubs";
            case "Free": return "Gratuit";
            case "From your TMDB account": return "Depuis votre compte TMDB";
            case "Getting ready…": return "Préparation…";
            case "HDMI Inputs": return "Entrées HDMI";
            case "Heavy Rain": return "Forte pluie";
            case "Hidden": return "Masqué";
            case "Hide from the menu": return "Masquer du menu";
            case "Hide “": return "Masquer « ";
            case "Hide": return "Masquer";
            case "Home Screen": return "Écran d’accueil";
            case "Home Screen, ": return "Écran d’accueil, ";
            case "Home Settings": return "Réglages accueil";   // fits the drawer shortcut tile
            case "Home button": return "Bouton Accueil";
            case "Home screen memory": return "Mémoire de l’écran d’accueil";
            case "Home screen": return "Écran d’accueil";
            case "Home": return "Accueil";
            case "I've seen it": return "Déjà vu";
            case "In my list": return "Dans ma liste";
            case "Inputs": return "Sources";
            case "Invalid color: use #RRGGBB.": return "Couleur invalide : utilisez #RRGGBB.";
            case "Kept on this TV. Connect your TMDB account in Settings to sync.": return "Enregistré sur cette TV. Connectez votre compte TMDB dans les paramètres pour synchroniser.";
            case "Keywords": return "Mots-clés";
            case "Kids": return "Enfants";
            case "Language": return "Langue";
            case "Light": return "Clair";
            case "Like ": return "Similaires à ";
            case "List created": return "Liste créée";
            case "List name": return "Nom de la liste";
            case "List": return "Liste";
            case "Live TV": return "TV en direct";
            case "Loading the services…": return "Chargement des services…";
            case "Loading…": return "Chargement…";
            case "Logo and color": return "Logo et couleur";
            case "Logo and color: ": return "Logo et couleur : ";
            case "Measuring Free My TV…": return "Mesure de Free My TV…";
            case "Measuring the original home screen…": return "Mesure de l’écran d’accueil d’origine…";
            case "Mostly Clear": return "Plutôt dégagé";
            case "Mostly Sunny": return "Plutôt ensoleillé";
            case "Move down": return "Descendre";
            case "Move up": return "Monter";
            case "Move": return "Déplacer";
            case "Movie": return "Film";
            case "Movie, show or person": return "Film, série ou personne";
            case "Movies": return "Films";
            case "Movies, shows and people · filter by type, category and year": return "Films, séries et personnes · filtrer par type, catégorie et année";
            case "Moving ": return "Déplacement de ";
            case "My list and ratings come from your account and are saved to it.": return "Ma liste et mes notes viennent de votre compte et y sont enregistrées.";
            case "My list": return "Ma liste";
            case "My watchlist (TMDB)": return "Ma liste à voir (TMDB)";
            case "NOTICES": return "MENTIONS";
            case "Name in the menu": return "Nom dans le menu";
            case "Netflix button": return "Bouton Netflix";
            case "New list": return "Nouvelle liste";
            case "New list…": return "Nouvelle liste…";
            case "New movies": return "Nouveaux films";
            case "New shows": return "Nouvelles séries";
            case "New": return "Nouveautés";
            case "News": return "Actualités";
            case "Next episode: ": return "Prochain épisode : ";
            case "No TMDB key: add yours to launcher-ui/config.properties and install again.": return "Pas de clé TMDB : ajoutez la vôtre dans launcher-ui/config.properties et réinstallez.";
            case "No TMDB key: see the README (config.properties).": return "Pas de clé TMDB : voir le README (config.properties).";
            case "No ads, with the new screensaver": return "Sans pubs, avec le nouvel écran de veille";
            case "No app on the TV": return "Aucune app sur la TV";
            case "No biography on TMDB.": return "Pas de biographie sur TMDB.";
            case "No city with that name.": return "Aucune ville de ce nom.";
            case "No configurable buttons: push your model's remote.json (launcher-ui/config.sh --remote).": return "Aucun bouton configurable : envoyez le remote.json de votre modèle (launcher-ui/config.sh --remote).";
            case "No weather data right now.": return "Pas de données météo pour l’instant.";
            case "None yet.": return "Aucune pour l’instant.";
            case "Not connected": return "Non connecté";
            case "Not installed": return "Non installée";
            case "Not on any streaming service in the region right now.": return "Disponible sur aucun service de streaming dans votre région pour l’instant.";
            case "Nothing found.": return "Aucun résultat.";
            case "Nothing here yet. Open a title to add it.": return "Rien pour l’instant. Ouvrez un titre pour l’ajouter.";
            case "Nothing matches this filter.": return "Aucun titre ne correspond à ce filtre.";
            case "Nothing": return "Rien";
            case "OK adds or removes · ← → change the order": return "OK ajoute ou retire · ← → changent l’ordre";
            case "OK or the arrows change the value.": return "OK ou les flèches changent la valeur.";
            case "OK picks what the button does. The rest work as always.": return "OK choisit l’action du bouton. Les autres fonctionnent comme d’habitude.";
            case "OK picks, Back cancels.": return "OK choisit, Retour annule.";
            case "Off": return "Désactivé";
            case "On the TV: tick “Always allow” and press OK.": return "Sur la TV : cochez « Toujours autoriser » et appuyez sur OK.";
            case "On the air": return "En cours de diffusion";
            case "On your services · data from TMDB": return "Sur vos services · données TMDB";
            case "On · ": return "Activé · ";
            case "On": return "Activé";
            case "Original mode, but I couldn't turn back on: ": return "Mode d’origine, mais réactivation impossible : ";
            case "Original mode: the factory home screen, screensaver and apps are back.": return "Mode d’origine : l’écran d’accueil, l’écran de veille et les apps d’usine sont de retour.";
            case "Original": return "Origine";
            case "Partly Cloudy": return "Partiellement nuageux";
            case "Pick categories and year in the filter; save it to the menu": return "Choisissez catégories et année dans le filtre, puis enregistrez-le dans le menu";
            case "Pick something in the filter first.": return "Choisissez d’abord un critère dans le filtre.";
            case "Pick the app that opens each service. Picks and Explore only use services with an app, unless you show them all.": return "Choisissez l’app qui ouvre chaque service. Les suggestions et Explorer n’utilisent que les services qui ont une app, sauf si vous les affichez tous.";
            case "Picked for you": return "Sélectionné pour vous";
            case "Point your phone's camera, sign in to TMDB and tap Approve.": return "Visez avec l’appareil photo de votre téléphone, connectez-vous à TMDB et touchez Approuver.";
            case "Preview": return "Aperçu";
            case "Prime Video button": return "Bouton Prime Video";
            case "Production": return "Production";
            case "Quotes widget": return "Widget cours";
            case "RECOMMENDATIONS": return "RECOMMANDATIONS";
            case "Rain": return "Pluie";
            case "Rate": return "Noter";
            case "Recent": return "Récents";
            case "Recommended for you": return "Recommandé pour vous";
            case "Release": return "Sortie";
            case "Remote Buttons": return "Boutons de la télécommande";
            case "Remove my rating": return "Supprimer ma note";
            case "Remove “": return "Supprimer « ";
            case "Remove": return "Supprimer";
            case "Rent": return "Louer";
            case "Restoring the original home screen…": return "Restauration de l’écran d’accueil d’origine…";
            case "Reviews": return "Critiques";
            case "Save changes": return "Enregistrer les modifications";
            case "Save to menu": return "Enregistrer dans le menu";
            case "Save": return "Enregistrer";
            case "Saved filter": return "Filtre enregistré";
            case "Saved to the menu": return "Enregistré dans le menu";
            case "Sci-Fi & Fantasy": return "Science-Fiction & Fantastique";
            case "Screensaver": return "Écran de veille";
            case "Screensaver, preview": return "Écran de veille, aperçu";
            case "Search": return "Rechercher";
            case "Search, Home and My list always stay on top. OK on an item: move, hide or remove.": return "Rechercher, Accueil et Ma liste restent toujours en haut. OK sur un élément : déplacer, masquer ou supprimer.";
            case "Searching…": return "Recherche…";
            case "Season ": return "Saison ";
            case "Seasons": return "Saisons";
            case "See more": return "Voir plus";
            case "Seen": return "Vus";
            case "Series": return "Série";
            case "Service": return "Service";
            case "Services and apps": return "Services et apps";
            case "Services": return "Services";
            case "Settings": return "Paramètres";
            case "Shelf": return "Étagère";
            case "Shortcuts": return "Raccourcis";
            case "Show every service": return "Afficher tous les services";
            case "Show in the menu": return "Afficher dans le menu";
            case "Show": return "Série";
            case "Shows": return "Séries";
            case "Side menu": return "Menu latéral";
            case "Snow": return "Neige";
            case "Soap": return "Feuilleton";
            case "Specials": return "Épisodes spéciaux";
            case "Starting…": return "Démarrage…";
            case "Subscription": return "Abonnement";
            case "Sunrise ": return "Lever du soleil ";
            case "Sunset ": return "Coucher du soleil ";
            case "Switching takes a few seconds; the screen blinks while it measures.": return "Le changement prend quelques secondes ; l’écran clignote pendant la mesure.";
            case "Switching the home screen and screensaver…": return "Changement de l’écran d’accueil et de l’écran de veille…";
            case "TCL Channel button": return "Bouton TCL Channel";
            case "THIS AFTERNOON": return "CET APRÈS-MIDI";
            case "THIS MORNING": return "CE MATIN";
            case "TMDB account connected": return "Compte TMDB connecté";
            case "TMDB account": return "Compte TMDB";
            case "TMDB account, ": return "Compte TMDB, ";
            case "TMDB account: ": return "Compte TMDB : ";
            case "TONIGHT": return "CE SOIR";
            case "TV Settings": return "Paramètres de la TV";
            case "TV settings": return "Paramètres de la TV";
            case "Tagged “": return "Mot-clé « ";
            case "Talk": return "Talk-show";
            case "Temperature": return "Température";
            case "The ": return "Années ";
            case "The TV update turned back on ": return "La mise à jour de la TV a réactivé ";
            case "The TV was updated; Free My TV is still on.": return "La TV a été mise à jour ; Free My TV est toujours actif.";
            case "The TV's factory home screen": return "L’écran d’accueil d’usine de la TV";
            case "The TV's own": return "Celui de la TV";
            case "The link expired. Go back and open it again.": return "Le lien a expiré. Revenez en arrière et rouvrez-le.";
            case "Theme": return "Thème";
            case "These are the TCL remote's buttons: on this TV, the buttons work as they always did.": return "Ce sont les boutons de la télécommande TCL : sur cette TV, ils fonctionnent comme d’habitude.";
            case "This TV can't open the app's settings.": return "Cette TV ne peut pas ouvrir les paramètres de l’app.";
            case "This product uses the TMDB API but is not endorsed or certified by TMDB.": return "Ce produit utilise l’API TMDB mais n’est ni approuvé ni certifié par TMDB.";
            case "Thunderstorms": return "Orages";
            case "Today's top 10": return "Top 10 du jour";
            case "Top 10 movies today": return "Top 10 des films du jour";
            case "Top 10 shows today": return "Top 10 des séries du jour";
            case "Top rated movies": return "Films les mieux notés";
            case "Top rated shows": return "Séries les mieux notées";
            case "Top rated": return "Les mieux notés";
            case "Trailer": return "Bande-annonce";
            case "Trending anime": return "Anime tendance";
            case "Trending movies this week": return "Films tendance de la semaine";
            case "Trending movies": return "Films tendance";
            case "Trending on ": return "Tendance sur ";
            case "Trending people": return "Personnalités tendance";
            case "Trending shows this week": return "Séries tendance de la semaine";
            case "Trending shows": return "Séries tendance";
            case "Trending this week": return "Tendance de la semaine";
            case "Trending today": return "Tendance du jour";
            case "Trending": return "Tendance";
            case "Turned off what was missing: ": return "Désactivé ce qui manquait : ";
            case "Turning apps back on (": return "Réactivation des apps (";
            case "Turning off apps (": return "Désactivation des apps (";
            case "Turning the apps back on…": return "Réactivation des apps…";
            case "Type the city's name": return "Saisissez le nom de la ville";
            case "Type": return "Type";
            case "Waiting for approval…": return "En attente d’approbation…";
            case "War & Politics": return "Guerre & Politique";
            case "Watch on ": return "Regarder sur ";
            case "Weather widget": return "Widget météo";
            case "Weather": return "Météo";
            case "Where to watch": return "Où regarder";
            case "Writing": return "Scénario";
            case "You may also like": return "Vous aimerez aussi";
            case "YouTube button": return "Bouton YouTube";
            case "YouTube isn't installed.": return "YouTube n’est pas installée.";
            case "YouTube, Prime Video and Google Play: the TV itself controls those buttons.": return "YouTube, Prime Video et Google Play : la TV gère elle-même ces boutons.";
            case "Your TV's home screen": return "L’écran d’accueil de votre TV";
            case "Your rating for ": return "Votre note pour ";
            case "Your rating: ": return "Votre note : ";
            case "Your ratings": return "Vos notes";
            case "debugging is off on the TV (port 5555 closed)": return "le débogage est désactivé sur la TV (port 5555 fermé)";
            case "debugging permission wasn't accepted on the TV": return "l’autorisation de débogage n’a pas été acceptée sur la TV";
            case "invalid ADB message": return "message ADB invalide";
            case "no TMDB key": return "pas de clé TMDB";
            case "no ads, with the new screensaver": return "sans pubs, avec le nouvel écran de veille";
            case "no app on the TV": return "aucune app sur la TV";
            case "no official app": return "pas d’app officielle";
            case "not on the shelf": return "pas dans l’étagère";
            case "now": return "maintenant";
            case "on the shelf, place ": return "dans l’étagère, position ";
            case "opens ": return "ouvre ";
            case "opens the title in ": return "ouvre le titre dans ";
            case "prices from ": return "cours de ";
            case "s": return "";
            case "the TV asked for TLS (wireless debugging); use USB debugging": return "la TV demande TLS (débogage sans fil) ; utilisez le débogage USB";
            case "the TV rejected the app's key": return "la TV a refusé la clé de l’app";
            case "the TV's ADB didn't respond": return "l’ADB de la TV n’a pas répondu";
            case "the TV's factory home screen": return "l’écran d’accueil d’usine de la TV";
            case "the home screen": return "l’écran d’accueil";
            case "the original home screen": return "l’écran d’accueil d’origine";
            case "the screensaver": return "l’écran de veille";
            case "unexpected reply from ADB": return "réponse inattendue d’ADB";
            case "” from the menu?": return " » du menu ?";
            case "”? It comes back in Settings.": return " » ? Il reviendra dans les paramètres.";
            case "← → to move · OK to drop · Back to cancel": return "← → pour déplacer · OK pour poser · Retour pour annuler";
            case "Arrows to move · OK to drop · Back to cancel": return "Flèches pour déplacer · OK pour poser · Retour pour annuler";
            case "Plex": return "Plex";
            case "Continue on Plex": return "Reprendre sur Plex";
            case "Continue watching": return "Reprendre";
            case "Recently added on Plex": return "Ajouté récemment sur Plex";
            case "Resume": return "Reprendre";
            case "Start over": return "Depuis le début";
            case "Episodes": return "Épisodes";
            case "Episode ": return "Épisode ";
            case "Left: ": return "Reste ";
            case "Plex server unreachable": return "Serveur Plex injoignable";
            case "Nothing to resume on Plex": return "Rien à reprendre sur Plex";
            case "Apps": return "Apps";
            case "Add to shelf": return "Ajouter à l’étagère";
            case "Sources": return "Sources";
            case "Good morning": return "Bon matin";
            case "Markets": return "Bourse";
            case "Markets · 24 h": return "Bourse · 24 h";
            case "This TV has no Ambilight + Hue.": return "Cette TV n’a pas Ambilight + Hue.";
            case "Turn on Ambilight+hue in the TV's Ambilight settings.": return "Activez Ambilight+hue dans les réglages Ambilight de la TV.";
            case "This TV can't open that screen.": return "Cette TV ne peut pas ouvrir cet écran.";
            case "Turn off": return "Éteindre";
            case "Restart": return "Redémarrer";
            case "Power": return "Alimentation";
            case "Turn on the remote buttons service to use this.": return "Activez le service des touches de la télécommande pour cela.";
            case "Good afternoon": return "Bon après-midi";
            case "Good evening": return "Bonne soirée";
            case "Options": return "Options";
            case "Connected": return "Connecté";
            case "Standby": return "Veille";
            case "available": return "disponibles";
            case "available, one": return "disponible";
            case "Switch": return "Basculer";
            case "Choose": return "Choisir";
            case "Confirm": return "Valider";
            case "Back to close": return "Retour pour fermer";
            case "TV channels": return "Chaînes TV";
            case "Not in use": return "Libre";
            case "Glass": return "Verre";
            case "Editorial": return "Éditorial";
            case "Colors": return "Couleurs";
            case "Dark (HUD)": return "Sombre (HUD)";
            case "Queue": return "File d’attente";
            case "Browse": return "Parcourir";
            case "Minimal": return "Minimal";
            case "Dark (Minimal)": return "Sombre (Minimal)";
            case "Change input": return "Changer d’entrée";
            case "Info": return "Infos";
            case "Disconnected": return "Déconnecté";
            case "Dark (Colors)": return "Sombre (Couleurs)";
            case "Your apps": return "Vos applications";
            case "Keep watching": return "Continuer à regarder";
            case "Quick settings": return "Réglages rapides";
            case "All settings": return "Tous les paramètres";
            case "Turn off the TV": return "Éteindre la TV";
            case "Classic": return "Classique";
            case "Appearance": return "Apparence";
            case "Dark (Glass)": return "Sombre (Verre)";
            case "OK to ": return "OK pour ";
            case "Recently added": return "Ajouté récemment";
            case "Resume playback": return "Reprendre la lecture";
            case "Watch": return "Regarder";
            case "What to watch?": return "Que regarder ?";
            case "See you soon?": return "Éteindre ou redémarrer ?";
            case "entries": return "entrées";
            case "entry": return "entrée";
            case "To discover": return "À découvrir";
            case "Applications": return "Applications";
            case "Application": return "Application";
            case "App icons": return "Icônes des applis";
            case "House style": return "Maison";
            case "The apps’ own": return "D’origine";
            case "No signal": return "Pas de signal";
            case "In standby": return "En veille";
            case "Wi-Fi and VPN": return "Wi-Fi et VPN";
            case "Frequent settings": return "Réglages fréquents";
            case "Picture format": return "Format d’image";
            case "Ambilight": return "Ambilight";
            case "Speakers": return "Haut-parleurs";
            case "Software update": return "Mise à jour du logiciel";
            case "Remove from shelf": return "Retirer de l’étagère";
            case "The shelf is full.": return "L’étagère est pleine.";
            case "Update": return "Mise à jour";
            case "Update available": return "Mise à jour disponible";
            case "change on GitHub:": return "changement sur GitHub :";
            case "changes on GitHub:": return "changements sur GitHub :";
            case "The TV can't install it by itself: the update is made on the PC, which rebuilds the home screen with your settings (config.properties) and installs it again. On the PC, in the project folder, run:":
                return "La TV ne peut pas l’installer seule : la mise à jour se fait sur le PC, qui recompile l’écran d’accueil avec vos réglages (config.properties) et le réinstalle. Sur le PC, dans le dossier du projet, lancez :";
            case "or ask Claude: “do the update”.": return "ou demandez à Claude : « fais la mise à jour ».";
            case "Up to date. Checked at ": return "À jour. Vérifié à ";
            case "GitHub hasn't answered yet. Try again in a moment.": return "GitHub n’a pas encore répondu. Réessayez dans un instant.";
            case "No sources found.": return "Aucune source trouvée.";
            case "TV setup": return "Réglages TV";
            case "Update now": return "Mettre à jour";
            case "Later": return "Plus tard";
            case "Try again": return "Réessayer";
            case "Update in progress: ": return "Mise à jour en cours : ";
            case "Update blocked": return "Mise à jour bloquée";
            case "Update failed": return "Mise à jour impossible";
            case "The original repository changed the same lines as your customizations:":
                return "Le dépôt d’origine a modifié les mêmes passages que vos personnalisations :";
            case "Nothing changed. Ask Claude: “do the update”.": return "Rien n’a changé. Demandez à Claude : « fais la mise à jour ».";
            case "change:": return "nouveauté :";
            case "changes:": return "nouveautés :";
            case "The NAS builds the new version, then the TV asks you to confirm the install. The home screen then starts again.":
                return "Le NAS prépare la nouvelle version, puis la TV vous demande de confirmer l’installation. L’écran d’accueil redémarre ensuite.";
            case "The NAS doesn't answer (": return "Le NAS ne répond pas (";
            case "). Is it on, with the cytv project running in Docker?": return "). Est-il allumé, avec le projet cytv lancé dans Docker ?";
            case "The NAS hasn't answered yet. Try again in a moment.": return "Le NAS n’a pas encore répondu. Réessayez dans un instant.";
            case "The NAS is preparing the update (1 to 3 min).": return "Le NAS prépare la mise à jour (1 à 3 min).";
            case "The NAS took too long. Try again.": return "Le NAS a mis trop de temps. Réessayez.";
            case "The NAS didn't answer.": return "Le NAS n’a pas répondu.";
            case "Allow CleanYourTV UI to install apps, then try again.": return "Autorisez CleanYourTV UI à installer des applis, puis réessayez.";
            case "Downloading the update…": return "Téléchargement de la mise à jour…";
            case "Couldn't download the update from the NAS.": return "Impossible de télécharger la mise à jour depuis le NAS.";
            case "The update wasn't installed.": return "La mise à jour n’a pas été installée.";
            default:
                if (english.startsWith("Free My TV is on: new home screen and screensaver, ")) {
                    return "Free My TV est activé : nouvel écran d’accueil et écran de veille, "
                            + english.substring("Free My TV is on: new home screen and screensaver, ".length()).replace(" apps turned off.", " apps désactivées.");
                }
                if (english.startsWith("On · ")) {
                    return "Activé · " + english.substring(5).replace(" titles from the TV's apps, 3 new ones every hour.",
                            " titres des apps de la TV, 3 nouveaux chaque heure.");
                }
                if (english.startsWith("OK adds or removes (up to ")) {
                    return "OK ajoute ou retire (jusqu’à " + english.substring("OK adds or removes (up to ".length())
                            .replace("). ← → change the order.", "). ← → changent l’ordre.");
                }
                if (english.endsWith(" min ago")) return "il y a " + english.substring(0, english.length() - 8) + " min";
                if (english.endsWith(" h ago")) return "il y a " + english.substring(0, english.length() - 6) + " h";
                if (english.endsWith(" days ago")) return "il y a " + english.substring(0, english.length() - 9) + " jours";
                return english;
        }
    }
}
