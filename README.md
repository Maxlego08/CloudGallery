# CloudGallery

Galerie photo/vidéo Android pour usage personnel. Elle reprend l'ergonomie de Samsung Galerie
(One UI 8) et doit, à terme, réunir dans une même bibliothèque les médias de l'appareil et ceux de
OneDrive.

> **État actuel : phases 1 et 2 (galerie locale).** L'application indexe les médias de l'appareil
> (MediaStore), les affiche, les organise et les lit. **OneDrive, le cache distant, les
> téléchargements hors ligne et la synchronisation en arrière-plan ne sont pas implémentés.** Les
> sections qui en parlent décrivent la conception prévue et portent la mention
> « Prévu (phases 3-5), non implémenté ».

- Package : `fr.eva.cloudgallery`, version `0.2.0` (versionCode 1)
- `minSdk 36` (Android 16), `targetSdk 37`, `compileSdk 37`
- Appareil cible : Samsung Galaxy S22 sous One UI 8 / Android 16, bibliothèque de 10 000 à 100 000 médias

---

## Sommaire

1. [Présentation](#présentation)
2. [Architecture](#architecture)
3. [Dépendances](#dépendances)
4. [Permissions Android](#permissions-android)
5. [Indexation locale](#indexation-locale)
6. [Synchronisation OneDrive et gestion du cache (prévu)](#synchronisation-onedrive-et-gestion-du-cache)
7. [Configuration Microsoft Entra et paramétrage MSAL (phase 3)](#configuration-microsoft-entra-et-paramétrage-msal)
8. [Compilation](#compilation)
9. [Installation sur Samsung Galaxy S22](#installation-sur-samsung-galaxy-s22)
10. [Tests](#tests)
11. [Limitations connues](#limitations-connues)

---

## Présentation

Fonctions disponibles (phases 1 et 2, médias locaux uniquement) :

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
- **Paramètres** : thème (système / clair / sombre), affichage de la durée des vidéos, lecture
  automatique, corbeille Android, confirmation avant suppression, état de l'indexation, réindexation
  complète.
- Interface en **français** (par défaut) et en **anglais** (`values-en`).

**Sécurité des fichiers.** L'application ne supprime ni ne modifie aucun fichier sans confirmation
système. La suppression passe toujours par `MediaStore.createTrashRequest` (corbeille Android,
récupérable 30 jours, option par défaut) ou par `MediaStore.createDeleteRequest`, et Android affiche
alors sa propre boîte de confirmation. La rotation demande l'autorisation d'écriture avec
`MediaStore.createWriteRequest`. L'indexeur ne modifie que la base Room, jamais les fichiers.

---

## Architecture

### Couches

```
fr.eva.cloudgallery
├── ui/          Compose + ViewModels (Hilt), navigation typée (kotlinx.serialization)
│   ├── main/        écran à onglets, MediaSyncCoordinator (déclenchement de l'indexation)
│   ├── photos/      onglet Photos          ├── albums/   onglet Albums, grilles d'album
│   ├── grid/        grille paginée, zoom, sélection, séparateurs jour/mois
│   ├── viewer/      visionneuse, zoom, EXIF, rognage, filmstrip, image externe
│   ├── video/       lecteur Media3 et ses commandes
│   ├── menu/ settings/ permissions/ navigation/ components/ (composants One UI) theme/ image/ util/
├── domain/      modèles (Media, Album, MediaContext, Settings) + interfaces de dépôts
│   └── repository/Repositories.kt   MediaRepository, AlbumRepository, FavoritesRepository,
│                                    LocalMediaIndexer, LocalMediaWriter, SettingsRepository,
│                                    PlaybackPositionRepository
├── data/
│   ├── db/          Room : AppDatabase, entités, DAO
│   ├── local/       MediaStoreIndexer (MediaStore -> Room), MediaStoreWriter (écritures avec
│   │                confirmation), MediaStoreLogic (logique pure, testée en JVM)
│   ├── repository/  implémentations des dépôts
│   ├── prefs/       SettingsRepositoryImpl (DataStore Preferences)
│   └── mapper/      entités -> modèles du domaine
└── di/          modules Hilt
```

La couche `ui` ne dépend que des interfaces de `domain`. Les implémentations `data` sont liées par
Hilt.

### Modules Hilt (`di/`)

| Module | Rôle |
|---|---|
| `CoroutinesModule` | Qualificateurs `@IoDispatcher` (`Dispatchers.IO`), `@DefaultDispatcher` et `@ApplicationScope` (`CoroutineScope(SupervisorJob() + Default)`) pour le travail qui doit survivre aux écrans |
| `DatabaseModule` | `AppDatabase` (singleton, fichier `cloudgallery.db`, migration 1→2) et les 9 DAO |
| `DataStoreModule` | Instance unique de `DataStore<Preferences>` (fichier `settings`) |
| `RepositoryModule` | `@Binds` des interfaces du domaine vers `MediaRepositoryImpl`, `AlbumRepositoryImpl`, `FavoritesRepositoryImpl`, `MediaStoreIndexer`, `MediaStoreWriter`, `SettingsRepositoryImpl` et `PlaybackPositionRepositoryImpl` |

`CloudGalleryApp` (`@HiltAndroidApp`) fournit aussi l'`ImageLoader` Coil unique
(`SingletonImageLoader.Factory`) : cache mémoire d'environ 25 % de la mémoire de l'application, cache
disque de 256 Mo, miniatures MediaStore chargées par `ContentResolver.loadThumbnail` (fetcher
`LocalThumbnailFetcher`), images de vidéo extraites par `VideoFrameDecoder`.

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

L'UI ne lit jamais MediaStore directement. Elle observe Room, qui invalide automatiquement les
`PagingSource` à chaque écriture de l'indexeur. `maxSize = 1800` limite la mémoire après un long
défilement dans 100 000 médias. Les placeholders sont désactivés parce que les en-têtes jour/mois
sont insérés entre les éléments chargés.

### Schéma Room (version 2, schémas exportés dans `app/schemas/`)

Un **média logique** (`media`) peut avoir **plusieurs sources** (`media_source`) : le fichier local
MediaStore (`LOCAL`), l'élément OneDrive (`ONEDRIVE`, prévu) et une copie hors ligne
(`OFFLINE_COPY`, prévu). Favoris, albums et positions de lecture pointent vers le média logique, ce
qui leur permet de survivre à la disparition ou au remplacement d'une des sources. Les colonnes
`hasLocal`, `hasCloud`, `hasOffline` et `primaryUri` de `media` sont dénormalisées depuis
`media_source` (`MediaDao.refreshSourceFlags`), ce qui évite toute jointure à la grille.

| Table | Rôle |
|---|---|
| `media` | Média logique : type, nom, MIME, `dateTaken` (EXIF/MediaStore, à défaut date d'ajout puis de modification), dimensions, durée, taille, orientation, `primaryUri`, indicateurs de sources, `contentHash` (pour le futur dédoublonnage). Index `(dateTaken, id)`, `(mediaType, dateTaken)`, `displayName`, `contentHash` |
| `media_source` | Une source physique d'un média. `kind` + `externalId` unique (`image:123` / `video:45` pour MediaStore, car les `_ID` images et vidéos peuvent se chevaucher), `uri`, `bucketKey` (`local:<BUCKET_ID>`, plus tard `onedrive:<parentId>`), chemin relatif, `generation` (GENERATION_MODIFIED), `eTag`/`cTag`/`sha1Hash`/`quickXorHash` (réservés à OneDrive), `volumeName`, `missingSince` (source masquée mais conservée) |
| `album` | Album-dossier (`LOCAL_FOLDER`, `sourceKey` = bucketKey) ou album virtuel (`sourceKey` null). Nom personnalisé, épingle, ordre, couverture, masquage. Un dossier personnalisé n'est jamais supprimé automatiquement |
| `album_media` | Appartenance d'un média à un album virtuel (références, pas de copie) |
| `favorite` | Favoris propres à l'application (`mediaId`, date d'ajout) |
| `cached_file` | **Prévu** : fichiers en cache par média et par niveau (`THUMBNAIL`, `PREVIEW`, `ORIGINAL`, `OFFLINE`), date de dernier accès pour le LRU, `pinned` = conservé hors ligne (jamais évincé) |
| `download_task` | **Prévu** : file de téléchargements (`QUEUED`, `RUNNING`, `DONE`, `FAILED`, `CANCELLED`), octets reçus/total, `keepOffline` |
| `sync_cursor` | Curseur par fournisseur : `mediastore` (génération + `MediaStore.getVersion`) et plus tard `onedrive` (deltaLink Graph). Dates de dernière synchro et de dernière synchro complète, dernière erreur |
| `user_settings` | Table clé/valeur réservée. Les réglages actuels sont stockés dans DataStore |
| `playback_position` | Position de reprise vidéo (enregistrée seulement entre 3 s après le début et 3 s avant la fin) |

Les clés étrangères vers `media` sont en `ON DELETE CASCADE`. La base est **exclue des sauvegardes
Android** (`backup_rules.xml`, `data_extraction_rules.xml`) : elle référence des `_ID` MediaStore
propres à l'appareil. Seules les préférences sont sauvegardées.

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
| kotlinx-coroutines (android, test) | 1.11.0 |
| kotlinx-serialization-json | 1.11.0 |
| JUnit | 4.13.2 |
| AndroidX Test ext JUnit | 1.3.0 |
| Espresso Core | 3.7.0 |

Le plugin `org.gradle.toolchains.foojay-resolver-convention` 1.0.0 est déclaré dans
`settings.gradle.kts`. Aucune bibliothèque réseau ni MSAL n'est encore intégrée.

---

## Permissions Android

Déclarées dans `AndroidManifest.xml` :

| Permission | Usage |
|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | Lecture de toute la bibliothèque (accès complet) |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Accès partiel « Sélectionner des photos » (Android 14 et plus) |
| `ACCESS_MEDIA_LOCATION` | Coordonnées GPS non masquées dans les EXIF (`MediaStore.setRequireOriginal`) |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Réservées à OneDrive (phase 3 et suivantes). **Aucun accès réseau n'est fait aujourd'hui** |

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

> **Prévu (phases 3-5), non implémenté.** Aucune de ces fonctions n'existe dans le code actuel. Le
> schéma Room (`media_source` de type `ONEDRIVE`/`OFFLINE_COPY`, `cached_file`, `download_task`,
> `sync_cursor`) et l'interface (badge « Album OneDrive », page « Disponible uniquement sur
> OneDrive ») sont déjà préparés. Le Menu et les Paramètres affichent « Disponible dans une
> prochaine version ».

### Synchronisation (phase 3)

- **Authentification** : MSAL Android en client public (aucun secret), compte Microsoft personnel,
  scopes `Files.Read`, `User.Read` et `offline_access`. Les jetons sont gérés par MSAL
  (`acquireTokenSilent`, puis interaction si nécessaire).
- **Inventaire** : Microsoft Graph `GET /me/drive/root/delta` (ou un dossier choisi), avec `$select`
  limité aux champs utiles (id, name, size, file.mimeType, file.hashes, photo, video, image,
  parentReference, eTag, cTag, deleted). Les pages `@odata.nextLink` sont suivies jusqu'au
  `@odata.deltaLink`, qui est enregistré dans `sync_cursor` (`provider = "onedrive"`). Les synchros
  suivantes repartent de ce deltaLink. Si Graph répond `410 Gone` (jeton de delta expiré), une
  resynchronisation complète est faite, en appliquant les mêmes règles de masquage que pour
  MediaStore.
- **Écriture** : chaque élément OneDrive devient une `media_source` de type `ONEDRIVE`
  (`externalId` = id d'élément, `bucketKey` = `onedrive:<parentId>`, `cTag`/`eTag`, `quickXorHash`
  et `sha1Hash` quand Graph les fournit), rattachée à un média logique.
- **Limitation de débit** : en cas de `429 Too Many Requests` ou `503`, l'en-tête `Retry-After` est
  respecté, avec un backoff exponentiel et un plafond. La file de requêtes est sérialisée par compte.
- **Planification** (phase 5) : WorkManager périodique (contrainte réseau, éventuellement Wi-Fi
  seulement) en plus de la synchro au premier plan.

### Niveaux de qualité (phase 4)

| Niveau | Source | Usage |
|---|---|---|
| Miniature | `/items/{id}/thumbnails` (small/medium) | Grille, couvertures d'album |
| Aperçu | miniature `large` ou taille personnalisée, adaptée à l'écran | Visionneuse sans zoom |
| Original | `@microsoft.graph.downloadUrl` (URL temporaire, sans en-tête d'authentification, compatible HTTP Range) | Zoom profond, partage, lecture vidéo en streaming |

Pour la vidéo, ExoPlayer lira l'URL temporaire avec `DefaultHttpDataSource` (requêtes Range pour le
seek) derrière un `ResolvingDataSource` qui renouvelle l'URL à son expiration. Une copie hors ligne
sera lue en priorité si elle existe (voir `ui/video/PlaybackSource.kt`).

### Cache (phase 4-5)

- Les fichiers sont recensés dans `cached_file` (un par média et par niveau), avec `lastAccessAt`.
- **Éviction LRU** sous un plafond configurable : les fichiers les moins récemment consultés sont
  supprimés en premier. **Les fichiers `pinned` (« Conserver hors ligne ») ne sont jamais évincés.**
- Les téléchargements hors ligne passent par `download_task` (reprise, progression, annulation). Une
  copie terminée devient une source `OFFLINE_COPY`.
- Une page « Stockage » affichera l'espace occupé par niveau, le plafond et un bouton de vidage
  (fichiers non épinglés). Ce cache ne contient que des copies de fichiers OneDrive : les fichiers de
  l'utilisateur ne sont jamais concernés.

---

## Configuration Microsoft Entra et paramétrage MSAL

> **À faire en phase 3.** Rien de tout cela n'est encore nécessaire pour compiler ou utiliser
> l'application.

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

### 3. Intégration prévue dans le projet

- Dépendance `com.microsoft.identity.client:msal` : prendre la dernière version stable au moment de
  la phase 3 et vérifier dans son README si un dépôt Maven supplémentaire est nécessaire.
- **Client ID hors du dépôt** : l'ajouter dans `local.properties` (non versionné), par exemple
  `msal.clientId=xxxxxxxx-xxxx-…` et `msal.signatureHash=…`, puis l'exposer via
  `buildConfigField` (le `BuildConfig` est déjà activé dans `app/build.gradle.kts`) et des
  placeholders de manifeste. Le client ID d'un client public n'est pas un secret, mais il reste
  propre à chaque inscription et n'a pas à être versionné. **Ne jamais committer de secret.**
- Fichier de configuration MSAL (`res/raw/auth_config_single_account.json`, généré à la
  compilation ou rempli depuis `BuildConfig`) : `client_id`, `redirect_uri` (forme **encodée URL**),
  `account_mode: "SINGLE"`, `authorization_user_agent: "DEFAULT"`, autorité AAD avec l'audience
  `PersonalMicrosoftAccount`.
- Manifeste : `com.microsoft.identity.client.BrowserTabActivity` avec un intent-filter
  `scheme="msauth"`, `host="fr.eva.cloudgallery"` et `path="/<hachage>"` (**non encodé** dans le
  manifeste).

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
| `data/prefs/SettingsLogicTest` | Bornage du niveau de zoom, repli sur la valeur par défaut d'une énumération inconnue |
| `data/repository/AlbumSortingTest` | Tri des albums |
| `ui/albums/AlbumsLogicTest` | Colonnes, masquage des dossiers vides, actions de sélection (renommer, supprimer, épingler), noms |
| `ui/grid/GridSelectionLogicTest` | Sélection (plages, jour/mois) |
| `ui/grid/GridSeparatorsTest` | En-têtes jour/mois |
| `ui/grid/GridZoomLevelsTest` | Niveaux de zoom, colonnes, regroupement |
| `ui/video/VideoPlayerLogicTest` | Classement des erreurs de lecture, position de reprise, seek borné, zones de double appui, vitesses, format d'image, média cloud sans source |
| `ui/viewer/CropMathTest` | Géométrie du rognage |
| `ui/viewer/ExifFormatTest` | Formatage des données EXIF |
| `ui/viewer/ZoomMathTest` | Calculs de zoom de la visionneuse |
| `ExampleUnitTest` | Test modèle d'Android Studio |

### Tests instrumentés (`app/src/androidTest`) : appareil ou émulateur requis

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

- `data/db/MediaDaoTest` : base Room en mémoire. Ordre de pagination, projection des favoris,
  appartenance aux albums, nettoyage des orphelins, recalcul des colonnes dénormalisées, masquage
  d'une source avec conservation des favoris et albums, ré-identification, plages d'identifiants,
  protection des albums personnalisés.
- `ExampleInstrumentedTest` : test modèle.

L'indexeur MediaStore, l'UI Compose et le lecteur vidéo n'ont pas de tests automatisés : ils se
vérifient sur l'appareil.

---

## Limitations connues

- **Pas de OneDrive** : connexion, synchronisation, streaming, cache et hors ligne ne sont pas
  implémentés (phases 3 à 5). Les permissions `INTERNET` / `ACCESS_NETWORK_STATE` sont déclarées
  mais inutilisées.
- **Pas de synchro en arrière-plan** : l'index n'est mis à jour que lorsque l'application est au
  premier plan (WorkManager est déclaré mais pas utilisé).
- **Favoris et albums personnalisés propres à l'application** : ils ne sont pas lus depuis Samsung
  Galerie ni écrits dans le champ `IS_FAVORITE` de MediaStore, et la base est exclue des sauvegardes.
  Une désinstallation ou un changement d'appareil les perd.
- **Pas de recherche** (ni par nom, ni par lieu, ni par contenu).
- **Pas de dédoublonnage** : la colonne `contentHash` existe mais n'est pas renseignée.
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
