# Tests du module Portail Stalker/MAG

## 1. Tests automatisés (logique pure)
Lancés à chaque build CI via `./gradlew testReleaseUnitTest`.
Fichier : `app/src/test/java/com/binaire/iptv/StalkerProtocolTest.kt`.
Couvre : nettoyage/validation MAC, normalisation portail, origine, points d'entrée,
enveloppes de réponse (OK / vide / bloqué / token expiré / erreur), pagination
(page vide, dernière page, total atteint, plafond), et surtout la **sécurité des
liens de lecture** (`normalizePlayUrl` accepte uniquement http(s), refuse rtp/file/commandes).

## 2. Portail de démonstration local
`tools/mock_stalker_portal.py` imite un portail Stalker avec des contenus de démo
libres de droits (échantillon Big Buck Bunny, CC-BY). Il permet de tester le client
de bout en bout sans aucun vrai fournisseur.

```
python3 tools/mock_stalker_portal.py 8088
```

Dans Binaire → Ajouter un abonnement → **Portail MAC** :
- Portail : `http://<ip-de-ta-machine>:8088/c`
- MAC : `00:1A:79:AA:BB:CC`

Cas d'erreur à vérifier :
- MAC `00:00:00:00:00:00` → l'appli doit afficher « appareil bloqué / abonnement expiré ».
- Couper le serveur en cours → message « Portail injoignable ».

## 3. Ce que ces tests ne couvrent pas
Les variantes propres à chaque vrai portail (paramètres get_profile durcis,
structures de séries exotiques) ne peuvent être validées qu'avec un portail réel
autorisant les lecteurs tiers. Le code gère les cas connus ; le reste est signalé
comme « à confirmer sur un vrai portail » dans le récap.
