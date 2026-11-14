# Binaire IPTV — Récap mode « Portail Stalker/MAG » + analyse de l'appli du fournisseur

Document de travail pour relecture par un autre assistant (GPT). Contexte : appli Android TV maison « Binaire IPTV » (lecteur pour abonnement perso), on vient d'ajouter la prise en charge des abonnements **portail Stalker / MAG** (identification par adresse MAC). But : avoir un avis technique sur l'implémentation et sur les points incertains.

---

## 1. Analyse de l'appli du fournisseur (contexte)

- APK : `1Dream24K_Stalker_Mag_V30.apk`, package `com.dream4ktvnew.app.pro`, classes internes `app.dream.com.*`.
- Lecteur vidéo : ExoPlayer + libVLC (assets lua = parsers VLC standard, non pertinents).
- Backend trouvé en clair dans le dex :
  - **`https://newvodpro.hmaserv.online/dream/process.php`** (avec `&action=1`) — endpoint « loader » maison.
  - Chemins **Xtream Codes** classiques, ex. `/streaming/timeshift.php?username=…`.
- Conclusion sur le fonctionnement : l'appli envoie sa **MAC** au loader → le serveur renvoie des **identifiants Xtream** (serveur/user/pass) rattachés à cette MAC → lecture via URLs Xtream standard.
- Le fournisseur **refuse de communiquer les identifiants Xtream** et dit que « ça ne communiquera pas vers le panel ». On **ne cherche pas** à extraire ces identifiants cachés (contournement non souhaité). On a donc implémenté un mode portail **générique**, utilisable avec un vrai portail Stalker qui, lui, donne une adresse de portail + accepte une MAC.

---

## 2. Ce qui a été implémenté dans Binaire (v1.7.0)

Nouveau fichier `StalkerClient.kt`, qui **hérite** de `XtreamClient` et remplace ses méthodes. Le reste de l'appli (nettoyage des listes, profils, Multi-TV, mode 1 écran) fonctionne sans changement car tout passe par la même interface.

### Stockage de l'abonnement
Modèle existant à 3 champs `(server, user, pass)`, réutilisé ainsi :
- `server = "stalker:<adresse du portail>"` (ex. `stalker:http://host:port/c`)
- `user  = "<MAC>"` (format `00:1A:79:AA:BB:CC`)
- `pass  = ""` (inutilisé)

Dispatch dans `XtreamClient.create()` : préfixe `m3u:` → M3uClient, `stalker:` → StalkerClient, sinon Xtream.

### Protocole Stalker implémenté
En-têtes envoyés à chaque requête :
- `User-Agent: Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3`
- `Cookie: mac=<MAC>; stb_lang=fr; timezone=Europe/Paris`
- `Referer: <origine>/c/`
- `Authorization: Bearer <token>` (après handshake)
- Suffixe `&JsHttpRequest=1-xml` sur chaque URL.

Détection du point d'entrée (essais successifs jusqu'à obtenir un token) :
1. `<origine>/portal.php`
2. `<origine>/server/load.php`
3. `<origine>/stalker_portal/server/load.php`
4. `<portail>/server/load.php`

Séquence :
- **handshake** : `type=stb&action=handshake&token=` → `js.token`
- **get_profile** : `type=stb&action=get_profile&hd=1&stb_type=MAG250&client_type=STB&...` (best-effort)
- **Live** : catégories `type=itv&action=get_genres` ; chaînes `type=itv&action=get_ordered_list&genre=<id>&p=<n>` (pagination via `total_items` / `max_page_items`, plafond 60 pages/catégorie).
- **VOD** : `type=vod&action=get_categories` puis `get_ordered_list&category=<id>&p=<n>`.
- **Séries** : `type=series&action=get_categories`, `get_ordered_list&category=<id>`, puis épisodes `get_ordered_list&movie_id=<id>&season_id=0&episode_id=0`.
- **Lecture** : chaque élément conserve son `cmd`. Au moment de lire : `type=<itv|vod>&action=create_link&cmd=<urlencodé>` → on récupère `js.cmd`, on retire un éventuel préfixe `ffmpeg ` et on joue l'URL. Les `cmd` sont gardés en mémoire (map id→cmd) pour la durée de la session, comme le M3uClient garde ses URLs.

### Interface
- **Page téléphone (Mon compte → Ajouter)** : 3e onglet « Portail MAC » avec adresse du portail + MAC + bouton « Générer une MAC » (préfixe 00:1A:79). Stockage `stalker:<portail>` / MAC.
- **TV (écran de connexion)** : si « Utilisateur » ressemble à une MAC (12 hex) et mot de passe vide → traité comme portail.
- **Réglages** : ligne « Type : Portail MAG/Stalker · <host> ».
- `maxConnections` forcé à 1 → le mode « 1 écran à la fois » (passage de relais entre appareils) s'applique.

---

## 3. Points incertains / à challenger (questions pour GPT)

Je n'ai **pas pu tester avec un vrai portail** (pas d'accès). Les points sur lesquels j'aimerais un avis :

1. **Chemin du token** : certains portails attendent le token sur `Cookie` (`token=…`) plutôt que sur `Authorization: Bearer`. Faut-il envoyer les deux ? Gérer le rafraîchissement du token quand il expire en cours de session (réponse vide / `block`/`error` dans `js`) ?
2. **get_profile** : paramètres souvent attendus (`sn`, `device_id`, `device_id2`, `signature`, `hw_version`). Certains portails bloquent sans un `device_id` cohérent avec la MAC. Est-ce nécessaire pour un portail standard, ou seulement sur les portails « durcis » ?
3. **Pagination** : je me base sur `total_items` / `max_page_items`. Fiable ? Risque de boucle si le portail renvoie toujours des données — d'où le plafond 60 pages. Mieux : s'arrêter dès qu'une page renvoie moins d'éléments que `max_page_items` ?
4. **Séries** : la structure Stalker des séries (saisons/épisodes via `cmd` imbriqués) varie beaucoup. Mon approche (un seul `get_ordered_list` avec `movie_id`) suffit-elle, ou faut-il descendre saison par saison ?
5. **create_link** : parfois le `cmd` contient déjà une URL jouable (pas besoin de create_link) ; parfois create_link est obligatoire et renvoie une URL à usage unique. Je fais toujours create_link. Risque de requêtes inutiles ? Faut-il détecter `use_http_tmp_link` / `enable_balancer` ?
6. **Format d'URL finale** : je retire le préfixe `ffmpeg `. D'autres préfixes possibles (`auto `, `mpegts `, `varied `) à gérer ?
7. **User-Agent / portail durci** : UA MAG250 codé en dur. Certains portails checkent un couple (UA + MAC + signature). Acceptable pour un portail standard ?

---

## 4. Ce qu'on NE fait PAS (volontairement)

- On ne réplique pas le loader maison `process.php` du fournisseur pour extraire des identifiants Xtream cachés derrière une MAC. C'est un contournement de la protection que le fournisseur a choisie, et il a refusé de donner les identifiants. Le mode portail est **générique**, pour des portails Stalker qui fournissent normalement une adresse de portail à l'utilisateur.

---

## 5. Résumé en une ligne
Binaire sait maintenant lire un abonnement **Xtream**, un **lien M3U**, et un **portail Stalker/MAG** (connexion par MAC, chaînes/films/séries, lien de lecture via `create_link`). Le mode portail est codé mais **non testé sur un vrai portail** : relecture bienvenue sur les 7 points ci-dessus.
