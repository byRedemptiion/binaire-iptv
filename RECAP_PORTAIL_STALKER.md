# Binaire IPTV — Module « Portail Stalker/MAG » : récap des modifications (v1.7.1)

Suite à la relecture (GPT) du module v1.7.0, voici l'audit, les corrections réellement
apportées, ce qui a été volontairement laissé tel quel, et les tests réalisés.

---

## 1. Principe (rappel)
Abonnement « portail Stalker / MAG » : l'appareil s'identifie par une **adresse MAC**
auprès d'un portail, récupère un **jeton**, puis les listes (chaînes/films/séries).
Chaque lecture demande un **lien temporaire** (`create_link`).
Stockage inchangé : `server = "stalker:<portail>"`, `user = MAC`, `pass = ""`.
→ **Aucun contournement** d'authentification d'un fournisseur : on parle le protocole
Stalker standard avec les infos que l'utilisateur fournit (portail + sa MAC).

---

## 2. Corrections apportées (par point de la relecture)

### 1) Sessions / jetons
- Nouvelle couche `StalkerProtocol.parse()` qui **classe** chaque réponse :
  `OK / EMPTY / BLOCKED / TOKEN_EXPIRED / ERROR` (gère `{"js":"error"}`, `{"js":{"error":…}}`, `block=1`, HTTP 401/403).
- **Jeton expiré en cours de session** → une **ré-authentification unique** (nouveau handshake) puis **une** nouvelle tentative. Pas de boucle infinie (un seul retry, `allowReauth=false` ensuite).
- Jeton centralisé dans le client ; plus de requêtes « aveugles ».

### 2) Profils
- `get_profile` est interprété : un **profil refusé** (bloqué/expiré) remonte un message clair au lieu d'être ignoré.
- **Aucune signature ni ID matériel inventés** (on n'envoie pas de `sn`/`device_id`/`signature` fabriqués). Conforme à la demande.

### 3) Pagination
- **Dédoublonnage** par id (corrige les doublons quand `total_items` est faux).
- Arrêts robustes via `StalkerProtocol.paginationDone()` : page vide, dernière page (moins pleine que `max_page_items`), total atteint, **plafond de sécurité** (60 pages/catégorie).

### 4) Séries
- Deux structures de catalogue gérées :
  - **plate** : l'entrée série contient un tableau `series` (numéros d'épisodes) + un `cmd` ; `create_link` reçoit alors `series=<n>`.
  - **hiérarchique** : épisodes listés et paginés.
- Hiérarchie Série → Saison → Épisode reconstruite et triée.
- ⚠️ **Honnêteté** : variété réelle des portails non vérifiable sans accès ; cas connus gérés, le reste reste « à confirmer sur un vrai portail ».

### 5) Lecture (liens temporaires)
- `create_link` **validé** : si le portail ne renvoie rien d'exploitable, on lève une erreur claire au lieu de passer une commande interne injouable au lecteur.
- Repli raisonnable : si le `cmd` d'origine est déjà une URL jouable, on l'utilise.

### 6) URL (sécurité)
- `StalkerProtocol.normalizePlayUrl()` : retire uniquement les **préfixes connus** (`ffmpeg`, `auto`, `mpegts`, …), **n'accepte que http(s)://**, **refuse** tout le reste (`rtp://`, `file://`, commandes locales). Conserve les paramètres de requête nécessaires.

### 7) Compatibilité
- Variantes documentées dans l'en-tête de `StalkerClient.kt` et ici. Aucune prétention de contourner l'auth d'un fournisseur.

---

## 3. Laissé tel quel (ça marche — ne pas refaire pour rien)
- **Modèle de données commun M3U/Xtream/Stalker** : il **existe déjà** par héritage —
  `M3uClient` et `StalkerClient` étendent `XtreamClient` et partagent `Item`/`Category`/
  `VodInfo`/`SeriesInfo`/`Episode`. Un refactor en type scellé toucherait tout l'écosystème
  (adaptateurs, lecteur, recherche, Multi-TV) pour **zéro gain fonctionnel** → écarté.
- **Format de stockage** (`stalker:` / MAC) inchangé → **comptes existants compatibles**.
- **Séparation totale résolveur/lecteur** (point 5) : non faite volontairement — ripplerait
  dans `PlayerActivity`. Les erreurs sont gérées à la source, ce qui couvre le vrai besoin.

## 4. Sécurité / confidentialité
- **Aucune journalisation** de token, MAC ou identifiants (le code n'écrit aucun log, et les messages d'erreur n'exposent ni MAC ni jeton).
- MAC stockée dans les données privées du compte (règles Firestore déjà en place).

---

## 5. Tests réalisés
- **Logique pure extraite dans `StalkerProtocol`** (sans Android ni réseau), donc testable.
- **Tests JUnit** `app/src/test/java/com/binaire/iptv/StalkerProtocolTest.kt` (MAC, portail,
  points d'entrée, enveloppes OK/vide/bloqué/token-expiré/erreur, pagination, et surtout
  **sécurité des liens** http-only). **Lancés à chaque build CI** (`./gradlew testReleaseUnitTest`),
  build v1.7.1 vert avec `Task :app:testReleaseUnitTest` exécuté.
- **Portail de démo local** `tools/mock_stalker_portal.py` (contenus libres de droits) +
  procédure dans `tools/README_tests.md`, pour tester le client de bout en bout sans vrai fournisseur
  (y compris le cas « appareil bloqué »).

## 6. Reste à valider sur un vrai portail (autorisant les lecteurs tiers)
- Paramètres `get_profile` exigés par certains portails « durcis ».
- Structures de séries exotiques.
- Comportement exact de `create_link` (lien à usage unique vs réutilisable) selon l'implémentation du portail.
