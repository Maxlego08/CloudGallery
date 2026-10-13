# CloudGallery

Galerie photo et vidéo pour Android, inspirée de Samsung Galerie (One UI 8), qui regroupe dans une
même bibliothèque les photos du téléphone et celles de **OneDrive**.

[![Release](https://img.shields.io/github/v/release/Maxlego08/CloudGallery)](https://github.com/Maxlego08/CloudGallery/releases/latest)
![Android 16+](https://img.shields.io/badge/Android-16%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
[![Licence MIT](https://img.shields.io/badge/licence-MIT-blue)](LICENSE)

> Projet personnel, conçu et testé sur Samsung Galaxy sous One UI 8. Il n'est affilié ni à Samsung
> ni à Microsoft.

---

## Fonctionnalités

### Bibliothèque

- **Photos du téléphone et de OneDrive réunies** dans une seule grille, triée par date de prise de
  vue. Un élément présent uniquement sur OneDrive porte un badge nuage.
- **Grandes bibliothèques** : défilement fluide sur plusieurs dizaines de milliers d'éléments, et
  défilement rapide par date avec une poignée et une bulle de date.
- **Zoom au pincement** sur la grille (2 à 9 colonnes), avec regroupement par jour ou par mois.
- **Doublons fusionnés** : une photo sauvegardée sur OneDrive et toujours présente sur le
  téléphone n'apparaît qu'une fois, après comparaison de l'empreinte des fichiers.
- **Recherche** par nom de fichier, dossier, album, date ou type.

### Albums

- Un album par dossier du téléphone, un album par dossier OneDrive, et des **albums
  personnalisés** qui ne copient aucun fichier.
- Tri, épinglage et renommage, ainsi que les collections Favoris et Vidéos.
- **Masquer des albums** : chaque album peut être affiché, masqué de l'onglet Albums, ou masqué
  aussi de Photos.
- **Ouvrir dans OneDrive** : depuis un album OneDrive, ouvre le dossier dans OneDrive sur le web.

### Visionneuse et vidéos

- **Transitions façon One UI** : la photo s'agrandit depuis sa vignette et y revient à la
  fermeture. Les courbes ont été mesurées sur Samsung Galerie.
- Zoom au pincement ou au double appui, bande de miniatures et détails EXIF (appareil, réglages,
  position GPS).
- Rotation sans perte. Le rognage enregistre une copie.
- **Lecteur vidéo** (Media3) : ±10 s, vitesse, son, reprise à la dernière position et lecture en
  streaming des vidéos OneDrive.

### OneDrive

- Connexion avec un **compte Microsoft personnel**, en **lecture seule** : l'application ne crée,
  ne modifie et ne supprime jamais rien sur OneDrive.
- Aperçus adaptés au réseau, et original téléchargé au zoom profond.
- Partage et « Ouvrir avec » pour les éléments OneDrive.
- **Copies hors ligne** (« Conserver hors ligne ») et **mise en cache de tout OneDrive**
  (vignettes et aperçus) pour naviguer sans connexion.
- **Page Stockage** : espace utilisé, plafonds du cache et vidage du cache.
- **Économiseur d'espace** : propose de mettre à la corbeille les photos du téléphone déjà
  sauvegardées à l'identique sur OneDrive.

### Et aussi

- Mode sélection avec glissement, et sélection d'un jour ou d'un mois entier. Actions disponibles :
  favori, partage, ajout à un album, copie et suppression.
- **Déplacer vers le Dossier sécurisé** Samsung.
- Animation de chargement des vignettes.
- Thèmes clair, sombre et système. Interface en français et en anglais.
- Peut servir d'application par défaut pour ouvrir les photos et vidéos.

### Sécurité des fichiers

Toute suppression passe par la **corbeille Android** (récupérable pendant 30 jours) ou par la boîte
de confirmation du système. L'indexation ne fait que lire les fichiers du téléphone.

---

## Installation

**Prérequis : Android 16 (One UI 8) ou plus récent.**

1. Téléchargez `CloudGallery-v*.apk` depuis la
   [dernière release](https://github.com/Maxlego08/CloudGallery/releases/latest).
2. Ouvrez le fichier sur le téléphone. Si Android le demande, autorisez l'installation
   d'applications depuis votre navigateur ou votre gestionnaire de fichiers.
3. Au premier lancement, accordez l'accès aux photos et vidéos.
4. Pour activer OneDrive : *Menu > OneDrive > Se connecter*.

Avec adb : `adb install -r CloudGallery-v1.0.0.apk`.

Pour ouvrir les photos avec CloudGallery par défaut, ouvrez une photo depuis une autre
application (Mes fichiers, une messagerie…), choisissez **CloudGallery**, puis **Toujours**.

> Les APK publiés sont signés avec la clé de release du projet. Un APK compilé vous-même porte une
> autre signature : il faut désinstaller l'un avant d'installer l'autre, ce qui efface les favoris
> et les albums personnalisés.

---

## Compiler depuis les sources

Prérequis : Android Studio récent (AGP 9.4), JDK 17 ou plus récent, SDK Android API 37.

```bash
git clone https://github.com/Maxlego08/CloudGallery.git
cd CloudGallery
./gradlew assembleDebug        # Windows : .\gradlew.bat assembleDebug
```

L'APK se trouve dans `app/build/outputs/apk/debug/`.

### Activer OneDrive dans votre build

Sans configuration, l'application se compile normalement et les fonctions OneDrive sont
masquées. Pour les activer, il faut une inscription d'application gratuite dans Microsoft Entra,
sans secret client :

1. Inscrivez l'application dans le [centre d'administration Entra](https://entra.microsoft.com) :
   - type de compte : *Comptes Microsoft personnels* ;
   - plateforme Android : package `fr.eva.cloudgallery` et hachage de votre clé de signature ;
   - autorisations déléguées : `Files.Read`, `User.Read` et `offline_access`.
2. Ajoutez dans `local.properties` (fichier non versionné) :

   ```properties
   msal.clientId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   msal.signatureHash=XXXXXXXXXXXXXXXXXXXXXXXXXXX=
   ```

La procédure complète, avec le calcul du hachage sous Windows, se trouve dans
[docs/DOCUMENTATION.md](docs/DOCUMENTATION.md#configuration-microsoft-entra-et-paramétrage-msal).

### Build release signé

Pour signer `assembleRelease` avec votre propre clé, définissez ces propriétés dans
`~/.gradle/gradle.properties` :

```properties
cloudgallery.release.storeFile=C:/chemin/vers/release.jks
cloudgallery.release.storePassword=…
cloudgallery.release.keyAlias=…
cloudgallery.release.keyPassword=…
msal.releaseSignatureHash=…   # hachage de cette clé, à ajouter aussi dans Entra
```

Sans ces propriétés, le build release est signé avec la clé debug.

### Tests

```bash
./gradlew testDebugUnitTest          # tests unitaires JVM
./gradlew connectedDebugAndroidTest  # tests instrumentés (appareil requis)
```

---

## Technique

Kotlin, Jetpack Compose, Hilt, Room (avec Paging 3), WorkManager, DataStore, Coil 3,
Media3 ExoPlayer, MSAL et Microsoft Graph (requête delta).

Le détail de l'architecture, de la synchronisation, du cache, des permissions et des tests est
dans [docs/DOCUMENTATION.md](docs/DOCUMENTATION.md).

## Limitations connues

- OneDrive : comptes Microsoft personnels uniquement, un seul compte à la fois, en lecture seule.
- Pas de synchronisation en arrière-plan : la bibliothèque est mise à jour quand l'application est
  ouverte.
- Les favoris et les albums personnalisés sont propres à l'application. Ils ne sont pas partagés
  avec Samsung Galerie et sont perdus en cas de désinstallation.
- Pas de décodage par tuiles pour les très grandes images (au-delà d'environ 24 MP), ni de GIF
  animés ou de Motion Photos.
- Recherche sans reconnaissance de lieux ni de contenu.

La liste complète se trouve dans
[docs/DOCUMENTATION.md](docs/DOCUMENTATION.md#limitations-connues).

## Licence

[MIT](LICENSE)
