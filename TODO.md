# TODO — CloudGallery

Ce qui reste à faire après les phases 1 à 3 (galerie locale MediaStore, OneDrive en lecture seule).
Les cases cochées sont déjà faites. Règles qui s'appliquent à toutes les phases :

- aucun fichier de l'utilisateur n'est supprimé sans confirmation explicite ;
- aucune fonction factice (un bouton absent vaut mieux qu'un bouton qui ne fait rien) ;
- aucune entrée/sortie sur le thread principal ;
- uniquement des API documentées et vérifiées.

---

## Prochaines actions immédiates (avant la phase 3)

- [ ] Installer sur le Galaxy S22 et tester sur la vraie bibliothèque :
      `D:\AndroidSDK\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk`.
      Vérifier les permissions (complet, partiel, refus), la première indexation, le défilement,
      le zoom de la grille, la sélection, la suppression vers la corbeille, la rotation, le
      recadrage, le lecteur vidéo et l'ouverture depuis une autre application.
- [ ] Lancer les tests sur appareil : `gradlew connectedDebugAndroidTest` (`MediaDaoTest`).
- [ ] Fournir les captures Samsung Galerie listées en phase 7, puis corriger les écarts visuels.
- [x] Fournir le **Client ID** Microsoft Entra (comptes personnels) et le hachage de signature
      pour l'URI de redirection MSAL : c'est le prérequis de la phase 3 (dans `local.properties`).

## Phase 3 — OneDrive : authentification, Graph, delta, miniatures

### Authentification (MSAL)
- [x] Inscrire l'application dans Microsoft Entra : comptes personnels, plateforme Android,
      `msauth://fr.eva.cloudgallery/<hash>`, scopes `Files.Read`, `User.Read`, `offline_access`
      (procédure dans le README).
- [x] Ajouter la dépendance MSAL (8.5.0, dépôt Duo SDK en `exclusiveContent` pour `display-mask`)
      et la déclarer dans `libs.versions.toml`.
- [x] Lire le client ID et le hachage de signature depuis `local.properties` (ou une propriété
      Gradle), puis les exposer via `BuildConfig` et le placeholder du manifeste. Sans valeurs
      valides, le build réussit et OneDrive est masqué.
- [x] Générer la configuration MSAL (`account_mode SINGLE`, audience `PersonalMicrosoftAccount`,
      fichier écrit à l'exécution dans `noBackupFilesDir`) et déclarer `BrowserTabActivity` dans le
      manifeste.
- [x] Gérer la connexion, la déconnexion et l'expiration (`acquireTokenSilent`, puis
      « Reconnexion requise » si `MsalUiRequiredException`). Écran « Compte OneDrive » dans Menu et
      Paramètres, à la place de « Disponible dans une prochaine version ».
- [x] Décider du sort des données OneDrive à la déconnexion, avec confirmation : purger les sources
      `ONEDRIVE`, les albums OneDrive et les miniatures en cache, sans toucher aux fichiers locaux.
- [x] Exclure les fichiers de jetons MSAL des sauvegardes Android (`backup_rules.xml`,
      `data_extraction_rules.xml`).

### Client Microsoft Graph
- [x] Client HTTP (OkHttp 5.5.0, créé au premier appel sur le thread d'E/S), sérialisation avec
      kotlinx-serialization, jeton `Bearer` envoyé uniquement à `graph.microsoft.com`.
- [x] `GET /me/drive/root/delta` (tout le OneDrive) avec un `$select` explicite. Suivre
      `@odata.nextLink` et enregistrer `@odata.deltaLink` dans `sync_cursor`
      (`provider = "onedrive"`), lié au `drive.id` et à la version du `$select`.
- [x] Gérer `410 Gone` (deltaLink expiré) par une resynchronisation complète, avec les règles de
      masquage plutôt que de suppression (`planCloudRemovals`).
- [x] Respecter `429` / `503` + `Retry-After`, avec backoff exponentiel plafonné, pause globale de
      toutes les requêtes et sans parallélisme excessif.
- [x] Traiter les éléments `deleted` : masquer la source puis la purger après 30 jours. Le fichier
      local correspondant n'est jamais supprimé. Dossiers supprimés traités en fin de passage.
- [x] Filtrer les éléments sans facette `file`, et ne garder que les MIME image/vidéo.
- [x] Écrire des sources `ONEDRIVE` (`externalId`, `bucketKey = onedrive:<parentId>`, `eTag`,
      `cTag`, `quickXorHash`, `sha1Hash` si disponible), mettre à jour `hasCloud` /
      `refreshSourceFlags` et créer les albums-dossiers OneDrive (badge « Album OneDrive »).
- [x] Choisir la date à afficher : `photo.takenDateTime`, puis `fileSystemInfo.createdDateTime`,
      puis `createdDateTime`.
- [x] Changement de compte Microsoft : synchro suspendue et remplacement des données seulement
      après confirmation.
- [x] Réglage « Afficher les éléments OneDrive dans Photos » (désactivé par défaut) pour éviter les
      doublons dans Photos et Vidéos avant le dédoublonnage.

### Miniatures
- [x] Ajouter un `Fetcher`/`Keyer` Coil pour les miniatures Graph
      (`/items/{id}/thumbnails/0/{taille}/content`), avec la clé de cache fondée sur `cTag`, des
      tailles personnalisées `c{n}x{n}_crop` (repli sur les tailles standard) et le cache disque
      partagé de l'ImageLoader.
- [x] Afficher les miniatures dans la grille, les couvertures d'album, la bande de miniatures et le
      sélecteur d'album, avec un indicateur de source cloud seulement sur la cellule.
- [x] Aperçu OneDrive dans la visionneuse (sans zoom, badge vidéo), actions sur fichier local
      masquées ou désactivées pour les éléments présents uniquement sur OneDrive.
- [x] État de la synchro OneDrive dans le Menu et les Paramètres.
- [x] Migration Room 2→3 (`media.cloudItemId`, `media.cloudCTag`, table `cloud_folder`), schéma
      exporté, `MigrationTest` (2→3 et 1→3).

### Vérifications sur l'appareil (phase 3)
Détail dans le README (« Vérifications sur l'appareil : OneDrive ») :
- [ ] Lancer `gradlew connectedDebugAndroidTest` (`MigrationTest`, `CloudSyncWriterTest`,
      `CloudDataPurgerTest`, `MediaDaoTest`) : compilés, pas encore exécutés.
- [ ] Connexion par l'onglet de navigateur, compte conservé après un arrêt forcé, reconnexion,
      déconnexion. Vérifier que `broker_redirect_uri_registered=false` garde l'onglet de
      navigateur même si Microsoft Authenticator est installé.
- [ ] Supprimer une photo et un dossier de test sur OneDrive (web) : vérifier que `deleted`,
      `root`, `parentReference.id`, `file.hashes.quickXorHash` et `photo` reviennent bien avec le
      `$select` (sinon modifier `DELTA_SELECT` et incrémenter `DELTA_SELECT_VERSION`), et que Graph
      accepte le `$select` littéral avec virgules.
- [ ] Tailles de miniatures personnalisées `c{n}x{n}_crop` et `c1600x1600` acceptées en segment de
      chemin, et redirection des miniatures sans authentification.
- [ ] Renommage, déplacement et restauration de dossier depuis la corbeille OneDrive, y compris
      un dossier dont les photos sont dans un sous-dossier : vérifier si la requête delta renvoie
      chaque descendant restauré (si oui, la détection large « dossier retiré de l'index » pourrait
      être resserrée pour éviter des énumérations complètes inutiles).
- [ ] Connexion à un autre compte Microsoft : carte « Autre compte OneDrive », rien de purgé avant
      confirmation.
- [ ] Logcat sans jeton, deltaLink ni nom de compte ; OkHttp 5.5.0 et TLS sur l'appareil.
- [ ] `adb shell run-as fr.eva.cloudgallery ls -R shared_prefs files app_fr.eva.cloudgallery` :
      noms des fichiers MSAL (dont `app_fr.eva.cloudgallery/adalks`) conformes aux exclusions de
      sauvegarde.
- [ ] Déconnexion pendant le chargement de miniatures sur réseau lent : le compte reste « Non
      connecté » et aucune synchro ne repart.

## Phase 4 — Streaming, aperçus adaptatifs, téléchargements hors ligne

- [x] Visionneuse : miniature puis aperçu OneDrive dans `CloudOnlyPage` (`ui/viewer/ViewerPages.kt`),
      fait en phase 3.
- [x] Visionneuse : original OneDrive au zoom profond (téléchargement à la demande, automatique sur
      réseau non facturé, pastille « Charger l'original (x Mo) » sinon, réglage « Charger les
      originaux sur réseau mobile »), `CloudOriginalStore` + `CdnDownloader`, aperçu zoomable.
- [x] Choisir le niveau d'aperçu selon la taille de l'écran, l'image et le réseau (paliers 1600 /
      2048, 1600 au plus sur réseau facturé, palier refusé par Graph abandonné).
- [x] Ajouter `PlaybackSource.Remote` (`ui/video/PlaybackSource.kt`) : URL temporaire
      `@microsoft.graph.downloadUrl`, `ResolvingDataSource` au-dessus de `DefaultHttpDataSource`
      (HTTP Range pour le seek) et renouvellement de l'URL à son expiration (`data/cloud/stream/`,
      `CloudDownloadUrlProvider`, garde d'accès commune `CloudAccessGuard`, journal Media3 expurgé
      des URL).
- [x] Lire en priorité une copie `OFFLINE_COPY` quand elle existe (`PlaybackSource.Offline`, copie
      vérifiée hors du thread principal ; « Ouvrir avec » par le FileProvider).
- [x] Vérifier que Media3 1.11.1 renvoie bien `ERROR_CODE_IO_*` pour les erreurs réseau (vérifié
      dans le bytecode : 2001/2002 réseau, 2004 statut HTTP, 2000 pour une erreur du résolveur), et
      ajouter les messages correspondants (réseau, lien, connexion requise, limitation, introuvable).
- [x] État du réseau par défaut (`data/net/NetworkStatus`) : mesuré ou non, économiseur de données
      (compté comme mesuré), état inconnu traité comme facturé. Utilisé par les aperçus et
      téléchargements des tranches suivantes.
- [x] Partage et « Ouvrir avec » d'un média cloud : téléchargement de l'original (progression
      annulable, 20 éléments / 500 Mo), `CloudGalleryFileProvider`, CloudGallery exclue du sélecteur.
- [x] Base v4 (`cached_file.version`, `download_task.cloudItemId` / `cTag` / `attempts`), schéma
      exporté, `MigrationTest` 3→4, 2→4, 1→4 ; cache temporaire des originaux (1 Go, 7 jours,
      nettoyage au démarrage) ; purge des originaux à la déconnexion (`CloudDataGeneration`).
- [x] Message de dépassement des plafonds de partage (`share_cloud_too_large`, `share_cloud_too_many`)
      qui propose « Conserver hors ligne » ; copies hors ligne partagées sans téléchargement.
- [ ] Lire l'EXIF d'un original téléchargé (panneau Détails) sans passer par `MediaStore.setRequireOriginal`.
- [x] Téléchargements hors ligne : file `download_task` (reprise par Range, progression, annulation,
      pause après un arrêt par l'utilisateur, erreurs codées), tâche JobScheduler lancée par
      l'utilisateur (`OfflineTransferJobService`), notification avec « Annuler ». Une copie terminée
      (`filesDir/offline/`, exclue des sauvegardes) devient une source `OFFLINE_COPY` et marque
      `hasOffline` ; enregistrement revérifié (purge, libération, version).
- [x] Action « Conserver hors ligne » / « Libérer l'espace » (`pinned`) depuis la visionneuse et en
      sélection multiple ; autorisation des notifications au premier usage ; gros lot (200 éléments /
      1 Go) confirmé ; espace insuffisant (écran système) ; réglage « Wi-Fi uniquement » et
      « Télécharger maintenant » ; section « Hors ligne » de l'écran Compte.
- [x] Nouvelle version d'un élément conservé : nouveau téléchargement, l'ancienne copie reste jusqu'au
      remplacement ; original temporaire périmé retiré.
- [x] Déconnexion, purge des données restantes, changement de compte : moteur arrêté, copies
      supprimées, confirmation avec le nombre de copies et de dernières copies (D3).
- [x] Élément supprimé de OneDrive (D1) : copie masquée avec lui, jamais purgée sans décision après
      30 jours ; carte de l'écran Compte et pastille du Menu (« Supprimer les copies » /
      « Les conserver ») ; copie conservée visible, « Libérer » avec confirmation de dernière copie ;
      élément revenu rattaché au même média.
- [x] Corrections de la revue de la phase 4 : décision D1 limitée à la liste confirmée et revérifiée
      dans la transaction ; dernières copies comptées copies masquées comprises et « Libérer »
      abandonné si le compte échoue ; confirmations de purge désactivées tant que le nombre de copies
      n'est pas lu (avertissement prudent en cas d'échec) ; « Télécharger maintenant » limité aux
      tâches en file à cet instant ; réglage « Wi-Fi uniquement » appliqué aussitôt (replanification,
      réseau vérifié avant chaque élément) ; `allocateBytes` appelé quand l'espace libre manque mais
      est obtenable (copies et originaux) ; pause au démarrage seulement après un arrêt pendant un
      téléchargement ; fin de travail revérifiée (`OfflineJobTracker`) ; « Libérer » / « Annuler »
      de l'élément en cours sans arrêter le passage ; contenu plus récent que l'index en attente de
      la synchro ; version redemandée à Graph avant une reprise Range ; mise à jour arrêtée
      signalée et reprenable ; original jamais écrasé avant l'enregistrement du suivant ; nettoyage
      des originaux conditionnel ; aperçu choisi une fois le réseau connu ; zoom haute résolution
      avec les dimensions du fichier ; aperçu en cache seulement pour une copie hors ligne ; zoom
      profond sans compte signalé ; sign-out en échec : file relancée après la fin de l'état
      « déconnexion » ; tests `OfflineRepositoryTest`, `OfflineJobTrackerTest`,
      `CloudOriginalLogicTest`.

