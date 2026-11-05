# Binaire IPTV

**Simple. Numérique. Intuitif.** Lecteur IPTV pour Android TV, box Android et téléphones, compatible avec les abonnements **Xtream Codes**. L'appli ne fournit aucune chaîne : elle lit ton abonnement.

## Fonctionnalités (v1)

- Écran de démarrage et accueil en grandes tuiles : TV · FILMS · SÉRIES · RÉGLAGES
- **TV** : catégories, chaînes avec logo et programme en cours, aperçu en direct, guide « en ce moment / ensuite », favoris (appui long), zapping CH+/CH−
- **Films / Séries** : titre à la une, rangées Reprendre / Épisodes à suivre, Ma liste, Ajoutés récemment, Les mieux notés, catégories, recherche
- Fiches film et série : Reprendre, Depuis le début, saisons, épisodes vus, épisode suivant automatique
- **Réglages** : 8 couleurs, 4 fonds (Binaire, Aurore, Noir, image floutée), heure, aperçu, bascule de format si ça coupe, mémoire tampon, abonnement
- **Mises à jour intégrées** : l'appli propose elle-même les nouvelles versions publiées ici

## Installer

Télécharge `Binaire-IPTV.apk` dans la dernière [Release](../../releases/latest), puis installe-le sur la TV (par exemple avec l'appli *Downloader*, ou une clé USB et un gestionnaire de fichiers). Ensuite, les mises à jour se font depuis l'appli.

## Publier une nouvelle version

1. Pousse le code sur `main` : GitHub compile et vérifie (onglet **Actions**).
2. Crée un tag `vX.Y.Z` (ex. `v1.0.1`) : GitHub compile l'APK signé et le publie dans **Releases**. Les appareils installés le proposent automatiquement.

La signature utilise les secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` et `KEY_PASSWORD` (Settings → Secrets and variables → Actions). Garde la clé en lieu sûr : sans elle, impossible de mettre à jour une appli déjà installée.

## Licences

Police [Outfit](https://github.com/Outfitio/Outfit-Fonts) sous licence SIL Open Font License 1.1.
