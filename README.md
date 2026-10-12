# CloudGallery

Galerie photo/vidéo Android pour usage personnel. Elle reprend l'ergonomie de Samsung Galerie
(One UI 8) et doit, à terme, réunir dans une même bibliothèque les médias de l'appareil et ceux de
OneDrive.

> **État actuel : phases 1 à 3.** L'application indexe les médias de l'appareil (MediaStore), les
> affiche, les organise et les lit (phases 1-2). La phase 3 ajoute **OneDrive en lecture seule** :
> connexion d'un compte Microsoft personnel (MSAL), inventaire de tout le OneDrive par la requête
> delta de Microsoft Graph, albums OneDrive, miniatures et aperçu dans la visionneuse. La phase 4
> ajoute la lecture vidéo en streaming, les aperçus adaptatifs, l'original des images au zoom
> profond, le partage et « Ouvrir avec » des éléments OneDrive, et les **copies hors ligne**
> (« Conserver hors ligne » / « Libérer l'espace », téléchargement en arrière-plan). **Le cache
> géré (LRU, page Stockage), la synchronisation en arrière-plan et l'usage « OneDrive seul » sans
> permission médias ne sont pas implémentés** (phase 5) : les sections qui en parlent portent la
> mention « Prévu, non implémenté ».

- Package : `fr.eva.cloudgallery`, version `0.3.0` (versionCode 3)
- `minSdk 36` (Android 16), `targetSdk 37`, `compileSdk 37`
- Appareil cible : Samsung Galaxy S22 sous One UI 8 / Android 16, bibliothèque de 10 000 à 100 000 médias

---

## Sommaire

1. [Présentation](#présentation)
2. [Architecture](#architecture)
3. [Dépendances](#dépendances)
4. [Permissions Android](#permissions-android)
5. [Indexation locale](#indexation-locale)
6. [Synchronisation OneDrive et gestion du cache](#synchronisation-onedrive-et-gestion-du-cache)
7. [Configuration Microsoft Entra et paramétrage MSAL (phase 3)](#configuration-microsoft-entra-et-paramétrage-msal)
8. [Compilation](#compilation)
9. [Installation sur Samsung Galaxy S22](#installation-sur-samsung-galaxy-s22)
10. [Tests](#tests)
11. [Limitations connues](#limitations-connues)

---

## Présentation

Fonctions disponibles (phases 1 et 2 pour les médias locaux, phase 3 pour OneDrive) :

- **Onglets Photos / Albums / Menu**, avec une barre d'onglets inspirée de One UI.
- **Grille Photos** paginée, triée par date de prise de vue (`dateTaken DESC, id DESC`). Cinq niveaux
  de zoom au pincement (2, 3, 4, 6 ou 9 colonnes en portrait, ×1,75 en paysage), regroupement par jour
  (niveaux 0 à 2) ou par mois (niveaux 3 et 4). Le niveau choisi est mémorisé.
- **Mode sélection** : appui long, glissement pour étendre la sélection (y compris sur des éléments
  pas encore chargés), sélection d'un jour ou d'un mois entier. Actions : favori, partage, ajout à un
  album, copie vers un dossier, suppression.
- **Albums** : un album par dossier local (bucket MediaStore), plus des albums personnalisés
  (« virtuels », qui contiennent des références et non des copies). Tri personnalisé, par nom, par
  date ou par nombre d'éléments. On peut épingler un album et renommer son nom affiché (le dossier
  sur le disque n'est pas renommé). Les collections Favoris et Vidéos sont aussi proposées.
- **Visionneuse** : défilement horizontal, zoom au pincement ou au double appui, bande de miniatures
  (filmstrip) et panneau de détails EXIF (appareil, ISO, flash, GPS avec lien vers une application de
  cartes, emplacement). Rotation sans perte (JPEG/WebP, par l'orientation EXIF). Le rognage
  enregistre une **copie** et ne modifie jamais l'original.
- **Lecteur vidéo** (Media3 ExoPlayer) avec commandes personnalisées : ±10 s, vitesse, son
  coupé/activé, rotation de l'écran, reprise à la dernière position (table `playback_position`) et
  « ouvrir avec une autre application ».
- **Ouverture depuis d'autres applications** : intent `VIEW` pour `image/*` et `video/*`, catégorie
  `APP_GALLERY`. Un fichier qui n'est pas dans l'index (FileProvider, accès partiel…) s'affiche en
  lecture seule.
- **OneDrive (lecture seule)** : écran « Compte OneDrive » (Menu et Paramètres) pour se connecter
  avec un compte Microsoft personnel, voir le profil et le quota, synchroniser et se déconnecter.
  Chaque dossier OneDrive contenant des photos ou vidéos devient un **album OneDrive** (badge nuage).
  Les cellules affichent les miniatures Microsoft Graph, avec un badge nuage pour un élément présent
  uniquement sur OneDrive ; la visionneuse en montre un grand aperçu. Réglage **« Afficher les
  éléments OneDrive dans Photos »** (désactivé par défaut, voir
  [Synchronisation OneDrive](#synchronisation-onedrive-et-gestion-du-cache)). Aucune action ne
  modifie OneDrive.
- **Paramètres** : compte OneDrive et état de sa synchronisation (si ce build est configuré pour
  OneDrive), thème (système / clair / sombre), affichage de la durée des vidéos, lecture
  automatique, corbeille Android, confirmation avant suppression, état de l'indexation, réindexation
  complète.
- Interface en **français** (par défaut) et en **anglais** (`values-en`).

**Sécurité des fichiers.** L'application ne supprime ni ne modifie aucun fichier sans confirmation
système. La suppression passe toujours par `MediaStore.createTrashRequest` (corbeille Android,
récupérable 30 jours, option par défaut) ou par `MediaStore.createDeleteRequest`, et Android affiche
alors sa propre boîte de confirmation. La rotation demande l'autorisation d'écriture avec
`MediaStore.createWriteRequest`. L'indexeur ne modifie que la base Room, jamais les fichiers.
OneDrive est lu en lecture seule (scope `Files.Read`) : aucun fichier OneDrive n'est créé, modifié
ou supprimé, et un élément présent uniquement sur OneDrive n'est jamais proposé au partage, à la
copie ni à la suppression.

---

## Architecture

### Couches

```
fr.eva.cloudgallery
├── ui/          Compose + ViewModels (Hilt), navigation typée (kotlinx.serialization)
│   ├── main/        écran à onglets, MediaSyncCoordinator (indexation locale et synchro OneDrive)
│   ├── account/     écran « Compte OneDrive » (connexion, profil, synchronisation, déconnexion)
│   ├── photos/      onglet Photos          ├── albums/   onglet Albums, grilles d'album
│   ├── grid/        grille paginée, zoom, sélection, séparateurs jour/mois
│   ├── viewer/      visionneuse, zoom, EXIF, rognage, filmstrip, image externe
│   ├── video/       lecteur Media3 et ses commandes
│   ├── menu/ settings/ permissions/ navigation/ components/ (composants One UI) theme/ image/ util/
├── domain/      modèles (Media, Album, MediaContext, Settings, Cloud) + interfaces de dépôts
│   └── repository/Repositories.kt   MediaRepository, AlbumRepository, FavoritesRepository,
│                                    LocalMediaIndexer, LocalMediaWriter, SettingsRepository,
│                                    PlaybackPositionRepository, CloudAccountRepository,
│                                    CloudMediaSyncer, CloudTokenProvider
├── data/
│   ├── db/          Room : AppDatabase, entités, DAO
│   ├── local/       MediaStoreIndexer (MediaStore -> Room), MediaStoreWriter (écritures avec
│   │                confirmation), MediaStoreLogic (logique pure, testée en JVM)
│   ├── cloud/       OneDrive : CloudFeature (build configuré ou non), CloudSessionCoordinator
│   │   │            (session vue par l'UI), CloudDataPurger (retrait des données OneDrive)
│   │   ├── auth/    MSAL : MsalClientProvider, MsalAuthService (état du compte, jetons), CloudAuthLogic
│   │   ├── graph/   Microsoft Graph : GraphHttpClient (OkHttp, 401/429/410), GraphApi, DTO
│   │   ├── sync/    OneDriveSyncer (delta), CloudSyncWriter (Room), OneDriveLogic (logique pure)
│   │   └── thumb/   miniatures Graph pour Coil : CloudThumbnailFetcher/Keyer, CloudThumbnailCache
│   ├── repository/  implémentations des dépôts
│   ├── prefs/       SettingsRepositoryImpl (DataStore Preferences)
│   └── mapper/      entités -> modèles du domaine
└── di/          modules Hilt
```

La couche `ui` ne dépend que des interfaces de `domain`. Les implémentations `data` sont liées par
Hilt. Seul `CloudGalleryApp` relie la couche `data` à Coil (enregistrement du fetcher des
miniatures OneDrive dans l'ImageLoader construit par `ui/image`).

### Modules Hilt (`di/`)

| Module | Rôle |
|---|---|
| `CoroutinesModule` | Qualificateurs `@IoDispatcher` (`Dispatchers.IO`), `@DefaultDispatcher` et `@ApplicationScope` (`CoroutineScope(SupervisorJob() + Default)`) pour le travail qui doit survivre aux écrans |
| `DatabaseModule` | `AppDatabase` (singleton, fichier `cloudgallery.db`, migrations 1→2 et 2→3) et les 10 DAO |
| `DataStoreModule` | Instance unique de `DataStore<Preferences>` (fichier `settings`) |
| `RepositoryModule` | `@Binds` des interfaces du domaine vers `MediaRepositoryImpl`, `AlbumRepositoryImpl`, `FavoritesRepositoryImpl`, `MediaStoreIndexer`, `MediaStoreWriter`, `SettingsRepositoryImpl` et `PlaybackPositionRepositoryImpl` |
| `CloudModule` | `@Binds` de `CloudAccountRepository` (`CloudSessionCoordinator`), `CloudTokenProvider` (`MsalAuthService`), `CloudMediaSyncer` (`OneDriveSyncer`) et `CloudThumbnailCache` (`CoilCloudThumbnailCache`). Graphe sans cycle : le client Graph ne dépend que du fournisseur de jetons |
| `CloudNetworkModule` | `OkHttpClient` de Microsoft Graph, injecté via `dagger.Lazy` et créé au premier appel, sur le dispatcher d'E/S |

`CloudGalleryApp` (`@HiltAndroidApp`) fournit aussi l'`ImageLoader` Coil unique
(`SingletonImageLoader.Factory`) : cache mémoire d'environ 25 % de la mémoire de l'application, cache
disque de 256 Mo (miniatures OneDrive uniquement), miniatures MediaStore chargées par
`ContentResolver.loadThumbnail` (fetcher `LocalThumbnailFetcher`), images de vidéo extraites par
`VideoFrameDecoder`, miniatures OneDrive chargées par `CloudThumbnailFetcher` (voir
[Miniatures et aperçu](#miniatures-et-aperçu-phase-3)). La fabrique des miniatures OneDrive est
légère : le client Graph, OkHttp et MSAL ne sont créés qu'au premier téléchargement, hors du thread
principal.

### Flux de données

```
MediaStore (images + vidéos, VOLUME_EXTERNAL)
   │  MediaStoreIndexer : requêtes par lots de 500, sur Dispatchers.IO
   ▼
Room (media, media_source, album, …)        <- source de vérité de l'UI
   │  DAO -> PagingSource Room (room-paging)
   ▼
Pager (pageSize 150, initialLoad 450, prefetch 450, maxSize 1800, sans placeholders)
   │  Flow<PagingData<MediaSummary>>, cachedIn(viewModelScope)
   ▼
Compose (LazyVerticalGrid + collectAsLazyPagingItems, Coil AsyncImage)
```

OneDrive suit le même principe : `OneDriveSyncer` lit Microsoft Graph (delta) et
`CloudSyncWriter` écrit dans Room (une transaction par page) ; l'UI n'interroge jamais Graph, à
l'exception des miniatures chargées par Coil.

L'UI ne lit jamais MediaStore directement. Elle observe Room, qui invalide automatiquement les
`PagingSource` à chaque écriture de l'indexeur. `maxSize = 1800` limite la mémoire après un long
défilement dans 100 000 médias. Les placeholders sont désactivés parce que les en-têtes jour/mois
sont insérés entre les éléments chargés.

### Schéma Room (version 4, schémas exportés dans `app/schemas/`)

Un **média logique** (`media`) peut avoir **plusieurs sources** (`media_source`) : le fichier local
MediaStore (`LOCAL`), l'élément OneDrive (`ONEDRIVE`) et une copie hors ligne (`OFFLINE_COPY`,
phase 4 : `externalId` = id de l'élément OneDrive, sans dossier ni URI, fichier dans `cached_file`).
Favoris, albums et positions de lecture pointent vers le média logique, ce qui leur permet
de survivre à la disparition ou au remplacement d'une des sources. Les colonnes `hasLocal`,
`hasCloud`, `hasOffline`, `primaryUri`, `cloudItemId` et `cloudCTag` de `media` sont dénormalisées
depuis les sources **visibles** de `media_source` (`MediaDao.refreshSourceFlags`), ce qui évite
toute jointure à la grille. `primaryUri` n'est renseignée que par un fichier local présent : une URI
non nulle signifie « fichier local utilisable ». En phase 3, un élément OneDrive est toujours son
propre média logique (aucune fusion avec un fichier local, voir les limitations).

Migrations : 1→2 (phase 2), 2→3 (phase 3 : colonnes `media.cloudItemId` / `media.cloudCTag`,
table `cloud_folder`) et 3→4 (phase 4 : `cached_file.version`, `download_task.cloudItemId` / `cTag` /
`attempts`), testées par `MigrationTest` (2→3, 1→3, 3→4, 2→4, 1→4).

| Table | Rôle |
|---|---|
| `media` | Média logique : type, nom, MIME, `dateTaken` (EXIF/MediaStore, à défaut date d'ajout puis de modification ; pour OneDrive `photo.takenDateTime`, puis `fileSystemInfo.createdDateTime`, puis `createdDateTime`), dimensions, durée, taille, orientation, `primaryUri`, indicateurs de sources, `cloudItemId` / `cloudCTag` (miniatures OneDrive), `contentHash` (`qxh:<quickXorHash>` pour OneDrive ; pas encore calculé pour les fichiers locaux). Index `(dateTaken, id)`, `(mediaType, dateTaken)`, `displayName`, `contentHash` |
| `media_source` | Une source physique d'un média. `kind` + `externalId` unique (`image:123` / `video:45` pour MediaStore, car les `_ID` images et vidéos peuvent se chevaucher ; id d'élément pour OneDrive), `uri`, `bucketKey` (`local:<BUCKET_ID>` ou `onedrive:<id du dossier parent>`), chemin relatif (`OneDrive/Photos/2024/` pour OneDrive), `generation` (GENERATION_MODIFIED), `eTag`/`cTag`/`sha1Hash`/`quickXorHash` (OneDrive), `volumeName`, `missingSince` (source masquée mais conservée) |
| `album` | Album-dossier (`LOCAL_FOLDER` ou `CLOUD_FOLDER`, `sourceKey` = bucketKey) ou album virtuel (`sourceKey` null). Nom personnalisé, épingle, ordre, couverture, masquage. Un dossier personnalisé n'est jamais supprimé automatiquement (sauf à la déconnexion OneDrive, après confirmation) |
| `cloud_folder` | Dossiers OneDrive connus (id, parent, nom, racine) : noms et chemins des albums OneDrive, masquage du contenu d'un dossier supprimé |
| `album_media` | Appartenance d'un média à un album virtuel (références, pas de copie) |
| `favorite` | Favoris propres à l'application (`mediaId`, date d'ajout) |
| `cached_file` | Fichiers OneDrive copiés sur l'appareil, un par média et par niveau (`ORIGINAL` : originaux temporaires ; `OFFLINE` : copies hors ligne, `pinned`), chemin relatif au dossier du niveau, taille, `lastAccessAt` (nettoyage), `version` = cTag du contenu copié (null = jamais considéré à jour), `pinned` = conservé hors ligne (jamais évincé). Remplacement d'une copie : `CachedFileDao.replace` (même ligne) |
| `download_task` | File des copies hors ligne (`QUEUED`, `RUNNING`, `PAUSED` = arrêtée par l'utilisateur, `FAILED` ; une tâche réussie est supprimée), octets reçus/total, `keepOffline`, `cloudItemId` / `cTag` (version du fichier partiel), `attempts`, `error` (code `DownloadError`, jamais d'URL). Une seule tâche ouverte par média (`DownloadTaskDao.enqueueIfAbsent`, pas d'index partiel) |
| `sync_cursor` | Curseur par fournisseur : `mediastore` (génération + `MediaStore.getVersion`) et `onedrive` (deltaLink Graph, `version` = `<drive.id>\|<version du $select>`). Dates de dernière synchro et de dernière synchro complète, dernière erreur (code, traduit à l'affichage) |
| `user_settings` | Table clé/valeur réservée. Les réglages actuels sont stockés dans DataStore |
| `playback_position` | Position de reprise vidéo (enregistrée seulement entre 3 s après le début et 3 s avant la fin) |

Les clés étrangères vers `media` sont en `ON DELETE CASCADE`. La base est **exclue des sauvegardes
Android** (`backup_rules.xml`, `data_extraction_rules.xml`) : elle référence des `_ID` MediaStore
propres à l'appareil. Les fichiers de jetons MSAL (SharedPreferences chiffrées par une clé du
Keystore, inutilisables sur un autre appareil), la clé MSAL enveloppée par le Keystore
(`app_fr.eva.cloudgallery/adalks`, répertoire utilisé uniquement par MSAL), l'état de
synchronisation OneDrive (`cloud_sync.xml`), `msal_config.json` (`noBackupFilesDir`) et les copies
hors ligne (`files/offline/`) en sont aussi exclus. Seules les préférences de l'application sont
sauvegardées.

---

## Dépendances

Versions exactes tirées de `gradle/libs.versions.toml` :

| Composant | Version |
|---|---|
| Android Gradle Plugin | 9.4.1 |
| Gradle (wrapper) | 9.6.0 |
| Kotlin (plugins Compose et Serialization) | 2.4.21 |
| KSP | 2.3.12 |
| Hilt (`hilt-android`, `hilt-compiler`) | 2.60.1 |
| AndroidX Hilt (`hilt-navigation-compose`, `hilt-work`, `hilt-compiler`) | 1.4.0 |
| Core KTX | 1.19.1 |
| Activity Compose | 1.13.0 |
| Lifecycle (runtime, runtime-compose, viewmodel-compose, process) | 2.11.0 |
| Navigation Compose | 2.10.2 |
| Compose BOM | 2026.09.00 |
| Material Icons Extended | 1.7.8 |
| Room (runtime, ktx, paging, compiler, testing) | 2.8.5 |
| Paging (runtime, compose, testing) | 3.5.2 |
| WorkManager (`work-runtime-ktx`) | 2.12.0 (déclaré, pas encore utilisé) |
| DataStore Preferences | 1.2.1 |
| Media3 (exoplayer, ui-compose, ui) | 1.11.1 |
| ExifInterface | 1.4.2 |
| Coil 3 (`coil-compose`, `coil-video`) | 3.6.3 |
| MSAL Android (`com.microsoft.identity.client:msal`) | 8.5.0 |
| OkHttp | 5.5.0 |
| kotlinx-coroutines (android, test) | 1.11.0 |
| kotlinx-serialization-json | 1.11.0 |
| JUnit | 4.13.2 |
| AndroidX Test ext JUnit | 1.3.0 |
| Espresso Core | 3.7.0 |

Le plugin `org.gradle.toolchains.foojay-resolver-convention` 1.0.0 est déclaré dans
`settings.gradle.kts`.

**Dépôt supplémentaire pour MSAL.** MSAL dépend (via `com.microsoft.identity:common`) de
`com.microsoft.device.display:display-mask:0.3.0`, publié uniquement sur le flux Maven du SDK Surface
Duo (`https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1`).
Ce dépôt est déclaré dans `settings.gradle.kts` en `exclusiveContent`, limité au groupe
`com.microsoft.device.display` : il n'est jamais interrogé pour autre chose. Les règles R8 de MSAL
sont fournies par ses AAR (aucune règle à ajouter côté application).

OkHttp sert uniquement au client Microsoft Graph et aux miniatures OneDrive (fetcher Coil maison) :
le module réseau de Coil (`coil-network-okhttp`) n'est pas utilisé.

---

## Permissions Android

Déclarées dans `AndroidManifest.xml` :

| Permission | Usage |
|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | Lecture de toute la bibliothèque (accès complet) |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Accès partiel « Sélectionner des photos » (Android 14 et plus) |
| `ACCESS_MEDIA_LOCATION` | Coordonnées GPS non masquées dans les EXIF (`MediaStore.setRequireOriginal`) |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Connexion Microsoft (MSAL) et Microsoft Graph (OneDrive). Aucun accès réseau sans compte OneDrive connecté |
| `RUN_USER_INITIATED_JOBS` | Téléchargement des copies hors ligne par une tâche de transfert lancée par l'utilisateur (JobScheduler `setUserInitiated`), sans quota ni limite de durée |
| `POST_NOTIFICATIONS` | Progression des copies hors ligne. Demandée une seule fois, au premier « Conserver hors ligne » ; refusée, les téléchargements fonctionnent quand même (progression visible dans l'application et dans le Gestionnaire de tâches) |

Les bibliothèques de MSAL ajoutent au manifeste fusionné la permission `NFC`, des fonctionnalités
matérielles facultatives (NFC, hôte USB : clés de sécurité YubiKey) et quelques activités
techniques. CloudGallery ne s'en sert pas ; elles n'empêchent pas l'installation sur un appareil
qui n'a pas ces fonctions.

Les quatre permissions médias sont demandées ensemble, et Android propose alors « Autoriser tout »,
« Sélectionner des photos » ou « Refuser ». L'état est revérifié à chaque `ON_RESUME`
(`MediaPermissionController`).

Comportement selon l'état (`MediaAccess`) :

- **FULL** (images et vidéos accordées) : indexation complète. Une source qui disparaît de MediaStore
  et dont l'absence est confirmée (y compris en interrogeant la corbeille et les éléments en
  attente) est retirée de la base, sauf si la disparition est massive (voir plus bas).
- **PARTIAL** (sélection de l'utilisateur, ou un seul des deux types accordé) : l'application
  n'affiche que les éléments accessibles, avec un bandeau « Accès limité à certaines photos · Gérer »
  qui relance la demande système pour modifier la sélection. **Rien n'est retiré définitivement de la
  base** : les éléments qui ne sont plus visibles sont seulement masqués (`missingSince`), avec leurs
  favoris et leurs albums, et ils réapparaissent si l'accès est rétabli. Les albums-dossiers ne sont
  pas nettoyés.
- **NONE** : écran d'autorisation. Après un refus définitif, il propose d'ouvrir les paramètres de
  l'application. L'indexeur ne s'exécute pas : sans permission, MediaStore renverrait une liste vide,
  et la base est protégée contre un vidage.
- **ACCESS_MEDIA_LOCATION refusée** : l'application fonctionne normalement. Le panneau de détails
  relit simplement l'image avec ses coordonnées GPS masquées.

---

## Indexation locale

Implémentée dans `data/local/MediaStoreIndexer.kt`, avec la logique pure dans `MediaStoreLogic.kt`.
Elle s'exécute entièrement sur `Dispatchers.IO`, et un `Mutex` empêche deux synchronisations
simultanées.

1. **Curseur de génération.** Avant toute lecture, l'indexeur relève `MediaStore.getGeneration` (le
   maximum sur les volumes externes montés). Une synchro incrémentale ne relit que les lignes dont
   `GENERATION_MODIFIED` dépasse le curseur enregistré dans `sync_cursor` (`provider = "mediastore"`).
   Une modification validée pendant la synchro aura une génération plus élevée et sera relue la fois
   suivante. Une relecture complète réécrit seulement ce qui a changé.
2. **Version MediaStore.** `MediaStore.getVersion(context, VOLUME_EXTERNAL)` est mémorisée. Si elle
   change (base MediaStore recréée, restauration), les générations et les `_ID` ne sont plus
   comparables. L'indexeur fait alors une relecture complète, marque les sources locales « à
   ré-identifier » puis les ré-associe par empreinte (`chemin relatif | nom | taille`) afin de
   conserver favoris, albums et positions de lecture.
3. **Diff des suppressions.** Les `_ID` visibles sont comparés aux sources connues. Une source
   absente est :
   - **masquée** (`missingSince`) si l'accès est partiel, si elle est encore présente en corbeille ou
     en attente (requête `MATCH_INCLUDE`), si son volume est démonté (carte SD) ou si la vérification
     est impossible ;
   - **supprimée de la base** (jamais du disque) si elle a vraiment disparu et que l'accès est
     complet ;
   - soumise au **frein anti-suppression massive** : si plus de 200 sources **et** plus de 25 % de la
     bibliothèque disparaissent d'un coup, elles sont seulement masquées et un avertissement
     s'affiche. Une source masquée n'est purgée qu'après **30 jours** et une fois sa disparition
     confirmée ;
   - si MediaStore ne renvoie plus rien du tout alors que la base contient des médias, rien n'est
     touché (état transitoire du fournisseur).
4. **Albums-dossiers** : créés ou mis à jour à partir des buckets. Un dossier qui disparaît n'est
   supprimé qu'en accès complet, et jamais s'il porte une personnalisation.
5. **ContentObserver** sur `MediaStore.Files` (`VOLUME_EXTERNAL`, descendants inclus). Les
   notifications sont regroupées dans un canal `CONFLATED` avec un **debounce de 1,5 s**, puis
   déclenchent une synchro incrémentale. Les demandes rapprochées sont fusionnées : une synchro qui
   démarre après une demande la satisfait.
6. **Synchro au premier plan** : `CloudGalleryApp` observe `ProcessLifecycleOwner` et appelle
   `MediaSyncCoordinator.onForeground()` à chaque retour de l'application. La synchro est aussi
   lancée quand l'accès change (accordé, étendu, nouvelle sélection) et à la demande depuis Menu
   (« Synchroniser maintenant ») ou Paramètres (« Réindexer complètement », après confirmation).

Il n'y a **pas de synchro en arrière-plan** quand l'application est fermée. Les changements sont
rattrapés au prochain passage au premier plan grâce au curseur de génération.

---

## Synchronisation OneDrive et gestion du cache

> **Synchronisation, miniatures et aperçu : implémentés (phase 3).** Streaming vidéo, aperçus
> adaptatifs, originaux d'image, partage et « Ouvrir avec » : **implémentés (phase 4)**. Hors ligne
> et cache géré (LRU, page Stockage) : **prévus (phase 4 tranche 3, phase 5).** La fonction n'apparaît que
> dans un build configuré pour OneDrive (voir
> [Configuration Microsoft Entra](#configuration-microsoft-entra-et-paramétrage-msal)).

### Synchronisation (phase 3)

- **Authentification** : MSAL Android 8.5.0 en client public (aucun secret), mode compte unique,
  autorité AAD avec l'audience `PersonalMicrosoftAccount` (comptes Microsoft personnels). Scopes
  demandés : `Files.Read` et `User.Read` (MSAL ajoute lui-même `openid`, `profile` et
  `offline_access`). La connexion s'ouvre dans un onglet de navigateur ; les jetons sont renouvelés
  silencieusement (`acquireTokenSilent`, sur le thread d'E/S). Si une interaction est nécessaire,
  l'écran Compte affiche « Reconnexion requise » et les données déjà indexées sont conservées. Hors
  ligne, la session reste valide. Un jeton demandé avant une déconnexion et obtenu après est
  abandonné (ni mis en cache, ni compte « connecté » rétabli). Une lecture du compte MSAL en échec
  est retentée automatiquement (au plus toutes les 30 s) avant une synchro ou une miniature.
- **Inventaire** : Microsoft Graph `GET /me/drive/root/delta` sur **tout le OneDrive**, avec un
  `$select` explicite (`id, name, size, eTag, cTag, createdDateTime, lastModifiedDateTime,
  fileSystemInfo, file, folder, package, image, photo, video, parentReference, deleted, root,
  remoteItem`). Les pages `@odata.nextLink` sont suivies jusqu'au `@odata.deltaLink`, enregistré
  **à la fin** dans `sync_cursor` (`provider = "onedrive"`) : un passage interrompu reprend depuis
  le curseur précédent. Les liens sont utilisés tels quels, jamais reconstruits. Le curseur est lié
  au lecteur (`drive.id`) et à la version du `$select` : changer la liste de champs force une
  énumération complète.
- **Filtrage** : seuls les fichiers `image/*` et `video/*` sont gardés (SVG exclus ; à défaut de
  type MIME, les facettes `image`/`photo`/`video` décident). Les blocs-notes OneNote (`package`) et
  les éléments partagés par d'autres (`remoteItem`) sont ignorés.
- **Écriture** : une page = une transaction Room. Chaque élément OneDrive devient **son propre média
  logique** avec une `media_source` de type `ONEDRIVE` (`externalId` = id d'élément, `bucketKey` =
  `onedrive:<id du dossier parent>`, chemin lisible `OneDrive/Photos/2024/`, `cTag`/`eTag`,
  `quickXorHash`, `sha1Hash` si fourni). Il n'est **jamais** rattaché à un fichier local, ni par le
  nom ni par le contenu (dédoublonnage prévu en phase 6).
- **Albums OneDrive** : chaque dossier qui contient des photos ou vidéos devient un album
  `CLOUD_FOLDER` (badge nuage), dont le nom suit les renommages sur OneDrive. Renommage d'affichage
  et épingle possibles ; pas de suppression ni de « Copier vers » un dossier OneDrive.
- **Suppressions** : un élément `deleted` est **masqué** (`missingSince`), puis retiré de l'index
  après **30 jours**. Un dossier supprimé ne masque son contenu qu'en fin de passage, s'il est
  toujours absent ; un dossier restauré depuis la corbeille OneDrive déclenche une énumération
  complète : quand un nouveau dossier apparaît alors que des éléments masqués sont dans ce dossier
  ou dans un dossier retiré de l'index (sous-dossiers d'un dossier supprimé), le deltaLink est
  effacé dans la transaction de la page (même interrompu, le passage suivant repart d'une
  énumération complète) et l'énumération démarre aussitôt. Pendant les 30 jours qui suivent la
  suppression d'un dossier contenant des médias, la création d'un nouveau dossier déclenche donc
  aussi une énumération complète. `410 Gone` (jeton delta expiré) : nouvelle énumération complète depuis l'URL `Location`
  (au plus deux par passage), puis les éléments non revus sont masqués. Rien n'est supprimé
  immédiatement ; aucun fichier local ni OneDrive n'est touché.
- **Déclenchement** : après la connexion, au retour au premier plan (au plus une fois toutes les
  5 minutes, indépendamment de la permission médias) et par « Synchroniser maintenant » (Menu, qui
  lance aussi l'indexation locale, et écran Compte). « Réindexer complètement » (Paramètres) reste
  local. Un seul passage à la fois ; les demandes simultanées sont regroupées.
- **Limitation de débit** : `429`, `502`, `503`, `504` : `Retry-After` (en secondes) respecté, sinon
  attente exponentielle 2 s → 60 s, 5 nouvelles tentatives au plus ; un `Retry-After` de plus de
  10 minutes suspend la synchro jusqu'à cette échéance. Toute pause demandée (« pause all further
  requests ») s'applique à toutes les requêtes Graph suivantes, y compris quand la requête qui l'a
  reçue abandonne : une pause de 10 minutes au plus est attendue, au-delà les requêtes (miniatures
  comprises) échouent aussitôt sans être envoyées. La fin de la pause de synchronisation est
  enregistrée (`cloud_sync.xml`) et respectée après un redémarrage ; elle est effacée avec les
  données OneDrive. `401` : un renouvellement forcé du jeton, puis, si le nouveau jeton est lui
  aussi refusé, reconnexion requise (le compte passe en « Reconnexion requise »). Le jeton n'est
  envoyé qu'à `https://graph.microsoft.com`.
- **Autre compte** : si le OneDrive connecté n'est pas celui des données de l'index, la synchro est
  suspendue et l'écran Compte propose de remplacer ces données, **après confirmation**. Rien n'est
  purgé automatiquement. Tant que ce décalage est constaté, aucune miniature des éléments de
  l'autre compte n'est demandée à Graph (seules celles déjà en cache disque s'affichent).
- **Déconnexion** (confirmation obligatoire) : déconnexion MSAL, puis retrait de toutes les données
  OneDrive de l'index : sources, médias présents uniquement sur OneDrive avec leurs favoris et
  leurs ajouts aux albums, albums OneDrive (y compris renommés ou épinglés), dossiers, curseur,
  miniatures et originaux en cache, **copies hors ligne** et file de téléchargement. Les
  téléchargements en cours sont arrêtés avant la déconnexion. La confirmation indique le nombre de
  copies hors ligne supprimées et, s'il y en a, combien sont les **dernières copies** d'éléments
  déjà supprimés de OneDrive. Même chose pour la purge des données restantes et le changement de
  compte. Les fichiers sur OneDrive et sur le téléphone ne sont pas modifiés. Si la déconnexion
  MSAL échoue, les données sont conservées et les téléchargements reprennent.
- **Réglage « Afficher les éléments OneDrive dans Photos »** (désactivé par défaut, Paramètres et
  écran Compte) : désactivé, Photos et Vidéos (grille, nombres, sélection par jour ou par
  glissement) ne montrent que les médias présents sur l'appareil ou **conservés hors ligne** (toujours
  affichés, même réglage désactivé) ; les éléments
  présents uniquement sur OneDrive restent visibles dans leurs albums OneDrive, les Favoris et les
  albums personnalisés. Activé, tout est mélangé : une photo présente sur le téléphone **et** sur
  OneDrive (sauvegarde de l'appareil photo, par exemple) apparaît **deux fois** jusqu'au
  dédoublonnage de la phase 6.
- **État** : sous-titre de l'entrée OneDrive du Menu (synchro en cours, date de la dernière synchro
  et nombre d'éléments, ou erreur), ligne « Synchronisation OneDrive » des Paramètres et écran
  Compte (profil, quota, « Synchroniser maintenant »). Les erreurs sont enregistrées sous forme de
  code et traduites à l'affichage.
- **Journaux** : ni jeton, ni deltaLink, ni nom de compte.
- **Planification** (phase 5, prévu) : WorkManager périodique (contrainte réseau, éventuellement
  Wi-Fi seulement) en plus de la synchro au premier plan.

### Miniatures et aperçu (phase 3)

- **Coil** : modèle `CloudThumbnail` (id d'élément + cTag), `CloudThumbnailKeyer` et
  `CloudThumbnailFetcher` (`data/cloud/thumb/`). Requête
  `GET /me/drive/items/{id}/thumbnails/0/{taille}/content` ; Graph redirige vers l'image, et OkHttp
  suit la redirection sans l'en-tête `Authorization` (autre hôte).
- **Tailles** : vignette carrée `c{n}x{n}_crop` au palier de la cellule (192 à 1024 px, mêmes
  paliers que les miniatures locales), `smallSquare` pour 96 px. Si Graph refuse une taille
  personnalisée (400 ou 404), la taille standard `mediumSquare` (176 px) ou `largeSquare` (800 px)
  est demandée, et les tailles standard sont ensuite utilisées directement. Aperçu de la
  visionneuse : palier `c1600x1600` ou `c2048x2048` (proportions d'origine, voir « Niveaux de
  qualité »), repli sur le palier inférieur puis `large` (800 px). Les miniatures Graph sont déjà
  orientées.
- **Cache** : clé = id + cTag + palier (`p<côté>` pour un aperçu ; un renommage ne l'invalide pas,
  un nouveau contenu si).
  Écriture dans le cache disque de l'ImageLoader (`cacheDir/coil`, 256 Mo, géré par Coil) : les
  miniatures déjà vues s'affichent hors connexion. 4 téléchargements simultanés au plus (Coil
  annule ceux des cellules sorties de l'écran) ; une miniature introuvable (404) n'est pas
  redemandée pendant 10 minutes. Téléchargement uniquement pour un compte connecté ; la
  déconnexion vide le cache disque et les miniatures OneDrive du cache mémoire. Une miniature en
  cours de téléchargement pendant une purge n'est pas enregistrée (`CloudDataGeneration`,
  incrémenté avant la purge et commun aux miniatures et aux originaux).
- **Priorité locale** : une cellule, une couverture d'album, la bande de miniatures ou le sélecteur
  d'album utilisent toujours le fichier local s'il existe (MediaStore), la miniature OneDrive sinon.
- **Visionneuse** : une image présente uniquement sur OneDrive affiche l'aperçu, zoomable, avec la
  mention « Aperçu OneDrive : zoomez pour afficher l'original » ; l'original remplace l'aperçu au
  zoom profond (voir « Originaux d'image »). Une vidéo affiche l'aperçu, un bouton de lecture
  (streaming) et sa durée. Partager et « Ouvrir avec » téléchargent l'original ; rotation, rognage,
  « Copier vers » et suppression restent réservés aux fichiers de l'appareil (masqués). Aperçu
  impossible (hors connexion sans cache, session expirée) : message explicatif.
- **Sélection** : Supprimer et Copier sont désactivés si la sélection ne contient aucun fichier
  local (tout un album OneDrive, par exemple) ; pour une sélection mixte, ils ne traitent que les
  fichiers locaux, et les boîtes de confirmation affichent le nombre exact de fichiers concernés et
  le nombre d'éléments OneDrive non concernés. Partager accepte aussi les éléments OneDrive (voir
  « Partage et Ouvrir avec »). Favori et Ajouter à l'album s'appliquent à tout.
- **Lecteur vidéo** : un fichier local est lu en priorité ; sinon, pour un compte connecté, la vidéo
  OneDrive est lue en streaming (voir « Niveaux de qualité »). Dans le lecteur, « Ouvrir avec »
  n'est proposé que pour un fichier local (depuis la visionneuse, il télécharge la vidéo OneDrive).

### Niveaux de qualité (phase 4)

| Niveau | Source | Usage | État |
|---|---|---|---|
| Miniature | `/items/{id}/thumbnails/0/c{n}x{n}_crop/content` (repli `smallSquare`/`mediumSquare`/`largeSquare`) | Grille, couvertures d'album, bande de miniatures | Implémenté (phase 3) |
| Aperçu | `/items/{id}/thumbnails/0/c{1600\|2048}x{…}/content` (repli palier inférieur, puis `large`) | Visionneuse (zoomable) | Implémenté (phase 3, paliers en phase 4) |
| Original | `@microsoft.graph.downloadUrl` (URL temporaire, sans en-tête d'authentification, compatible HTTP Range) | Lecture vidéo en streaming | Implémenté (phase 4) |
| Original | idem, fichier temporaire `cacheDir/onedrive/originals/` | Zoom profond, partage, « Ouvrir avec » | Implémenté (phase 4) |

**Palier d'aperçu** (`previewSideFor`) : le plus petit palier (1600 ou 2048 px) qui couvre le grand
côté de l'écran, jamais au-delà de ce qu'exige l'image elle-même, et 1600 au plus sur un réseau
facturé (données mobiles, économiseur de données, état inconnu). Le palier est fixé à l'ouverture
de la page. Si Graph refuse `c2048x2048` (400/404) alors que `c1600x1600` passe, le palier 2048 est
abandonné jusqu'au redémarrage. Les aperçus 2048 occupent davantage le cache disque Coil (256 Mo)
partagé avec les miniatures ; la phase 5 décidera d'un cache séparé.

**Originaux d'image** (`data/cloud/content/CloudOriginalStore`) :
- Au-delà de 2,5x, si l'image affichée dépasse l'aperçu de plus de 20 % et que l'image elle-même est
  plus grande que l'aperçu (`originalDecision`) : téléchargement **automatique sur réseau non
  facturé** ; sur réseau facturé, pastille « Charger l'original (taille) », sauf si le réglage
  « Charger les originaux sur réseau mobile » est activé. Progression annulable, erreur avec
  nouvel essai ; l'original remplace l'aperçu sans perdre le cadrage.
- Téléchargement depuis l'URL temporaire (`CdnDownloader` : HTTPS hors Graph seulement, aucun
  en-tête d'authentification, `Accept-Encoding: identity`, reprise `Range` d'un fichier partiel de
  la même version seulement, version redemandée à Graph juste avant chaque reprise (jamais celle
  d'une URL en cache ; Graph ne documente pas `If-Range`), 200 en réponse à un Range = reprise à
  zéro, nouvelle URL une fois après 401/403/404/410, Retry-After jusqu'à 60 s, écriture au fil de
  l'eau, `fsync`, taille vérifiée, appel OkHttp annulé dès l'abandon, y compris pendant la lecture
  du corps).
- Espace vérifié avant le téléchargement : si l'espace libre ne suffit pas mais que le système peut
  libérer le cache d'autres applications (`getAllocatableBytes`), il le libère
  (`StorageManager.allocateBytes`) ; sinon « Espace de stockage insuffisant ».
- Fichier `.part` renommé une fois complet, puis enregistré dans `cached_file` (`ORIGINAL`,
  `version` = cTag) en une transaction qui revérifie que les données OneDrive n'ont pas été vidées
  entre-temps et que l'élément est toujours le même (identifiant, version) ; sinon le fichier est
  supprimé. Un fichier du même nom déjà présent (version inconnue, original précédent) n'est jamais
  écrasé : le nouveau prend un autre nom et l'ancien n'est supprimé qu'après l'enregistrement du
  nouveau (un partage déjà transmis reste lisible). Un même original n'est téléchargé qu'une fois
  pour tous ses demandeurs (visionneuse, partage) et s'arrête quand plus personne ne l'attend.
- Nom de fichier opaque (`<empreinte de l'id>_<empreinte du cTag>.<ext>`), jamais dérivé du nom
  OneDrive. Un original déjà présent et à jour est réutilisé à la prochaine ouverture.

**Partage et « Ouvrir avec »** : fichier local tel quel ; sinon, pour un compte connecté, l'original
OneDrive est téléchargé (boîte de progression annulable), puis transmis par
`CloudGalleryFileProvider` (`${applicationId}.fileprovider`, dossiers `onedrive/originals/` et
`offline/` seuls, lecture seule, nom affiché = nom OneDrive nettoyé). Plafonds : **20 éléments OneDrive et 500 Mo par
partage** (les fichiers locaux ne comptent pas). CloudGallery est exclue du sélecteur
(`EXTRA_EXCLUDE_COMPONENTS`) pour ne pas se proposer elle-même. Sans compte connecté, les éléments
OneDrive d'une sélection mixte sont signalés comme non partagés.

**Lecture vidéo en streaming** (`data/cloud/stream/`) : ExoPlayer lit l'élément par une URI stable
`onedrive://item/<id>`. Un `ResolvingDataSource` la remplace, à chaque ouverture (lecture, seek,
nouvelle tentative), par l'URL temporaire, lue ensuite par `DefaultHttpDataSource` (requêtes Range
pour le seek, aucun en-tête d'authentification).
- L'URL est demandée à Graph (`GET /me/drive/items/{id}` sans `$select`, qui sur OneDrive personnel omettrait
  `@microsoft.graph.downloadUrl`),
  gardée **en mémoire seulement**, deux minutes au plus (`CloudDownloadUrlProvider`), et oubliée à
  la déconnexion ou à la purge.
- Un statut 401/403/404/410 de l'hôte de téléchargement fait oublier l'URL : la tentative suivante en
  obtient une nouvelle.
- Reconnexion nécessaire, élément supprimé sur OneDrive ou limitation Graph : échec immédiat, avec un
  message dédié (et « Se connecter » vers l'écran Compte si besoin).
- Le streaming est autorisé sur tout réseau, y compris mobile : l'utilisateur a appuyé sur Lecture.
- **Journaux** : l'URL vaut un jeton. Elle n'est jamais journalisée ni persistée. Media3 la masque
  dans ses événements (URI stable rapportée), et un journal Media3 expurgé de toute URL
  (`RedactingMedia3Logger`) est installé au démarrage.

Une copie hors ligne intacte (présente, taille attendue, vérifiée hors du thread principal) est lue
en priorité, avant le streaming et sans compte (voir `ui/video/PlaybackSource.kt`) ; « Ouvrir avec »
la transmet alors par le FileProvider.
Les accès au contenu OneDrive (miniatures, URL de lecture, originaux) passent tous par `CloudAccessGuard` :
compte connecté, et OneDrive identique à celui des données de l'index. L'état du réseau par défaut
(mesuré ou non, économiseur de données) est exposé par `data/net/NetworkStatus` pour les choix
d'aperçus et de téléchargements.

### Copies hors ligne (phase 4)

« **Conserver hors ligne** » (menu de la visionneuse, ou menu « Plus » d'une sélection) télécharge
une copie complète d'éléments OneDrive sur l'appareil ; « **Libérer l'espace** » la supprime. Ce
sont des fichiers privés de l'application : ni MediaStore ni les fichiers de l'utilisateur ne sont
jamais touchés, et aucune confirmation système n'est nécessaire.

- **Stockage** : `filesDir/offline/` (jamais vidé par le système, exclu des sauvegardes), noms
  opaques `<sha1(id)>_<sha1(cTag)>.<ext>`. Chaque copie est enregistrée dans `cached_file`
  (`OFFLINE`, `pinned`, `version` = cTag) et devient une source `OFFLINE_COPY` : `hasOffline`, badge
  « hors ligne » dans la grille, affichage et lecture depuis la copie, partage et « Ouvrir avec »
  sans téléchargement (FileProvider, `<files-path name="offline">`). `primaryUri` reste réservée
  aux fichiers locaux : modifier, pivoter, rogner, supprimer et copier restent impossibles.
- **Téléchargement** : tâche de transfert lancée par l'utilisateur (JobScheduler
  `setUserInitiated`, espace de noms « offline »), sans quota ni limite de 6 h ; WorkManager 2.12 ne
  sait pas créer ce type de tâche. Un élément à la fois (`OfflineDownloadEngine`) : fichier `.part`
  repris par HTTP Range pour la même version, `fd.sync()`, taille vérifiée, renommage, puis
  enregistrement en une transaction qui revérifie que les données OneDrive n'ont pas été purgées,
  que la tâche n'a été ni libérée ni annulée et que l'élément est toujours dans l'index avec la
  même version. Connexions sur le réseau choisi par le système (`JobParameters.getNetwork`).
  « Libérer » ou « Annuler » d'un élément en cours arrête seulement cet élément (le passage continue
  avec les suivants) ; une tâche supprimée pendant son téléchargement l'interrompt au relevé de
  progression suivant. Avant de se terminer, le travail revérifie la file : une tâche ajoutée à ce
  moment-là est prise par un nouveau passage ou confiée à un nouveau travail (suivi du travail dans
  le processus, `OfflineJobTracker` : JobScheduler renvoie encore un travail qui vient de finir).
- **Réseau** : réglage « **Télécharger hors ligne en Wi-Fi uniquement** » (activé par défaut,
  Paramètres) ; sur réseau mobile, une boîte propose « **Télécharger maintenant** » (aussi dans
  l'écran Compte), qui lève la restriction **pour les tâches déjà en file à cet instant
  seulement** : les tâches ajoutées ensuite (dont les nouvelles versions trouvées par la synchro)
  suivent de nouveau le réglage. Le réseau est vérifié avant chaque élément ; changer le réglage
  replanifie aussitôt le travail en cours ou planifié (activé : un passage sur réseau mobile s'arrête
  et attend le Wi-Fi ; désactivé : un travail qui attendait le Wi-Fi démarre).
- **Notification** : progression (élément en cours, éléments restants) avec un bouton « Annuler »,
  puis bilan. L'autorisation est demandée au premier « Conserver hors ligne » ; refusée, les
  téléchargements continuent (progression dans l'application).
- **Erreurs** : réseau, limitation, erreur serveur : nouvel essai plus tard (attente gérée par le
  système, 5 tentatives ; une coupure réseau après des octets reçus ne compte pas) ; contenu plus
  récent sur OneDrive que dans l'index : la tâche attend la prochaine synchronisation sans compter
  d'essai ni bloquer les suivantes ; élément introuvable : échec de cet élément ; compte déconnecté
  ou espace insuffisant : toute la file échoue. Un échec se reprend depuis la visionneuse ou l'écran
  Compte.
- **Espace** : vérifié avant la mise en file (`StorageManager.getAllocatableBytes`, marge 200 Mo) ;
  s'il manque de la place, l'écran système « Libérer de l'espace » est proposé. Au début de chaque
  passage, si l'espace libre ne suffit pas pour la file mais que le cache d'autres applications peut
  être libéré, le système est prié de le libérer (`allocateBytes`, une seule demande par passage).
  Un lot de plus de **200 éléments ou 1 Go** demande confirmation (taille totale et espace
  disponible).
- **Arrêt** : un arrêt depuis le Gestionnaire de tâches (ou un arrêt forcé pendant un
  téléchargement) met la file **en pause** jusqu'à ce que l'utilisateur la reprenne ; balayer
  l'application depuis les applications récentes sans téléchargement en cours ne change rien ; un
  arrêt pour perte du réseau ou du Wi-Fi reprend automatiquement. La tâche n'est pas persistante :
  après un redémarrage, la file reprend à la prochaine ouverture de l'application.
- **Nouvelle version sur OneDrive** : la synchro met en file un nouveau téléchargement (règle Wi-Fi
  respectée, lancé au premier plan) ; l'ancienne copie reste affichée jusqu'à ce que la nouvelle la
  remplace. Si cette mise à jour est en pause ou en échec, les Détails le signalent (« nouvelle
  version non téléchargée » et sa cause) et la visionneuse propose « Reprendre le téléchargement »
  et « Annuler le téléchargement » pour cet élément.
- **Élément supprimé de OneDrive** : sa copie est masquée avec lui (aussi pour un dossier supprimé
  ou une énumération complète) et réapparaît s'il est restauré. Au bout des 30 jours de grâce, un
  élément qui a une copie hors ligne **n'est pas purgé** : l'écran Compte affiche « N copies hors
  ligne d'éléments supprimés de OneDrive » (avec leurs aperçus) et le Menu une pastille. «
  **Supprimer les copies** » (confirmation) les retire ; « **Les conserver** » les garde, visibles,
  comme seuls exemplaires (« supprimé de OneDrive » dans les Détails) : elles ne partent ensuite que
  par « Libérer l'espace », avec une confirmation qui signale la dernière copie. Les deux actions ne
  portent que sur la liste affichée et confirmée, revérifiée dans leur transaction : un élément
  revenu sur OneDrive entre-temps n'est pas touché, un élément arrivé au terme des 30 jours après
  l'affichage attend la prochaine décision. Si l'élément revient sur OneDrive, il est rattaché au
  même média (favoris et albums conservés). Les éléments sans copie sont purgés comme avant.
- **Dernières copies** : « Libérer » compte les dernières copies (sans élément OneDrive visible ni
  fichier local) parmi la sélection, copies masquées comprises (élément supprimé de OneDrive depuis un
  autre appareil pendant que la sélection était ouverte) : confirmation dès qu'il y en a une. Si ce
  compte échoue, rien n'est supprimé (message). Les confirmations de déconnexion, de retrait des
  données restantes et de changement de compte ne peuvent être validées qu'une fois le nombre de
  copies (et de dernières copies) connu ; s'il n'a pas pu être lu, elles avertissent que certaines
  copies sont peut-être les dernières.
- **Écran Compte** : section « Hors ligne » (en cours, en attente du Wi-Fi, en pause, échecs,
  copies présentes et taille), « Télécharger maintenant », « Reprendre », « Annuler les
  téléchargements ».

### Cache (phase 4-5)

> **Phase 4 : cache temporaire des originaux et copies hors ligne.** LRU et page Stockage : **prévus
> (phase 5).**

- **Originaux (phase 4)** : `cacheDir/onedrive/originals/` (jamais `cacheDir/coil`), plafonné à
  **1 Go** ; au premier passage au premier plan de l'application, les originaux inutilisés depuis
  plus de **7 jours** sont supprimés, puis les plus anciens au-delà du plafond, ainsi que les lignes
  sans fichier, les fichiers sans ligne (plus d'une heure) et les fichiers partiels abandonnés (plus
  de 24 h) (règles pures : `planOriginalTrim`, `filesToSweep`). Une ligne n'est supprimée que si elle
  n'a pas changé depuis sa lecture (original rouvert ou remplacé entre-temps : gardé), et jamais
  pendant la lecture d'un original par la visionneuse ou le partage. Android peut aussi vider ce dossier en cas de manque d'espace. La déconnexion, la purge
  des données restantes et le changement de compte suppriment tous les originaux.
- Les fichiers sont recensés dans `cached_file` (un par média et par niveau), avec `lastAccessAt`.
- **Éviction LRU** sous un plafond configurable : les fichiers les moins récemment consultés sont
  supprimés en premier. **Les fichiers `pinned` (« Conserver hors ligne ») ne sont jamais évincés.**
- Les copies hors ligne (phase 4) sont dans `filesDir/offline/`, hors de ce cache, et ne sont
  jamais évincées (voir « Copies hors ligne »). Au premier passage au premier plan, les fichiers
  sans ligne (plus d'une heure) et les fichiers partiels abandonnés (plus de 24 h, hors tâches
  ouvertes) y sont supprimés.
- Une page « Stockage » affichera l'espace occupé par niveau, le plafond et un bouton de vidage
  (fichiers non épinglés). Ce cache ne contient que des copies de fichiers OneDrive : les fichiers de
  l'utilisateur ne sont jamais concernés.

---

## Configuration Microsoft Entra et paramétrage MSAL

> **Nécessaire pour activer OneDrive.** Sans cette configuration, l'application se compile et
> fonctionne normalement : les entrées OneDrive sont simplement masquées (aucun bouton inopérant).

### 1. Enregistrer l'application dans Microsoft Entra

1. Ouvrir le **centre d'administration Microsoft Entra** (<https://entra.microsoft.com>) avec un
   compte qui dispose d'un annuaire (tenant). Un compte Microsoft personnel seul peut nécessiter la
   création d'un tenant, par exemple via un compte Azure gratuit.
2. **Identité > Applications > Inscriptions d'applications > Nouvelle inscription.**
   - Nom : `CloudGallery`.
   - Types de comptes pris en charge : **« Comptes Microsoft personnels uniquement »** (ou « Comptes
     dans un annuaire organisationnel et comptes Microsoft personnels » pour accepter aussi les
     comptes professionnels).
   - Ne pas saisir d'URI de redirection à cette étape.
3. Noter l'**ID d'application (client)** dans la page Vue d'ensemble.
4. **Authentification > Ajouter une plateforme > Android.**
   - Nom du package : `fr.eva.cloudgallery`.
   - Hachage de signature : voir l'étape 2 ci-dessous.
   - Le portail génère l'URI de redirection
     `msauth://fr.eva.cloudgallery/<hachage de signature en base64, encodé URL>` (le `/`, le `+` et
     le `=` du base64 deviennent `%2F`, `%2B` et `%3D`). Le portail fournit aussi l'extrait de
     configuration MSAL correspondant.
5. **Autorisations de l'API > Ajouter une autorisation > Microsoft Graph > Autorisations
   déléguées** : `Files.Read`, `User.Read` et `offline_access`. Pour un compte personnel, le
   consentement est donné par l'utilisateur à la première connexion.
6. **Aucun secret client ni certificat** : une application mobile est un *client public*, et un
   secret embarqué dans un APK ne protège rien.

### 2. Obtenir le hachage de signature (keystore debug, Windows)

Le keystore debug se trouve dans `%USERPROFILE%\.android\debug.keystore` (alias `androiddebugkey`,
mot de passe `android`). Le hachage attendu est le **SHA-1 du certificat, encodé en base64**.

Dans **Git Bash**, qui fournit `openssl` :

```bash
keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android \
  | openssl sha1 -binary | openssl base64
```

Sans OpenSSL, en **PowerShell** :

```powershell
$sha1 = (keytool -list -v -alias androiddebugkey -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android |
  Select-String 'SHA1:').ToString().Split(' ')[-1]
[Convert]::ToBase64String(($sha1 -split ':' | ForEach-Object { [Convert]::ToByte($_, 16) }))
```

`keytool` est fourni avec le JDK d'Android Studio (`<Android Studio>\jbr\bin\keytool.exe`). Comme
l'APK release est aussi signé avec la clé debug (voir [Compilation](#compilation)), un seul hachage
suffit. Si une vraie clé de release est créée un jour, il faudra enregistrer son hachage en plus.

### 3. Configurer le projet

1. Ajouter dans **`local.properties`** (à la racine, non versionné ; ne jamais le committer) :

   ```properties
   msal.clientId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   msal.signatureHash=XXXXXXXXXXXXXXXXXXXXXXXXXXX=
   ```

   - `msal.clientId` : l'ID d'application (client) de l'étape 1 (format GUID).
   - `msal.signatureHash` : le hachage de l'étape 2, **tel quel** (base64 de 28 caractères, non
     encodé URL).
   - Les mêmes clés peuvent être données en propriétés Gradle (`~/.gradle/gradle.properties`, ou
     `-Pmsal.clientId=… -Pmsal.signatureHash=…` en ligne de commande), qui l'emportent sur
     `local.properties`. Pratique si Android Studio réécrit `local.properties`.
2. Recompiler (`./gradlew assembleDebug`). Une valeur absente ou mal formée n'empêche pas la
   compilation : Gradle affiche « MSAL non configuré (msal.clientId / msal.signatureHash) : OneDrive
   désactivé dans ce build » et l'application masque toutes les entrées OneDrive.
3. Sur le téléphone : Menu > OneDrive (ou Paramètres > Compte > OneDrive) > « Se connecter à
   OneDrive ». La première synchronisation démarre dès la connexion.

Ce que fait le build (rien à écrire à la main) :

- `BuildConfig.MSAL_CLIENT_ID` et `BuildConfig.MSAL_SIGNATURE_HASH` (`buildConfigField`), validés
  par expression régulière.
- Manifeste : `com.microsoft.identity.client.BrowserTabActivity` avec un intent-filter
  `scheme="msauth"`, `host="${applicationId}"` et `path="/<hachage>"` (**non encodé**, via le
  placeholder `msalSignatureHash` ; valeur factice `msal-not-configured` si absent).
- Configuration MSAL **générée à l'exécution** (pas de fichier `res/raw`) : au premier usage, sur le
  thread d'E/S, `noBackupFilesDir/msal_config.json` est écrit avec `client_id`, `redirect_uri`
  (forme **encodée URL** : `msauth://fr.eva.cloudgallery/<hachage encodé>`),
  `account_mode: "SINGLE"`, `authorization_user_agent: "DEFAULT"`,
  `broker_redirect_uri_registered: false` et l'autorité AAD `PersonalMicrosoftAccount`
  (`consumers`), puis passé à `PublicClientApplication.createSingleAccountPublicClientApplication`.
- Le client ID d'un client public n'est pas un secret, mais il reste propre à chaque inscription et
  n'est jamais versionné. **Aucun secret n'est nécessaire.**

---

## Compilation

Prérequis :

- **Android Studio** récent, compatible avec AGP 9.4.1. Il embarque le JDK (JetBrains Runtime)
  utilisé par Gradle.
- **JDK 17 ou plus** pour Gradle (le code est compilé en cible Java 17). Le JBR d'Android Studio
  convient.
- SDK Android avec la plateforme **API 37** (`compileSdk 37`). Le chemin du SDK est dans
  `local.properties` (`sdk.dir`).

Depuis Android Studio : *Build > Assemble Project*, ou exécuter la configuration `app` sur un
appareil connecté.

En ligne de commande, à la racine du projet :

```powershell
.\gradlew.bat assembleDebug      # APK debug
.\gradlew.bat assembleRelease    # APK release (R8 activé)
```

(`./gradlew assembleDebug` sous Git Bash.)

APK produits :

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

**Signature release** : pour un usage personnel, le build `release` est signé avec la **clé debug**
(`signingConfig = signingConfigs.getByName("debug")`). On peut donc l'installer directement, et il
reste compatible, pour les mises à jour, avec un APK debug produit sur la même machine. Une vraie
`signingConfig` sera nécessaire avant toute distribution. Le keystore debug étant propre à chaque
machine, un APK construit sur un autre PC ne pourra pas mettre à jour l'installation existante sans
désinstallation préalable, ce qui efface la base locale : favoris et albums personnalisés seraient
perdus.

---

## Installation sur Samsung Galaxy S22

L'application exige **Android 16** (`minSdk 36`), c'est-à-dire **One UI 8**.

### Avec adb (recommandé)

1. Sur le téléphone : *Paramètres > À propos du téléphone > Informations sur le logiciel*, toucher
   7 fois « Numéro de version » pour activer les options de développement, puis *Options de
   développement > Débogage USB*.
2. Brancher le téléphone, accepter l'empreinte du PC, puis :

```powershell
adb devices
adb install -r app\build\outputs\apk\release\app-release.apk
```

`-r` réinstalle en conservant les données (base, favoris, albums, réglages).

### Sans adb

Copier l'APK sur le téléphone (câble, OneDrive, e-mail…), l'ouvrir depuis *Mes fichiers* et
autoriser l'installation d'applications inconnues pour cette application source quand Android le
demande (*Paramètres > Applications > Accès spécial > Installer applis inconnues*).

### Faire de CloudGallery l'application par défaut pour les photos

- Ouvrir une photo ou une vidéo depuis une autre application (Mes fichiers, une messagerie…),
  choisir **CloudGallery** puis **Toujours**.
- Si Samsung Galerie avait déjà été choisie par défaut : *Paramètres > Applications > Galerie >
  Définir par défaut > Effacer les valeurs par défaut*, puis refaire l'étape précédente.
- L'application déclare aussi la catégorie `APP_GALLERY`, utilisée par les applications qui
  « ouvrent la galerie ». L'appareil photo Samsung peut continuer à ouvrir sa propre galerie depuis
  la vignette de capture (comportement à vérifier sur l'appareil).

### Samsung Galerie

Samsung Galerie est une application système : elle **ne peut pas être désinstallée**, seulement
**désactivée** (*Paramètres > Applications > Galerie > Désactiver*, si le bouton est proposé), ou
via `adb shell pm disable-user --user 0 com.sec.android.gallery3d` (réactivation :
`adb shell pm enable com.sec.android.gallery3d`). Cette désactivation ne supprime aucun fichier.
Avant de désactiver Samsung Galerie, vider ou restaurer sa corbeille et vérifier ses éventuelles
synchronisations (OneDrive / Samsung Cloud). Il n'est pas nécessaire de la désactiver pour utiliser
CloudGallery.

---

## Tests

### Tests unitaires JVM (`app/src/test`)

```powershell
.\gradlew.bat testDebugUnitTest
```

| Fichier | Contenu |
|---|---|
| `data/local/MediaStoreLogicTest` | Identifiants externes, dates, décision de relecture complète, empreintes, analyse des URI MediaStore / MediaDocuments, rognage, positions de lecture |
| `data/local/RemovalPlanTest` | Plan de suppression : accès partiel, masquage, frein anti-suppression massive, délai de grâce de 30 jours |
| `data/prefs/SettingsLogicTest` | Bornage du niveau de zoom, repli sur la valeur par défaut d'une énumération inconnue, « Afficher les éléments OneDrive dans Photos » et « Charger les originaux sur réseau mobile » désactivés par défaut, « Wi-Fi uniquement » activé par défaut (DataStore vide) |
| `data/repository/AlbumSortingTest` | Tri des albums |
| `ui/albums/AlbumsLogicTest` | Colonnes, masquage des dossiers vides, actions de sélection (renommer, supprimer, épingler), noms |
| `ui/grid/GridSelectionLogicTest` | Sélection (plages, jour/mois), cibles des actions sur fichiers locaux (éléments OneDrive exclus, nombres des confirmations), Partager actif pour les éléments OneDrive, entrées « Conserver hors ligne » / « Libérer » |
| `ui/offline/OfflineUiLogicTest` | Suites de « Conserver hors ligne » (message, attente du Wi-Fi, gros lot, espace insuffisant), confirmation de « Libérer » pour une dernière copie seulement, abandon si le compte des dernières copies échoue, confirmations de purge (bouton désactivé pendant la lecture, avertissement prudent si elle échoue), entrées de menu |
| `data/cloud/offline/OfflineLogicTest` | Taille à télécharger, espace manquant (marge), libération du cache d'autres applis (`allocateBytes` seulement si l'espace libre manque mais est obtenable), gros lots (200 éléments / 1 Go), réseau du travail (Wi-Fi, « Télécharger maintenant » limité à ses tâches, travail en cours qui couvre ou non), nouvelle version à retélécharger, contenu identique au moment d'enregistrer (version inconnue jamais identique), état affiché (mise à jour arrêtée signalée), suite d'un échec (nouvel essai, échec, toute la file, attente de la synchro sans essai compté, coupure après progression non comptée), arrêt du travail (pause, annulation, reprise), pause au démarrage seulement après un arrêt pendant un téléchargement, fin du délai de grâce avec copie (décision D1), noms de fichiers sûrs |
| `data/cloud/offline/OfflineJobTrackerTest` | Planification : travail en cours qui prend les tâches en charge, travail planifié ignoré s'il vient de se terminer, ancien travail remplacé qui finit après le nouveau |
| `data/cloud/content/CloudOriginalLogicTest` | Nettoyage des originaux (lignes sans fichier, plus de 7 jours, moins récemment utilisés au-delà du plafond, téléchargements en cours épargnés), nom de remplacement d'un original |
| `ui/util/ShareLogicTest` | Plan de partage (fichier local, copie hors ligne, téléchargement), plafonds 20 éléments / 500 Mo, éléments OneDrive sans compte, pourcentage de progression |
| `ui/grid/GridSeparatorsTest` | En-têtes jour/mois |
| `ui/grid/GridZoomLevelsTest` | Niveaux de zoom, colonnes, regroupement |
| `ui/video/VideoPlayerLogicTest` | Classement des erreurs de lecture (réseau, lien, échecs OneDrive trouvés dans la chaîne des causes), actions « Réessayer » / « Se connecter », position de reprise, seek borné, zones de double appui, vitesses, format d'image |
| `ui/video/PlaybackSourceResolverTest` | Priorité des sources de lecture (fichier local, copie hors ligne intacte, puis streaming OneDrive si le compte est accessible), copie tronquée ignorée, raison d'indisponibilité |
| `ui/viewer/CropMathTest` | Géométrie du rognage |
| `ui/viewer/ExifFormatTest` | Formatage des données EXIF |
| `ui/viewer/ZoomMathTest` | Calculs de zoom de la visionneuse, dimensions lues dans le fichier quand l'index n'en a pas (rechargement haute résolution possible) |
| `ui/viewer/ViewerSummaryTest` | Bande de miniatures : mise à jour du résumé (favori, version, miniature OneDrive, copie hors ligne) |
| `data/cloud/auth/CloudAuthLogicTest` | Validation du client ID et du hachage, URI de redirection encodée, configuration MSAL JSON, fraîcheur du jeton, classement des erreurs MSAL (hors ligne ≠ reconnexion) |
| `data/cloud/graph/GraphCallerTest` | Boucle de requête Graph avec transport factice : jeton uniquement vers Graph, 401, 429/503 avec Retry-After ou attente exponentielle, 410, erreurs réseau, pause globale (appliquée même après abandon, échec immédiat au-delà de 10 min), jeton renouvelé refusé signalé, en-tête Accept, budget de tentatives des miniatures |
| `data/cloud/graph/GraphRetryLogicTest` | Classement des statuts HTTP, Retry-After, attente exponentielle, URL de reprise, URL de confiance, URL delta initiale, URL des miniatures et de l'URL de téléchargement (sans `$select`), URL de téléchargement HTTPS hors Graph |
| `data/cloud/graph/GraphDtoParsingTest` | Lecture des réponses Graph (pages delta, éléments supprimés, dossiers, packages, champs inconnus, `@microsoft.graph.downloadUrl` jamais affiché par `toString`) |
| `data/cloud/stream/CloudStreamLogicTest` | Causes d'échec du streaming (Graph, MSAL, réseau), décision de nouvelle tentative (URL expirée, échec immédiat), chaîne des causes bornée, URI stable, expurgation des URL et de `tempauth` dans les journaux |
| `data/cloud/transfer/TransferLogicTest` | Durée de réutilisation d'une URL de téléchargement, statuts d'URL expirée, `Content-Range`, classement des réponses Range (206, 200, 416), plan de reprise (version inconnue jamais identique), noms de fichiers opaques et extensions, nom affiché sûr (séparateurs, caractères réservés et de contrôle, Unicode, longueur, vide), cadence de progression, même lecteur OneDrive (casse ignorée), nettoyage des dossiers de copies (fichiers enregistrés ou en cours jamais touchés, partiels après 24 h, orphelins après 1 h) |
| `data/cloud/transfer/CdnDownloaderTest` | Téléchargement avec transport factice : 200 complet, reprise 206 (version redemandée à Graph avant toute reprise, contenu changé refusé avant d'ajouter des octets), 200 après Range (reprise à zéro), fichier déjà complet sans requête, 416 inattendu, version inconnue sans reprise, URL expirée renouvelée une fois, taille incorrecte, URL non HTTPS ou Graph refusée, contenu changé, 429/503 avec Retry-After, erreurs Graph, aucun en-tête `Authorization`, `Accept-Encoding: identity`, annulation pendant la lecture du corps (appel annulé), jamais d'URL dans un message |
| `data/cloud/transfer/CloudDownloadUrlProviderTest` | Cache mémoire des URL : réutilisation, renouvellement forcé, oubli, accès refusé sans requête, purge pendant une requête (rien remis en cache), taille bornée |
| `data/net/NetworkLogicTest` | Réseau mesuré ou non, économiseur de données (appli exemptée non concernée), état inconnu traité comme facturé |
| `data/cloud/sync/OneDriveLogicTest` | Classement des éléments delta, type MIME, dates, chemins et noms d'albums OneDrive, plan de masquage/purge, intervalle au premier plan, curseur lié au lecteur, décision de démarrage (incrémental, complet, autre compte), suite d'un passage après une page (dossier restauré : énumération complète immédiate), pause Graph relue au démarrage |
| `data/cloud/thumb/CloudThumbnailLogicTest` | Paliers de taille, tailles Graph et chaîne de repli (aperçu 2048 → 1600 → `large`), clés de cache par palier d'aperçu, palier d'aperçu selon l'écran, l'image et le réseau, décision de charger l'original (automatique, à proposer, inutile), miniatures introuvables (expiration) |
| `data/mapper/CloudThumbMappingTest` | Référence de miniature OneDrive des lignes de grille et des couvertures d'album |
| `ExampleUnitTest` | Test modèle d'Android Studio |

### Tests instrumentés (`app/src/androidTest`) : appareil ou émulateur requis

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

- `data/db/MediaDaoTest` : base Room en mémoire. Ordre de pagination, projection des favoris,
  appartenance aux albums, nettoyage des orphelins, recalcul des colonnes dénormalisées, masquage
  d'une source avec conservation des favoris et albums, ré-identification, plages d'identifiants,
  protection des albums personnalisés, sources OneDrive masquées, filtre « OneDrive dans Photos ».
- `data/db/MigrationTest` : migrations 2→3, 1→3, 3→4, 2→4 et 1→4 avec `MigrationTestHelper`
  (schémas exportés ajoutés aux assets des tests instrumentés) ; en 3→4, lignes conservées,
  nouvelles colonnes nulles et `attempts` à 0.
- `data/db/CachedFileDaoTest` : remplacement d'une copie (même ligne, ancienne ligne renvoyée),
  date d'accès, suppressions, totaux, cascade, nombre d'éléments partageables, suppression
  conditionnelle du nettoyage (ligne rouverte ou remplacée gardée).
- `data/db/OfflineDaoTest` : une seule tâche ouverte par média (`enqueueIfAbsent`, nouvelle
  version, reprise d'une tâche en pause ou en échec, tâche en cours inchangée), ordre de la file,
  reprise après arrêt du processus, pause et reprise, cascade ; copie hors ligne (`hasOffline`,
  jamais `primaryUri`, visible dans Photos réglage désactivé), visibilité alignée sur l'élément
  OneDrive, dernières copies (copies masquées comprises, par sélection), éléments en attente de
  décision et revérification d'une liste confirmée, suppression des seules sources masquées, tâches
  téléchargeables (contenu changé en attente de la synchro), progression d'une tâche disparue,
  candidats à « Conserver hors ligne » (éléments déjà en téléchargement exclus, mise à jour arrêtée
  reprenable).
- `data/cloud/offline/OfflineDownloadEngineTest` : moteur avec téléchargeur factice. Enregistrement
  (copie, source, tâche retirée), purge pendant le téléchargement, libération avant
  l'enregistrement (rien de recréé), nouvelle version (remise en file), élément disparu (échec puis
  élément suivant), compte déconnecté, erreur réseau (fichier partiel gardé), tâches « en cours »
  d'un processus disparu, pause seulement après un arrêt pendant un téléchargement, arrêt puis
  nouveau passage (un seul à la fois), remplacement d'une ancienne copie (ancien fichier supprimé),
  contenu plus récent que l'index (attente de la synchro sans essai compté, file non bloquée),
  coupure réseau après progression, tâches autorisées sur le réseau (« Télécharger maintenant »),
  « Libérer » de l'élément en cours (seul cet élément s'arrête), tâche disparue pendant le
  téléchargement, espace (cache d'autres applis libéré seulement quand il le faut).
- `data/cloud/offline/OfflineRepositoryTest` : décision D1 sur la liste confirmée seulement
  (élément non confirmé, encore en délai de grâce ou revenu sur OneDrive jamais touché), « Les
  conserver » (copie visible, plus d'élément OneDrive masqué), « Libérer » d'une copie conservée
  seule (dernière copie : élément retiré avec son fichier) ou d'une copie ordinaire (élément
  OneDrive gardé), dernières copies masquées comptées.
- `data/cloud/sync/CloudSyncWriterTest` : écriture des pages delta (création, nouveau cTag,
  suppression puis purge, dossier supprimé puis restauré, dossier supprimé sans facette `folder`,
  restauration détectée pour les sous-dossiers et deltaLink effacé avec la page (passage
  interrompu), énumération complète, albums OneDrive renommés), lignes locales jamais touchées ;
  copies hors ligne masquées avec leur élément (suppression, dossier supprimé, énumération
  complète), réaffichées à la restauration, gardées au-delà du délai de grâce (décision D1), un seul
  nouveau téléchargement par nouvelle version (original temporaire périmé retiré), copie conservée
  rattachée quand l'élément revient.
- `data/cloud/CloudDataPurgerTest` : retrait complet des données OneDrive (y compris sources
  masquées, couvertures, miniatures, lignes et fichiers des originaux téléchargés, copies hors ligne,
  file de téléchargement, numéro de vidage incrémenté), données locales intactes.
- `ExampleInstrumentedTest` : test modèle.

L'indexeur MediaStore, l'UI Compose, le lecteur vidéo, MSAL et les appels réels à Microsoft Graph
n'ont pas de tests automatisés : ils se vérifient sur l'appareil.

### Vérifications sur l'appareil : OneDrive (phase 3)

À faire sur le Galaxy S22 avec un build configuré (voir
[Configuration Microsoft Entra](#configuration-microsoft-entra-et-paramétrage-msal)) :

| Vérification | Résultat attendu |
|---|---|
| Build sans `msal.clientId` / `msal.signatureHash` | Compilation réussie, aucune entrée OneDrive dans Menu ni Paramètres |
| Connexion (Menu > OneDrive > Se connecter) | Onglet de navigateur Microsoft, retour dans l'application, nom du compte affiché, synchro lancée |
| Arrêt forcé puis relance | Compte toujours connecté, synchro au premier plan sans nouvelle connexion |
| Première synchro | Albums OneDrive créés (un par dossier contenant des médias), miniatures Graph dans les albums |
| Réglage « Afficher les éléments OneDrive dans Photos » | Désactivé : Photos et Vidéos sans éléments uniquement OneDrive (nombres compris) ; activé : éléments mélangés |
| Second passage au premier plan avant 5 min | Aucune requête ; après 5 min, un seul appel delta |
| Photo et dossier supprimés sur OneDrive (web), puis « Synchroniser maintenant » | Éléments masqués ; `deleted`, `root`, `parentReference.id`, `file.hashes.quickXorHash` et `photo` bien reçus avec le `$select` (sinon modifier `DELTA_SELECT` et `DELTA_SELECT_VERSION`) |
| Dossier restauré depuis la corbeille OneDrive | Énumération complète automatique, contenu à nouveau visible |
| Renommage / déplacement d'un dossier sur OneDrive | Album renommé, chemin mis à jour |
| Tailles de miniatures `c{n}x{n}_crop` | Miniatures nettes ; si Graph les refuse, repli automatique sur les tailles standard |
| Mode avion après un premier affichage | Miniatures déjà vues toujours affichées (cache disque) ; aperçu non vu : message explicatif |
| Visionneuse sur un élément uniquement OneDrive | Aperçu avec mention, bouton de lecture et badge de durée pour une vidéo, pas de rotation, rognage, copie ni suppression (partage et « Ouvrir avec » : voir phase 4) |
| Sélection uniquement OneDrive (album OneDrive) | Supprimer et Copier désactivés ; Partager (phase 4), Favori et Ajouter à l'album actifs |
| Sélection mixte, Supprimer | Titre avec le nombre de fichiers locaux, mention des éléments OneDrive non concernés ; seuls les fichiers locaux vont à la corbeille |
| Connexion avec un autre compte Microsoft | Carte « Autre compte OneDrive », rien de purgé avant confirmation |
| Déconnexion (confirmée) | Éléments et albums OneDrive retirés, miniatures OneDrive vidées (`cache/coil`), médias locaux intacts |
| Session révoquée (mot de passe changé) | « Reconnexion requise », données conservées, reconnexion possible |
| Déconnexion pendant le chargement de miniatures (réseau lent) | Compte toujours « Non connecté » ensuite, aucune synchro relancée |
| Dossier contenant un sous-dossier de photos supprimé puis restauré (corbeille OneDrive) | Énumération complète, photos du sous-dossier à nouveau visibles |
| Logcat (`adb logcat`) | Aucun jeton, deltaLink ni nom de compte |
| `adb shell run-as fr.eva.cloudgallery ls -R shared_prefs files app_fr.eva.cloudgallery` | Noms des fichiers MSAL (dont `adalks`) conformes aux exclusions de sauvegarde |

### Vérifications sur l'appareil : streaming OneDrive (phase 4)

| Vérification | Résultat attendu |
|---|---|
| Lecture d'une vidéo uniquement OneDrive | Lecture en streaming ; en cas d'échec, message d'erreur du lecteur (réseau, lien, connexion requise…) |
| Seek dans une longue vidéo | Reprise rapide (réponse 206 de l'hôte de téléchargement aux requêtes Range) |
| Pause de plus de deux minutes puis seek | Nouvelle URL obtenue sans erreur ; noter le statut renvoyé pour une URL expirée |
| Mode avion pendant la lecture | Message « Connexion impossible », « Réessayer » relance à la même position |
| Déconnexion OneDrive puis lecture | Message de connexion et bouton « Se connecter » ; retour au lecteur après connexion : lecture |
| Vidéo supprimée sur OneDrive (avant la synchro) | « Introuvable sur OneDrive », pas de bouton « Réessayer » |
| Logcat pendant la lecture et après une erreur | Aucune URL `files.1drv.com` ni `tempauth` (URL remplacées par `<url>`) |

### Vérifications sur l'appareil : aperçus, originaux et partage OneDrive (phase 4)

| Vérification | Résultat attendu |
|---|---|
| Image uniquement OneDrive en Wi-Fi | Aperçu net (palier 2048) ; si Graph refuse `c2048x2048`, repli `c1600x1600` sans erreur visible |
| Zoom au-delà de 2,5x en Wi-Fi | Pastille de progression, puis original affiché sans saut de cadrage |
| Même zoom en données mobiles (ou économiseur de données) | Pastille « Charger l'original (x Mo) » ; rien n'est téléchargé avant l'appui ; réglage activé : chargement automatique |
| Original de 50 Mpx (Galaxy S22) depuis OneDrive | Affichage sans plantage (décodage réduit à ~24 Mpx au plus) |
| Annulation pendant le téléchargement (pastille) | Retour à l'aperçu ; nouvel essai possible |
| Partager une image OneDrive vers Gmail ou Messages | Boîte de progression, puis pièce jointe avec le nom OneDrive (et non le nom interne) |
| « Ouvrir avec » sur une image ou une vidéo OneDrive | CloudGallery absente du choix ; l'appli choisie ouvre le fichier |
| Sélection de 21 éléments OneDrive, Partager | Message « 20 éléments OneDrive au maximum » |
| Vidéo OneDrive de plus de 500 Mo, Partager | Message « 500 Mo au maximum », aucun téléchargement |
| Déconnexion OneDrive après quelques originaux | `adb shell run-as fr.eva.cloudgallery ls cache/onedrive/originals` vide |
| Huit jours sans ouvrir un original, puis relance de l'appli | Original supprimé du dossier (nettoyage au premier plan) |
| Logcat pendant les téléchargements | Aucune URL `files.1drv.com` ni `tempauth` |
| `MigrationTest` 3→4 et `CachedFileDaoTest` (`connectedDebugAndroidTest`) | Réussis sur l'appareil |

### Vérifications sur l'appareil : copies hors ligne (phase 4)

| Vérification | Résultat attendu |
|---|---|
| Premier « Conserver hors ligne » | Demande d'autorisation des notifications (une seule fois) ; refusée, le téléchargement a lieu quand même |
| « Conserver hors ligne » en Wi-Fi | Notification de progression avec « Annuler », badge « hors ligne » dans la grille, élément visible dans Photos réglage OneDrive désactivé |
| Même action en données mobiles (Wi-Fi uniquement activé) | Boîte « En attente du Wi-Fi » ; « Télécharger maintenant » lance le téléchargement en mobile |
| Wi-Fi coupé pendant un téléchargement (Wi-Fi uniquement) | Arrêt, puis reprise automatique au retour du Wi-Fi (requête Range, fichier partiel conservé), jamais en mobile |
| VPN actif | Le téléchargement démarre et aboutit |
| Mode avion après téléchargement | Image et vidéo affichées et lues depuis la copie ; partage et « Ouvrir avec » sans réseau |
| Arrêt depuis le Gestionnaire de tâches pendant un téléchargement | File « en pause » (écran Compte), aucune reprise automatique ; « Reprendre » relance |
| Bouton « Annuler » de la notification | File vidée, copies déjà présentes conservées, aucun fichier `.part` restant |
| Redémarrage du téléphone avec une file en attente | Reprise à la prochaine ouverture de l'appli |
| Modification de la photo sur OneDrive, synchro | Nouveau téléchargement ; l'ancienne copie reste affichée jusqu'au remplacement |
| Sélection de plus de 200 éléments (ou plus de 1 Go) | Confirmation avec la taille totale et l'espace disponible |
| Espace insuffisant | Boîte « Espace insuffisant » et écran système « Libérer de l'espace » |
| « Libérer l'espace » | Copie supprimée sans confirmation, badge retiré ; élément toujours sur OneDrive |
| Élément conservé supprimé sur OneDrive | Masqué ; après 30 jours (ou horloge avancée), carte de l'écran Compte et pastille du Menu ; « Les conserver » le rend visible (« supprimé de OneDrive »), « Libérer » demande alors confirmation |
| Déconnexion avec des copies hors ligne | La confirmation annonce le nombre de copies supprimées (et les dernières copies) ; `adb shell run-as fr.eva.cloudgallery ls files/offline` vide |
| Économiseur de données activé, « Télécharger maintenant » en mobile | Téléchargement autorisé (tâche lancée par l'utilisateur) |
| Restrictions One UI « appli en veille » | Le téléchargement lancé au premier plan aboutit (noter le comportement) |
| `adb shell dumpsys jobscheduler fr.eva.cloudgallery` | Tâche `offline` 4001 « user-initiated », réseau non facturé (ou quelconque après « Télécharger maintenant ») |
| Logcat pendant les téléchargements | Aucune URL `files.1drv.com` ni `tempauth`, aucun nom de fichier |
| `connectedDebugAndroidTest` | `OfflineDaoTest`, `OfflineDownloadEngineTest`, `OfflineRepositoryTest`, `CloudSyncWriterTest`, `CloudDataPurgerTest` réussis |

---

## Limitations connues

- **OneDrive en lecture seule** : un élément présent uniquement sur OneDrive peut être affiché
  (aperçu, original au zoom profond), lu en streaming, partagé ou ouvert dans une autre application
  (original téléchargé, 20 éléments / 500 Mo par partage, compte connecté requis), mais ni modifié,
  ni supprimé, ni copié vers un dossier du téléphone, même conservé hors ligne. Pas encore de page
  Stockage (phase 5). Les informations EXIF d'un original téléchargé ou d'une copie hors ligne ne
  sont pas lues (panneau Détails limité aux métadonnées OneDrive).
- **Copies hors ligne** : téléchargées uniquement quand l'application les lance au premier plan
  (tâche lancée par l'utilisateur) ; une nouvelle version détectée par la synchro attend le
  prochain passage au premier plan. Après un redémarrage du téléphone, la file reprend à la
  prochaine ouverture. Un élément conservé hors ligne qui existe aussi sur le téléphone apparaît
  deux fois dans Photos (jusqu'au dédoublonnage de la phase 6). L'appartenance aux albums OneDrive
  d'une copie conservée après suppression de OneDrive est perdue (favoris et albums personnels
  gardés). L'image d'aperçu d'une copie de vidéo vient du fichier (aucune miniature OneDrive) ;
  les couvertures d'album n'utilisent pas encore les copies hors ligne.
- **Comptes Microsoft personnels uniquement**, un seul compte à la fois. Le contenu du Coffre-fort
  personnel (Personal Vault) n'est pas pris en charge (comportement de la requête delta non
  vérifié) ; les éléments partagés par d'autres personnes sont ignorés.
- **Doublons** : un média présent sur le téléphone et sur OneDrive est deux médias distincts. Le
  réglage « Afficher les éléments OneDrive dans Photos » est désactivé par défaut pour éviter les
  doublons dans Photos et Vidéos ; ils restent possibles dans les Favoris et les albums
  personnalisés. Fusion prévue en phase 6 (sur empreinte de contenu, jamais sur le nom).
- **OneDrive sans permission médias** : sans accès aux photos du téléphone, l'application affiche
  l'écran d'autorisation et rien d'autre ; un usage « OneDrive seul » est reporté en phase 5.
- **Dates OneDrive** : `photo.takenDateTime` peut être l'heure locale EXIF présentée comme UTC ; un
  élément OneDrive peut alors être classé quelques heures à côté de sa copie locale.
- **Première synchro d'une grande bibliothèque** : si l'application est tuée pendant la première
  énumération complète, celle-ci recommence depuis le début au passage suivant (écritures
  idempotentes, sans perte).
- **Pas de synchro en arrière-plan** : l'index local et OneDrive ne sont mis à jour que lorsque
  l'application est au premier plan (OneDrive : au plus toutes les 5 minutes, ou « Synchroniser
  maintenant »). WorkManager est déclaré mais pas utilisé.
- **Favoris et albums personnalisés propres à l'application** : ils ne sont pas lus depuis Samsung
  Galerie ni écrits dans le champ `IS_FAVORITE` de MediaStore, et la base est exclue des sauvegardes.
  Une désinstallation ou un changement d'appareil les perd.
- **Pas de recherche** (ni par nom, ni par lieu, ni par contenu).
- **Pas de dédoublonnage** : `contentHash` n'est renseigné que pour OneDrive (`qxh:<quickXorHash>`) ;
  l'empreinte des fichiers locaux n'est pas calculée.
- **Cache des miniatures OneDrive** : borné à 256 Mo et géré par Coil (éviction LRU), sans réglage
  ni page Stockage. Les tailles personnalisées de miniatures sont documentées par Microsoft mais pas
  encore vérifiées sur l'appareil (repli automatique sur les tailles standard).
- **Très grandes images** : la visionneuse plafonne vers 24 MP. Il n'y a pas de décodage par tuiles,
  donc pas de pleine résolution pour les panoramas ou les photos de 50 MP et plus.
- **GIF non animés** (pas de `coil-gif`). Pas de support spécifique des photos animées / Motion
  Photos Samsung.
- **Pas d'édition vidéo** (découpe). L'édition d'image se limite à la rotation sans perte
  (JPEG/WebP) et au rognage en copie.
- **URI du sélecteur de photos** (`content://media/picker/…`) et URI FileProvider tierces : elles
  s'affichent en lecture seule, sans être rattachées à l'index (pas de favori, pas d'album).
- **Accès partiel** : seuls les éléments sélectionnés sont visibles, et les albums-dossiers ne sont
  pas nettoyés tant que l'accès reste partiel.
- **Corbeille** : celle utilisée est la corbeille Android (MediaStore, 30 jours). Celle de Samsung
  Galerie n'est ni lue ni gérée, et il n'y a pas encore d'écran « Corbeille » dans l'application.
- **Fidélité visuelle One UI** approximative tant que les captures de référence Samsung n'ont pas
  été comparées (voir `TODO.md`). Pas de transitions à élément partagé grille → visionneuse.
- **Signature** : release signé avec la clé debug, ce qui ne convient pas à une distribution.
