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
- [ ] Visionneuse : original OneDrive au zoom profond (téléchargement à la demande).
- [ ] Choisir le niveau d'aperçu selon la taille de l'écran, le niveau de zoom et le réseau
      (mesuré ou non).
- [ ] Ajouter `PlaybackSource.Remote` (`ui/video/PlaybackSource.kt`) : URL temporaire
      `@microsoft.graph.downloadUrl`, `ResolvingDataSource` au-dessus de `DefaultHttpDataSource`
      (HTTP Range pour le seek) et renouvellement de l'URL à son expiration.
- [ ] Lire en priorité une copie `OFFLINE_COPY` quand elle existe.
- [ ] Vérifier que Media3 1.11.1 renvoie bien `ERROR_CODE_IO_*` pour les erreurs réseau, et
      ajouter les messages correspondants.
- [ ] Partage et « Ouvrir avec » d'un média cloud : télécharger d'abord dans le cache, puis exposer
      le fichier via un FileProvider.
- [ ] Téléchargements hors ligne : file `download_task` (reprise, progression, annulation, erreurs).
      Une copie terminée devient une source `OFFLINE_COPY` et marque `hasOffline`.
- [ ] Action « Conserver hors ligne » / « Libérer » (`pinned`) depuis la visionneuse et en sélection
      multiple.
- [ ] Usage « OneDrive seul » : aujourd'hui, sans permission médias, l'application n'affiche que
      l'écran d'autorisation. Permettre de parcourir OneDrive sans accès aux photos du téléphone.

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
| `app/src/main/java/fr/eva/cloudgallery/ui/video/PlaybackSource.kt` | `TODO(phase 4)` : `PlaybackSource.Remote` (downloadUrl temporaire, `ResolvingDataSource` + `DefaultHttpDataSource`, HTTP Range, copie hors ligne en priorité) |
| `app/src/main/java/fr/eva/cloudgallery/ui/viewer/ViewerPages.kt` | `TODO(phase 4)` : original OneDrive (téléchargement à la demande, zoom, lecture vidéo en streaming) |
| `app/src/main/java/fr/eva/cloudgallery/ui/viewer/ViewerScreen.kt` | `TODO(viewer)` : édition vidéo (découpe) non prise en charge |
| `app/src/main/java/fr/eva/cloudgallery/ui/viewer/ZoomableImage.kt` | `TODO(viewer)` : décodage par tuiles (`BitmapRegionDecoder`) pour les images de plus de 24 MP |

## Limitations connues (rappel)

- [ ] OneDrive en lecture seule et sans originaux : pas de zoom pleine résolution, de lecture
      vidéo, de partage, de copie ni d'« Ouvrir avec » pour un élément présent uniquement sur
      OneDrive (phase 4).
- [ ] Doublons : un média présent sur le téléphone et sur OneDrive est deux médias (réglage
      « Afficher les éléments OneDrive dans Photos » désactivé par défaut ; fusion en phase 6).
- [ ] Sans permission médias, OneDrive n'est pas consultable (écran d'autorisation seul).
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
