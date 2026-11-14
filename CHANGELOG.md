# Changelog — NAND Fix Pro Android

## 1.0.0 — 2026-11-14

Première version Android de NAND Fix Pro.

### Interface

- Application native Kotlin + Jetpack Compose + Material 3, thème sombre hérité du projet Windows.
- Navigation complète : accueil, connexion USB, analyse, sauvegardes, fichiers, diagnostics,
  journal, PRODINFO, BOOT0/BOOT1, partitions, firmware, paramètres, à propos.
- Notifications d'état, console de logs filtrable, barres de progression et annulation.

### USB OTG / Hekate

- Énumération USB Host : VID/PID, fabricant, produit, série, interfaces et endpoints.
- Demande de permission USB (PendingIntent mutable), surveillance attachement/détachement.
- Transport USB Mass Storage Bulk-Only (SCSI) en lecture seule : INQUIRY, READ CAPACITY 10/16,
  READ 10/16, REQUEST SENSE, TEST UNIT READY.
- Lecture réelle de la GPT et des partitions sur une cible Hekate `eMMC RAW GPP`.
- Section Hekate USB Connection avec verdict fondé sur les preuves observées.
- Test de lecture 4 Mio et sauvegarde locale d'une partition BOOT exposée en USB.

### Analyse NAND

- Analyseur `RAWNAND.bin`, images GPP, `BOOT0`, `BOOT1`, `PRODINFO`, `prod.keys`.
- Détection et vérification des jeux découpés Hekate et emuMMC.
- Vérification GPT (CRC32, GPT de secours, taille, chevauchements, limites).
- PRODINFO : portage du moteur CAL0 (CRC16, SHA-256, blocs, modèle, série, région, couleurs) et
  export d'une copie modifiée avec checksums recalculés.
- Contrôles d'intégrité et empreintes SHA-256.
- Sauvegardes locales hachées avec manifeste, vérification et suppression.
- Navigateur de fichiers SAF avec détection des formats NAND.
- Inventaire de dossier firmware sans déduction de version.

### Fiabilité

- Lecture seule garantie sur tout support physique.
- Gestion des déconnexions USB et annulation sécurisée des opérations.
- Auto-test des moteurs embarqué (8 contrôles).
- 42 tests unitaires JVM.

### Limites documentées

- Aucune écriture NAND/SD, aucune réparation simulée.
- Outils Windows d'origine (EmmecHaccGen, NxNandManager, OSFMount) non portables.
- Pas de navigation FAT en UMS, pas de version firmware, pas de téléchargement de firmware.
- Connexion Switch/Hekate réelle non testée faute de matériel.