### Vérifications sur l'appareil (phase 4)
Détail dans le README (« Vérifications sur l'appareil : streaming OneDrive ») :
- [ ] `$select=id,size,cTag,eTag,parentReference,@microsoft.graph.downloadUrl` accepté avec `$` et
      annotation renvoyée (sinon `downloadInfoUrl(selectParam = "select")`, forme de la doc).
- [ ] Réponse 206 de l'hôte de téléchargement aux requêtes Range (seek rapide).
- [ ] Statut HTTP renvoyé pour une URL expirée (401, 403, 404 ou 410 attendus) et durée de
      validité réelle (URL réutilisée deux minutes au plus).
- [ ] Logcat sans URL `files.1drv.com` ni `tempauth`, y compris après une erreur de lecture.
- [ ] Taille d'aperçu `c2048x2048` acceptée par Graph et image réellement plus grande que
      `c1600x1600` (sinon `CLOUD_PREVIEW_TIERS = [1600]`).
- [ ] Original OneDrive de 50 Mpx affiché sans plantage (décodage réduit) ; orientation EXIF correcte.
- [ ] Pastille « Charger l'original » en données mobiles et avec l'économiseur de données ;
      chargement automatique en Wi-Fi.
- [ ] Partage d'un original vers Gmail / Messages : nom OneDrive affiché ; « Ouvrir avec » sans
      CloudGallery dans le sélecteur.
- [ ] `connectedDebugAndroidTest` : `MigrationTest` (3→4, 2→4, 1→4), `CachedFileDaoTest`,
      `CloudDataPurgerTest`, `OfflineDaoTest`, `OfflineDownloadEngineTest`, `OfflineRepositoryTest`,
      `CloudSyncWriterTest`
      (la suite instrumentée de la phase 3 n'a pas encore tourné non plus).
- [ ] Copies hors ligne (détail dans le README, « Vérifications sur l'appareil : copies hors ligne ») :
  - [ ] première demande d'autorisation des notifications, téléchargement aussi en cas de refus
        (tâche visible dans le Gestionnaire de tâches) ;
  - [ ] Wi-Fi uniquement : boîte « En attente du Wi-Fi », « Télécharger maintenant » en mobile,
        économiseur de données compris ;
  - [ ] passage du Wi-Fi au mobile en cours de téléchargement : arrêt, reprise au retour du Wi-Fi
        (Range, fichier partiel gardé), jamais en mobile (`JobParameters.getNetwork`) ;
  - [ ] VPN actif : la tâche démarre ;
  - [ ] arrêt depuis le Gestionnaire de tâches : file en pause, aucune reprise automatique (vérifier
        si `onStopJob` reçoit `STOP_REASON_USER` ou si le processus est tué : motif de sortie
        `REASON_USER_REQUESTED` utilisé au redémarrage) ; appli balayée depuis les applis récentes
        avec une file en attente du Wi-Fi : file toujours en attente à la réouverture ;
  - [ ] « Télécharger maintenant » en mobile, puis nouvelle version trouvée par la synchro : la
        nouvelle tâche attend le Wi-Fi ;
  - [ ] « Wi-Fi uniquement » activé pendant un téléchargement en mobile : arrêt et attente du
        Wi-Fi ; désactivé alors que la file attend le Wi-Fi : démarrage aussitôt ;
  - [ ] « Libérer » de l'élément en cours de téléchargement : les suivants continuent ;
  - [ ] espace presque plein avec du cache d'autres applis : copie de plusieurs Go menée à bien
        (`allocateBytes`) ;
  - [ ] redémarrage du téléphone avec une file en attente : reprise à l'ouverture suivante ;
  - [ ] restrictions One UI « applis en veille » ;
  - [ ] `parentReference.driveId` renvoyé par Graph comparé au lecteur de l'index (casse ignorée) ;
  - [ ] nouvelle version sur OneDrive : retéléchargement, ancienne copie affichée jusque-là ;
  - [ ] élément conservé supprimé sur OneDrive : masqué, carte au bout de 30 jours (horloge
        avancée), « Les conserver » / « Supprimer les copies » ;
  - [ ] déconnexion : `files/offline` vide, message avec le nombre de copies ;
  - [ ] logcat sans URL ni nom de fichier ; `dumpsys jobscheduler` : tâche 4001 « user-initiated ».

## Phase 5 — Cache LRU, page Stockage, synchro périodique

- [ ] Recenser chaque fichier de cache dans `cached_file` (`lastAccessAt` mis à jour hors du thread
      principal).
- [ ] Éviction LRU sous un plafond configurable. **Les fichiers `pinned` ne sont jamais évincés.**
- [ ] Page « Stockage » : espace utilisé par niveau (miniatures, aperçus, originaux, hors ligne),
      plafond, vidage du cache non épinglé avec confirmation.
- [ ] Coil : décider si son cache disque (256 Mo, qui contient aujourd'hui les miniatures OneDrive)
      reste séparé du cache géré ou s'il y est intégré.
- [ ] WorkManager périodique (`@HiltWorker`, `hilt-work` déjà présent) pour le delta OneDrive, avec
      contrainte réseau et option Wi-Fi seulement. Prévoir aussi un rattrapage MediaStore en arrière-
      plan, éventuellement avec un `ContentUriTrigger`.
- [x] Afficher l'état de synchro OneDrive dans Paramètres (dernier delta, erreurs, nombre
      d'éléments) : fait en phase 3.
- [ ] Première énumération OneDrive reprenable après un arrêt du processus (aujourd'hui elle
      recommence depuis le début, sans perte de données).
- [ ] Usage « OneDrive seul » (reporté de la phase 4, décision D4) : aujourd'hui, sans permission
      médias, l'application n'affiche que l'écran d'autorisation. Permettre de parcourir OneDrive
      sans accès aux photos du téléphone.
- [ ] Copies hors ligne : relancer automatiquement un nouveau téléchargement détecté par une synchro
      en arrière-plan (aujourd'hui au premier plan seulement : la tâche lancée par l'utilisateur ne
      peut être planifiée qu'appli visible) ; couvertures d'album et EXIF depuis la copie hors ligne.
- [ ] Garder les lignes `cloud_folder` des dossiers supprimés (marqueur de suppression) tant que
      des sources masquées y font référence : la restauration d'un dossier pourrait alors cibler
      ses seuls descendants, au lieu de déclencher une énumération complète à chaque nouveau
      dossier pendant les 30 jours qui suivent la suppression d'un dossier (migration de schéma).
- [ ] Changement de compte détecté dès le démarrage (aujourd'hui au premier passage de synchro) :
      jusque-là, une miniature non encore en cache d'un élément de l'ancien compte peut encore être
      demandée avec le jeton du nouveau compte.

## Phase 6 — Dédoublonnage, économiseur d'espace, recherche, albums mixtes

- [ ] Calculer l'empreinte des fichiers locaux (QuickXorHash, compatible OneDrive) en arrière-plan,
      par lots, sous un budget CPU/batterie. Renseigner `media.contentHash`.
- [ ] Fusionner local et cloud **uniquement sur hash de contenu identique (+ taille)**, **jamais sur
      le nom de fichier**. Un média logique reçoit alors une source `LOCAL` et une source `ONEDRIVE`.
- [ ] Défaire la fusion si le hash diverge ensuite (fichier modifié d'un côté).
- [ ] Économiseur d'espace : proposer de supprimer les copies locales **déjà présentes sur OneDrive
      et vérifiées par hash**, avec une liste revue par l'utilisateur, un résumé de l'espace libéré et
      une confirmation explicite, puis `MediaStore.createTrashRequest` (corbeille récupérable par
      défaut). Ne jamais le déclencher automatiquement.
- [ ] Recherche plein texte : table FTS Room (`@Fts4`) sur le nom, le dossier et la date (puis le
      lieu si on ajoute le géocodage). Écran de recherche et suggestions.
- [ ] Albums virtuels mêlant sources locales et OneDrive (les références `album_media` pointent déjà
      vers le média logique). Vérifier le comportement quand une source disparaît.
- [ ] Albums-dossiers : `ALBUM_MEMBERS` / `ALBUM_ROWS` comptent encore les sources masquées comme
      membres du dossier (sans effet tant qu'un média n'a qu'une source ; à corriger avec la fusion).
- [ ] Dates OneDrive : `photo.takenDateTime` peut être l'heure locale EXIF présentée comme UTC ; à
      prendre en compte pour rapprocher un élément OneDrive de sa copie locale.
- [ ] Éventuellement, synchroniser les favoris avec `MediaStore.createFavoriteRequest` (avec la
      confirmation système), à condition que cela n'écrase pas les favoris Samsung existants.

## Phase 7 — Fidélité visuelle, images lourdes, finitions

### Visionneuse et formats
- [ ] Décodage par tuiles (`BitmapRegionDecoder`) pour les images de plus de 24 MP et les panoramas
      (TODO dans `ui/viewer/ZoomableImage.kt`).
- [ ] Animer les GIF avec `io.coil-kt.coil3:coil-gif` (même version que Coil, 3.6.3), puis WebP et
      HEIF animés si pris en charge.
- [ ] Motion Photos Samsung : détection et lecture de la partie vidéo, seulement après recherche
      d'un format documenté.
- [ ] Édition vidéo (découpe) : désactivée pour l'instant (TODO dans `ui/viewer/ViewerScreen.kt`).
      À évaluer avec Media3 Transformer, toujours en enregistrant une copie.
- [ ] Transitions à élément partagé grille → visionneuse (`SharedTransitionLayout`) et retour sur la
      bonne cellule.

### URI externes et accès partiel
- [ ] URI du sélecteur de photos (`content://media/picker/...`) et URI FileProvider tierces : elles
      ne peuvent pas être rattachées à une source MediaStore et s'affichent sans index (TODO dans
      `data/repository/MediaRepositoryImpl.kt`). Étudier `MediaStore.getMediaUri` et les colonnes
      lisibles pour en rattacher une partie, sinon conserver le mode lecture seule.
- [ ] Accès partiel : expliquer dans l'interface que les éléments non sélectionnés sont masqués,
      pas supprimés. Proposer l'accès complet sans insister. Tester l'ajout et le retrait
      d'éléments de la sélection sous One UI 8.
- [ ] Accès partiel : les albums-dossiers ne sont pas nettoyés. Vérifier que les dossiers vides
      restent bien masqués.

### Captures Samsung Galerie nécessaires (One UI 8, Galaxy S22, thèmes clair et sombre)
Elles servent à comparer pixel par pixel les marges, tailles de police, couleurs, rayons et
animations :
- [ ] Onglet Photos : grille à **chaque niveau de zoom** (du plus grand au plus petit), en-têtes
      jour et mois, barre d'onglets, barre supérieure repliée et dépliée, défilement rapide
      (poignée).
- [ ] Onglet Albums : grille d'albums, albums épinglés, tri, création d'album, vue d'un album.
- [ ] Visionneuse : interface (barres haut/bas, boutons), filmstrip, **panneau de détails**
      (EXIF, carte, emplacement), visionneuse en paysage.
- [ ] Lecteur vidéo : commandes (lecture, barre de progression, ±10 s, vitesse, son, rotation),
      état verrouillé, paysage.
- [ ] Mode sélection : en-tête avec compteur, cases à cocher, sélection d'un jour, barre d'actions
      du bas, menu « Plus ».
- [ ] Boîtes de dialogue : suppression (corbeille / définitive), renommage, création d'album,
      copie vers un dossier, tri, réindexation.
- [ ] Paramètres de Samsung Galerie (structure des sections et style des interrupteurs).
- [ ] Onglet / écran Menu (Favoris, Vidéos, Corbeille, Paramètres…).

### Divers
- [ ] Écran « Corbeille » (éléments `IS_TRASHED` de MediaStore) avec restauration et suppression
      définitive, toutes deux confirmées par le système.
- [ ] Sauvegarde et restauration manuelles des favoris et albums personnalisés (export JSON), puisque
      la base est exclue des sauvegardes Android.
- [ ] Vraie `signingConfig` release si l'application doit être distribuée (et son hachage de
      signature ajouté dans Microsoft Entra).
- [ ] Manifeste fusionné : la permission `NFC` et les activités ajoutées par les dépendances de
      MSAL (YubiKey, Credentials) ; les retirer avec `tools:node="remove"` seulement après test de
      la connexion.
- [ ] Profil de performance sur 100 000 médias : démarrage à froid, première indexation, défilement
      rapide, mémoire. Envisager un Baseline Profile.
- [ ] Tests : indexeur MediaStore sur appareil (accès partiel, volume amovible, changement de
      version), tests UI Compose des parcours principaux. (Les migrations Room sont testées par
      `MigrationTest` depuis la phase 3.)

---

## TODO présents dans le code

| Fichier | Note |
|---|---|
| `app/src/main/java/fr/eva/cloudgallery/data/repository/MediaRepositoryImpl.kt` | URI du sélecteur de photos (`content://media/picker/...`) et URI FileProvider tierces : impossible de les rattacher à une source MediaStore, la visionneuse les affiche sans index |
| `app/src/main/java/fr/eva/cloudgallery/ui/viewer/ViewerScreen.kt` | `TODO(viewer)` : édition vidéo (découpe) non prise en charge |
| `app/src/main/java/fr/eva/cloudgallery/ui/viewer/ZoomableImage.kt` | `TODO(viewer)` : décodage par tuiles (`BitmapRegionDecoder`) pour les images de plus de 24 MP |

## Limitations connues (rappel)

- [ ] OneDrive en lecture seule : un élément présent uniquement sur OneDrive peut être zoomé
      (original), lu, partagé et ouvert ailleurs, mais ni modifié, ni supprimé, ni copié vers un
      dossier du téléphone, même conservé hors ligne (copie de l'appli, lecture seule).
- [ ] Doublons : un média présent sur le téléphone et sur OneDrive est deux médias (réglage
      « Afficher les éléments OneDrive dans Photos » désactivé par défaut ; fusion en phase 6).
- [ ] Sans permission médias, OneDrive n'est pas consultable (écran d'autorisation seul ; phase 5).
- [ ] Pas de synchro en arrière-plan : `work-runtime-ktx` et `hilt-work` sont présents mais ne
      servent pas.
- [ ] Les favoris et albums personnalisés sont propres à l'application. Ils sont perdus à la
      désinstallation (base exclue des sauvegardes) et ne sont pas partagés avec Samsung Galerie.
- [ ] `media.contentHash` n'est renseigné que pour OneDrive (`qxh:`) : pas de dédoublonnage.
- [ ] Table `user_settings` inutilisée (les réglages sont dans DataStore) : l'utiliser ou la
      supprimer par migration.
- [ ] Pas de recherche, pas de GIF animés, pas de Motion Photos, pas de pleine résolution au-delà
      d'environ 24 MP.
- [ ] Fidélité visuelle One UI non vérifiée sur des captures de référence.
- [ ] Visionneuse : la date de la barre supérieure (« Aujourd'hui », « Hier ») n'est pas recalculée
      au passage de minuit tant que la visionneuse reste ouverte.
- [ ] Grille : sans placeholders de pagination, un défilement très rapide marque une courte pause
      en atteignant la fin des pages chargées.
- [ ] Albums-dossiers : impossible de revenir au nom d'origine après un renommage d'affichage
      (ajouter « Rétablir le nom du dossier »).
- [ ] Albums : pas de réorganisation par glisser-déposer (`sortIndex` existe mais n'est pas
      modifiable depuis l'interface).
- [ ] Pas d'écran Corbeille (voir phase 7).
