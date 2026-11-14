# NAND Fix Pro Android

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/ClmentCch/NANDFixPro-Android/releases/tag/v1.0.0)
[![Platform](https://img.shields.io/badge/platform-Android%2013%2B%20(ARM64)-3DDC84.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Licence](https://img.shields.io/badge/licence-MIT-green.svg)](LICENSE)

Portage Android natif de **NANDFixPro** (outil Windows de diagnostic et de réparation NAND pour
Nintendo Switch), avec une couche **USB OTG / USB Host Mass Storage** et une interface **Hekate**
spécifiquement conçues pour le téléphone.

> **État de cette version : analyse en lecture seule.**
> L'application ne contient **aucune commande d'écriture** vers un périphérique physique.
> Elle ne simule aucune réparation et ne prétend jamais avoir réparé une NAND.
> Le mode de réparation du projet Windows d'origine nécessite des binaires x64 et des pilotes
> Windows : il est expliqué, pas simulé. Voir [Limites techniques](#limites-techniques-et-pourquoi).

---

## Sommaire

- [État de vérification](#état-de-vérification)
- [Fonctionnalités](#fonctionnalités)
- [USB OTG et compatibilité Hekate](#usb-otg-et-compatibilité-hekate)
- [Formats de fichiers pris en charge](#formats-de-fichiers-pris-en-charge)
- [Installation de l'APK](#installation-de-lapk)
- [Compiler depuis les sources](#compiler-depuis-les-sources)
- [Utilisation](#utilisation)
- [Architecture](#architecture)
- [Tests](#tests)
- [Sécurité et fiabilité](#sécurité-et-fiabilité)
- [Limites techniques (et pourquoi)](#limites-techniques-et-pourquoi)
- [Crédits et licence](#crédits-et-licence)

---

## État de vérification

Cette section est volontairement factuelle : elle distingue ce qui a été exécuté de ce qui ne
pouvait pas l'être dans l'environnement de développement.

| Élément | Statut |
| --- | --- |
| Compilation Gradle (AGP 8.7.3, Kotlin 2.0.21, JDK 17) | ✅ `BUILD SUCCESSFUL` |
| Tests unitaires JVM (`./gradlew testDebugUnitTest`) | ✅ **42 tests, 0 échec** |
| APK release minifié (R8) généré | ✅ `app-release.apk` — 2,76 Mo |
| APK installable (signé, manifeste valide, ARM64 inclus) | ✅ vérifié avec `apksigner` et `aapt2` |
| Auto-test des moteurs embarqué (écran Diagnostics) | ✅ 8 contrôles sur des structures synthétiques |
| Connexion réelle à une Switch via Hekate | ❌ **non testée — aucun matériel disponible** |
| Lecture USB réelle d'un périphérique UMS | ❌ **non testée — aucun matériel disponible** |
| Exécution de l'interface sur un téléphone physique | ❌ **non testée — pas d'appareil ni d'émulateur dans l'environnement** |
| Validation Android 16 / 17 | ❌ non disponible au moment de la v1.0.0 (compilation et cible API 35, minSdk 33) |

L'APK de la release est **auto-signé avec la clé de debug Android** (`CN=Android Debug`,
SHA-256 `f514503594c8b9b3d4ed2fcbd86aec2aa73739122b622163876fb7fa696ac659`). Il s'installe par
sideload. Android exige la même signature pour une mise à jour : si vous publiez des versions
ultérieures, générez votre propre keystore et signez avec (voir
[Compiler depuis les sources](#compiler-depuis-les-sources)).

---

## Fonctionnalités

### USB OTG / USB Host — fonctionnalité prioritaire

- Énumération de **tous** les périphériques USB connectés (VID/PID, fabricant, produit, numéro de
  série, version, classe).
- Détail complet des **interfaces** et de leurs **points de terminaison** (Bulk IN/OUT, taille de
  paquet, adresse).
- Identification des interfaces **USB Mass Storage** (classe `0x08`) et du protocole **SCSI
  Bulk-Only** (sous-classe `0x06`, protocole `0x50`).
- Demande de permission USB via `PendingIntent` mutable (obligatoire depuis Android 12), avec
  surveillance des attachements/détachements en temps réel.
- Ouverture réelle du périphérique et dialogue **SCSI Bulk-Only** : `TEST UNIT READY`, `INQUIRY`,
  `REQUEST SENSE`, `READ CAPACITY(10)`, `READ CAPACITY(16)`, `READ(10)`, `READ(16)`.
- Lecture de la table **GPT** directement sur le support, vérification des CRC32 et de la GPT de
  secours, énumération des partitions.
- Section **HEKATE USB CONNECTION** : état, périphérique, type, capacité, mode de connexion,
  compatibilité, dernière activité, et **liste des preuves réellement observées**.
- Test de lecture réel (4 Mio) avec débit et SHA-256.
- Sauvegarde locale d'une partition **BOOT** exposée en USB (jusqu'à 16 Mio).

### Modules NAND (hérités de NANDFixPro, adaptés à Android)

- **NAND Analyzer** : `RAWNAND.bin`, images GPP, détection de type par nom/taille/signature,
  classe de capacité (32/64/128 Go), GPT, partitions, SHA-256 d'échantillons, contrôles d'intégrité.
- **Jeux découpés** : détection complète des sauvegardes Hekate `rawnand.bin.NN` et des fichiers
  emuMMC `00, 01, …` + `BOOT0`/`BOOT1`, contrôle de contiguïté, tailles homogènes, alignement,
  cohérence entre la somme des parties et la GPT.
- **PRODINFO Analyzer** : portage fidèle du moteur d'origine (magic `CAL0`, table de blocs, CRC16,
  SHA-256 du corps), détection Erista/Mariko/Lite/OLED, série, région WiFi, MAC, couleurs,
  vérification complète. Édition possible **uniquement dans une copie exportée**, avec recalcul et
  re-vérification des checksums.
- **BOOT0 / BOOT1 Manager** : contrôle de taille (4 Mio), détection de zone vide, premiers octets,
  SHA-256, rappel de la procédure de restauration Hekate.
- **SYSTEM / USER / RAW GPP** : offsets, tailles, signatures de premier secteur, empreintes
  d'échantillons, contrôles de cohérence GPT.
- **NAND Integrity Checker** : CRC en-tête et table GPT, GPT de secours, taille vs GPT, partitions
  hors limites, chevauchements, PRODINFO, jeu découpé.
- **Firmware Information** : inventaire d'un dossier fourni (fichiers, taille, NCA, `Contents/registered`,
  `bootloader`, `atmosphere`) sans prétendre déterminer la version.
- **Backup Manager** : copie locale vérifiée avec manifeste SHA-256, liste, vérification, suppression.
- **prod.keys** : validation structurelle (noms, tailles, générations de master keys) **sans jamais
  afficher, journaliser ou copier les valeurs**.
- **File Manager** : navigation SAF (carte SD montée par Android, stockage interne, dossiers de
  dumps), détection des fichiers NAND pertinents, analyse directe ou analyse du jeu découpé.
- **Journal & Diagnostics** : console de logs filtrable, export horodaté, informations appareil,
  recherche des limites, auto-test des moteurs.

### Interface

- Kotlin + Jetpack Compose + Material 3, **thème sombre uniquement**, palette héritée du projet
  d'origine (`#375A7F`, `#00BC8C`, `#F39C12`, `#E74C3C`, fonds `#1C1C1C`/`#222222`).
- Navigation par barre inférieure, cartes, indicateurs d'état, barres de progression, animations
  discrètes (`AnimatedVisibility`, `animateContentSize`).
- Écran d'accueil avec logo, boutons **DÉTECTER UN PÉRIPHÉRIQUE USB** / **DÉTECTER HEKATE**, et
  accès à tous les modules.
- Notifications d'état (connexion USB, fin d'opération) et permissions Android 13 demandées au
  premier lancement.
- Pages **Paramètres** et **À propos** complètes.

---

## USB OTG et compatibilité Hekate

### Le problème Android

Contrairement à Windows, Linux ou macOS, **Android n'intègre pas de pilote USB Mass Storage**.
Brancher une Switch en mode `eMMC RAW GPP` ne fait donc apparaître **aucun volume** dans le
téléphone. C'est précisément pourquoi NAND Fix Pro Android dialogue directement en SCSI.

### Ce que fait l'application

1. Elle énumère les périphériques via `UsbManager` et repère l'interface `class 0x08 / subclass 0x06`.
2. Elle demande la permission, ouvre la connexion, réclame l'interface et localise les endpoints
   Bulk IN/OUT.
3. Elle exécute de vraies commandes SCSI en lecture seule et lit les premiers secteurs.
4. Elle construit un verdict **à partir de preuves** :
   - INQUIRY (vendeur/produit/révision) ;
   - capacité réelle (`READ CAPACITY`) ;
   - GPT lisible, CRC valide, partitions `PRODINFO`, `SYSTEM`, `USER`, `SAFE`, etc. ;
   - signature du premier secteur (FAT32, exFAT, GPT, zone vide).
5. Elle ne déclare **jamais** « compatible » sur la seule base du VID/PID ou d'un nom commercial.
   Un périphérique qui répond en USB mais dont aucune structure NAND n'est lisible est classé
   `Incompatible` ou `UMS standard`, avec les raisons affichées.

### Verdicts

| Verdict | Signification |
| --- | --- |
| **GPP compatible** | UMS lisible + GPT valide + disposition de partitions Switch reconnue |
| **BOOT Hekate** | Petit volume UMS (≤ 16 Mio) sans GPT, compatible avec une partition BOOT0/BOOT1 |
| **UMS standard** | Mass Storage lisible mais pas une image GPP (ex. carte SD, volume FAT) |
| **Incompatible** | Interface UMS absente ou communication SCSI impossible |
| **Inconnu** | Périphérique lisible mais contenu non identifiable |

### Procédure recommandée

1. Câble **USB-C OTG compatible données** (un câble de charge seule ne fonctionne pas). Un câble
   USB 2.0 de bonne qualité est plus stable pour les opérations eMMC.
2. Sur la Switch : **Hekate → Tools → USB Tools**.
3. Choisir **eMMC RAW GPP** (ou **BOOT0** / **BOOT1** pour une sauvegarde de boot).
4. Brancher au téléphone, ouvrir **Connexion USB**, toucher **DÉTECTER UN PÉRIPHÉRIQUE USB**, accepter
   la demande Android.
5. Lire le verdict et les preuves dans **HEKATE USB CONNECTION**.

> Le mode lecture seule de Hekate peut rester activé : l'application n'écrit jamais. Certains
> téléphones limitent l'alimentation USB; si la détection échoue alors que le câble est bon,
> essayez un autre téléphone ou un hub OTG alimenté.

---

## Formats de fichiers pris en charge

| Fichier | Analyse |
| --- | --- |
| `RAWNAND.bin` / image GPP | GPT, partitions, intégrité, capacité, SHA-256 d'échantillon |
| `rawnand.bin.00`, `.01`, … | Détection du jeu complet, assemblage virtuel, cohérence GPT |
| `00`, `01`, … (emuMMC) | Détection + exigence `BOOT0`/`BOOT1` |
| `BOOT0`, `BOOT1` | Taille 4 Mio, contenu vide, empreinte |
| `PRODINFO`, `PRODINFO.bin`, `*.dec` | CAL0, CRC16, SHA-256, identité, export vérifié |
| `prod.keys` | Structure uniquement, valeurs jamais exposées |

Les gros fichiers sont lus **par accès aléatoire** via `ParcelFileDescriptor` + `FileChannel`.
Ils ne sont ni copiés en mémoire ni déplacés. Si un fournisseur SAF ne permet pas l'accès aléatoire,
l'application le dit explicitement et propose de copier le fichier dans le stockage interne.

---

## Installation de l'APK

L'APK signé est joint à la release **v1.0.0** :

```
NANDFixPro-Android-1.0.0.apk
```

Installation :

```bash
adb install -r NANDFixPro-Android-1.0.0.apk
```

ou copier l'APK sur le téléphone et l'ouvrir (autoriser l'installation depuis cette source).
L'application ne demande **aucune permission de stockage** : elle utilise Storage Access Framework
et son propre dossier applicatif.

---

## Compiler depuis les sources

### Prérequis

- **JDK 17** (AGP 8.7.3 refuse les JDK plus anciens).
- **Android SDK** avec `platforms;android-35` et `build-tools;35.0.0`.
- Gradle est fourni par le wrapper (`./gradlew`).

```bash
export JAVA_HOME=/chemin/vers/jdk17
export ANDROID_HOME=/chemin/vers/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

### Commandes

```bash
./gradlew :app:assembleDebug      # APK de développement
./gradlew :app:testDebugUnitTest  # 42 tests unitaires JVM
./gradlew :app:assembleRelease    # APK release minifié (R8)

# Sorties :
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

### Signature d'une vraie release

Par défaut, le build release utilise la clé de debug pour rester installable. Pour signer avec
votre propre clé, créez un fichier `keystore.properties` à la racine (ignoré par Git) :

```properties
storeFile=chemin/vers/nandfix-release.jks
storePassword=********
keyAlias=nandfix
keyPassword=********
```

ou utilisez les variables d'environnement `NANDFIX_STORE_FILE`, `NANDFIX_STORE_PASSWORD`,
`NANDFIX_KEY_ALIAS`, `NANDFIX_KEY_PASSWORD`. Aucun secret n'est stocké dans le dépôt.

### Configuration technique

| Paramètre | Valeur |
| --- | --- |
| `applicationId` | `com.nandfixpro.android` |
| `minSdk` | 33 (Android 13) |
| `targetSdk` / `compileSdk` | 35 (Android 15) |
| Kotlin | 2.0.21 |
| AGP | 8.7.3 |
| Compose BOM | 2024.10.01 |
| ABI | universel (arm64-v8a, armeabi-v7a, x86, x86_64) |

Android 17 n'étant pas publié à la date de la v1.0.0, la compatibilité future repose sur `minSdk 33`
et l'absence d'API supprimée. Aucune validation sur Android 16/17 n'a pu être effectuée.

---

## Utilisation

1. **Accueil** : état USB, boutons de détection, accès aux modules, journal récent.
2. **Connexion USB** : détection, permissions, interfaces, section Hekate, test de lecture.
3. **Analyse** : sélection d'un fichier ou d'un dossier découpé, résultat détaillé et contrôles.
4. **Sauvegardes** : copie hachée dans le stockage de l'application, vérification, suppression.
5. **Fichiers** : navigation SAF, analyse directe, hash, analyse d'un jeu découpé.
6. **Diagnostics** : informations appareil, auto-test, journal exportable.
7. **Paramètres** : notifications, délai USB, confirmations, journal détaillé, écran allumé.
8. **À propos** : version, licence, crédits, limites assumées.

### Raccourcis

| Action | Où |
| --- | --- |
| Détecter un périphérique | Accueil ou Connexion USB |
| Détecter Hekate automatiquement | Accueil → **DÉTECTER HEKATE** |
| Analyser `RAWNAND.bin` | Analyse → **CHOISIR UN FICHIER NAND** |
| Analyser un jeu découpé | Analyse → **Analyser un jeu découpé (dossier)** |
| Vérifier un PRODINFO | Accueil → **PRODINFO Analyzer** |
| Sauvegarder une partition BOOT USB | Connexion USB → verdict **BOOT Hekate** |

---

## Architecture

```
app/src/main/java/com/nandfixpro/android/
├── MainActivity.kt              # activité unique, thème, permission notifications, USB attach
├── NandFixProApp.kt             # Application, canaux de notification
├── AppViewModel.kt              # état global, opérations, orchestration USB/analyse/sauvegardes
├── data/
│   ├── AppSettings.kt           # DataStore (préférences)
│   ├── LogRepository.kt         # journal thread-safe, export
│   ├── NotificationHelper.kt    # notifications d'état
│   └── StorageRepository.kt     # SAF, accès aléatoire, sauvegardes + manifestes
├── diagnostics/
│   ├── SelfTest.kt              # auto-test des moteurs dans l'app
│   └── SyntheticFixtures.kt     # GPT/PRODINFO synthétiques (aucune donnée réelle)
├── nand/
│   ├── RandomAccessSource.kt    # abstractions lecture seule (fichier, canal, multi-parties)
│   ├── Gpt.kt                   # parsing GPT + CRC32 + GPT de secours
│   ├── ProdinfoEngine.kt        # portage CAL0 / CRC16 / SHA-256
│   ├── NandAnalyzer.kt          # pipeline d'analyse + contrôles d'intégrité
│   ├── NandFileType.kt          # détection de type + classe de capacité
│   ├── KeysFile.kt              # validation prod.keys sans exposer les valeurs
│   └── SplitNandDetector.kt     # portage detect_split_nand()
├── usb/
│   ├── UsbRepository.kt         # énumération, permissions, hotplug, ouverture
│   ├── MassStorageSession.kt    # BOT : CBW → data → CSW, READ seulement
│   ├── ScsiProtocol.kt          # CDB/CBW/CSW, capacités, INQUIRY (pur Kotlin, testé)
│   ├── HekateProbe.kt           # verdict fondé sur les preuves lues
│   └── UsbModels.kt             # modèles d'interface/périphérique
└── ui/
    ├── NandApp.kt               # navigation, barre inférieure, snackbar
    ├── components/Common.kt     # cartes, pills, progression, console, scaffold
    ├── screens/…                # 12 écrans Compose
    └── theme/…                  # palette héritée de NANDFixPro
```

**Invariant de conception** : tous les analyseurs consomment une interface `RandomAccessSource`
(fichier, canal SAF, multi-parties, session USB) et sont **en lecture seule**. Il n'existe aucune
méthode `write` dans les couches `nand`, `usb` ou `storage` appliquée à un support physique.

---

## Tests

### Tests unitaires JVM

```bash
./gradlew :app:testDebugUnitTest
```

42 tests couvrent :

- parsing GPT (signature, CRC, entrées, GPT de secours, GUID, image tronquée) ;
- moteur PRODINFO (chargement CAL0, CRC16, SHA-256, édition + recalcul, refus des fichiers
  invalides, immutabilité de la source) ;
- détection des jeux découpés Hekate et emuMMC (parties manquantes, tailles incohérentes, BOOT
  requis) ;
- protocole SCSI (CBW 31 octets, flags, LUN, CDB READ(10)/READ(16), CSW, capacités 10/16, INQUIRY,
  sense) ;
- pipeline d'analyse complet sur image synthétique, troncature, BOOT vide, `prod.keys` ;
- sources multi-parties et sondage de partitions.

### Auto-test embarqué

**Diagnostics → LANCER L'AUTO-TEST** exécute 8 contrôles sur des structures synthétiques
valides et affiche le détail. Il permet de vérifier que les moteurs fonctionnent sur l'appareil,
sans console ni dump réel.

### Tests non réalisés

- **Matériel réel** : aucune Switch, aucun câble OTG et aucun périphérique UMS n'était disponible.
  Les couches USB/SCSI sont donc vérifiées par tests unitaires de protocole, pas par une session
  physique.
- **Appareil Android** : aucun émulateur ni téléphone dans l'environnement de build. L'interface
  compile et l'APK est valide, mais elle n'a pas été parcourue à l'écran.
- **Providers SAF exotiques** : seuls les providers à descripteur seekable sont supportés par
  conception; le fallback est expliqué à l'utilisateur.

---

## Sécurité et fiabilité

- **Aucune écriture** sur NAND, carte SD, partition ou périphérique USB. Pas de commande SCSI
  `WRITE(10/16)`, `FORMAT UNIT`, `UNMAP` ni `SANITIZE`.
- **Vérifications systématiques** : signature, CRC32 GPT, SHA-256, tailles, alignement 512 octets,
  partitions hors limites, chevauchements, complétude des jeux découpés.
- **Gestion des déconnexions** : fermeture de session, annulation des permissions, message explicite.
- **Annulation coopérative** des lectures, analyses, hachages et sauvegardes.
- **Sauvegardes vérifiées** : manifeste JSON + SHA-256, vérification à la demande, suppression
  confirmée.
- **Confidentialité** : les clés `prod.keys` et le PRODINFO ne sont jamais journalisés, affichés en
  clair (au-delà des champs demandés) ni transmis. Aucune permission réseau n'est déclarée.
- **Protection contre les copies partielles** : un fichier de sauvegarde incomplet est supprimé et
  l'erreur est remontée.

---

## Limites techniques (et pourquoi)

| Fonction du projet Windows | Statut Android | Raison technique |
| --- | --- | --- |
| Écriture / flash NAND, reconstruction | **Non implémenté (volontaire)** | Exige une écriture brute; l'application reste en lecture seule par conception |
| Génération de `BOOT0`/`BOOT1` (EmmcHaccGen) | **Impossible tel quel** | `EmmcHaccGen.exe` est un binaire .NET x64; Android ARM64 ne peut pas l'exécuter sans réimplémentation complète du chiffrement |
| Montage / modification de partitions (NxNandManager) | **Impossible tel quel** | Binaire natif x64 + pilote Dokan (Windows) |
| Montage de disques virtuels (OSFMount) | **Impossible** | Pilote de disque virtuel Windows uniquement |
| Navigation FAT de la carte SD en UMS | **Non inclus en v1.0.0** | Android n'expose pas de pilote UMS/FAT; un lecteur FAT32 read-only serait nécessaire |
| Version de firmware | **Non déduite** | Nécessite le parsing des NCA et le déchiffrement des titres |
| Téléchargement de firmware | **Non inclus** | Hôte de fichiers et questions légales hors périmètre |
| Déchiffrement PRODINFO | **Non inclus** | Nécessite les clés propres à la console (`prod.keys`); seule l'analyse CAL0 déchiffré est proposée |
| Dump complet eMMC (29–58 Go) en USB | **Non proposé** | Durée et espace de stockage incompatibles avec un téléphone; seules les partitions BOOT (≤ 16 Mio) sont sauvegardées |

**Alternatives réalistes** : valider l'intégrité et l'identité sur Android, puis effectuer la
réparation sur PC avec NANDFixPro et flasher via Hekate. La restauration `BOOT0`/`BOOT1` se fait
depuis la carte SD dans Hekate (`Tools → Restore eMMC`).

---

## Crédits et licence

Ce projet est distribué sous **licence MIT**. Le texte original est conservé dans
[LICENSE](LICENSE) (Copyright © 2025 Hendy Kartono). Voir aussi [NOTICE.md](NOTICE.md).

### Projet original

- **[NANDFixPro](https://github.com/sthetix/NANDFixPro)** — sthetix / Hendy Kartono.
  Le code Windows d'origine est conservé dans le dépôt (`nandfixpro.py`, `lib/`, `run.bat`) et sa
  documentation dans [docs/ORIGINAL_WINDOWS_README.md](docs/ORIGINAL_WINDOWS_README.md).

### Outils et travaux remerciés

- **[Hekate](https://github.com/CTCaer/hekate)** — CTCaer (UMS, accès eMMC RAW GPP).
- **[EmmecHaccGen](https://github.com/suchmememanymuch/EmmecHaccGen)** — suchmememanymuch.
- **[NxNandManager](https://github.com/eliboa/NxNandManager)** — eliboa.
- **[OSFMount](https://www.osforensics.com/tools/mount-disk-images.html)** — PassMark Software.
- **[7-Zip](https://www.7-zip.org/)** — Igor Pavlov.
- **Atmosphère** — sémantique CAL0 / `INVALID_PRODINFO`.

Aucune affiliation avec Nintendo. Utilisez uniquement vos propres sauvegardes et vos propres clés,
dans le respect des lois applicables.
