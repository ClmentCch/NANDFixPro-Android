# Notice d'attribution

NAND Fix Pro Android est un portage Android du projet open source **NANDFixPro**.

- Projet original : https://github.com/sthetix/NANDFixPro
- Auteur original : Hendy Kartono (sthetix)
- Licence originale : MIT — Copyright (c) 2025 Hendy Kartono (voir `LICENSE`)

Le texte de licence MIT original est conservé sans modification dans `LICENSE`. Le code source
Windows d'origine reste présent dans ce dépôt (`nandfixpro.py`, `lib/`, `run.bat`) et sa
documentation a été déplacée dans `docs/ORIGINAL_WINDOWS_README.md` pour laisser la place au
README Android, sans en supprimer le contenu.

Les algorithmes suivants ont été portés ou adaptés depuis le projet original :

- moteur PRODINFO `ProdinfoEngine` (table de blocs, CRC16, vérification SHA-256, édition et recalcul) ;
- détection des sauvegardes NAND découpées `detect_split_nand()` ;
- palette et structure fonctionnelle de l'interface.

Les composants tiers remerciés (Hekate, EmmecHaccGen, NxNandManager, OSFMount, 7-Zip, Atmosphère)
appartiennent à leurs auteurs respectifs et ne sont pas redistribués dans l'APK Android.

Aucune affiliation avec Nintendo Co., Ltd.
