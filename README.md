# Deep Mud : Noemaly Detected

Jeu de donjon multijoueur en Java/JavaFX avec une architecture client-serveur.
Un nain mineur explore une cave générée procéduralement, collecte de l'or et affronte des monstres.

## Prérequis
- Java 17
- Maven (ou utiliser le wrapper `mvnw` fourni)
- JavaFX 21 (géré automatiquement par Maven)
  
> **Note :** Les dépendances JavaFX dans le `pom.xml` sont actuellement classifiées `win`.
> Sur Linux ou macOS, remplacez `<classifier>win</classifier>` par `linux` ou `mac` dans le `pom.xml`.

## Ce qui est en place

- **Architecture client-serveur** : le serveur (`GameServer`) gère la logique de jeu et accepte jusqu'à 8 clients simultanément sur le port `1234`
- **Génération de cave procédurale** par automate cellulaire (`CaveGenerator`) avec lissage paramétrable et graine reproductible
- **Rendu graphique** sur `Canvas` JavaFX avec caméra centrée sur le joueur
- **Tuiles** chargées depuis `src/main/resources/tiles` (sol, murs, décorations, torches) ; fallback couleur si un asset est manquant
- **Sprites animés** pour le nain mineur (Idle, Walk, Slice, Pierce, Crush, Hit, Death) et les monstres (Orc, Squelette)
- **Déplacement du nain** avec détection de collisions sur les murs
- **Collecte d'or** : miner une case `GOLD` rapporte +4 unités ; objectif global fixé à 10 unités d'or
- **Monstres** : Orc (attaque au poignard) et Squelette
- **Équipement** : Hache et Pioche (interface `Item`)
- **Événements réseau** sérialisés (`GameEvent`) : déplacement, minage, spawn/déplacement de monstres, victoire, connexion/déconnexion de joueurs
- **Game loop** serveur cadencée (`GameLoop`) indépendante du rendu JavaFX

## Contrôles

| Action   | Touches                  |
|----------|--------------------------|
| Monter   | `Z` / `W` / `↑`         |
| Descendre| `S` / `↓`               |
| Gauche   | `Q` / `A` / `←`         |
| Droite   | `D` / `→`               |

## Lancer le projet

1. Démarrez le serveur (affiche la fenêtre principale) :

```bash
./mvnw clean javafx:run
```

Sous Windows PowerShell :

```powershell
.\mvnw.cmd clean javafx:run
```

2. Depuis la fenêtre serveur, choisissez le nombre de clients à ouvrir et cliquez sur **Lancer**.  
   Chaque client se connecte automatiquement au serveur local sur le port `1234`.

## Structure du projet
```
src/main/java/
├── network/
│   ├── GameServer.java       # Point d'entrée JavaFX + logique serveur
│   ├── GameClient.java       # (voir controller/) Application cliente
│   ├── ClientHandler.java    # Thread dédié à chaque connexion cliente
│   ├── GameLoop.java         # Boucle de jeu serveur
│   ├── GameEvent.java        # Événements réseau sérialisés
│   └── Launcher.java         # Lanceur alternatif (contourne les modules Java)
├── controller/
│   ├── GameController.java   # Contrôleur FXML (rendu cave, input clavier)
│   ├── GameClient.java       # Application JavaFX cliente
│   └── PlayerView.java       # Représentation visuelle du joueur
├── entities/
│   ├── Entity.java           # Classe de base (position, PV)
│   ├── Player.java           # Joueur (collecte d'or, combat)
│   ├── Monster.java          # Monstre abstrait
│   ├── Orc.java              # Orc (attaque poignard)
│   └── Squelette.java        # Squelette
├── environment/
│   ├── CaveGenerator.java    # Génération de cave par automate cellulaire
│   └── Tile.java             # Case de la grille (type : EMPTY, WALL, GOLD…)
└── equipment/
    ├── Item.java             # Interface équipement
    ├── Hache.java            # Hache
    └── Pioche.java           # Pioche
src/main/resources/
├── index.fxml                # Interface principale (serveur)
├── tiles/                    # Tuiles (sol, murs, déco, torches)
├── entities/                 # Sprites nain et monstres
└── items/                    # Sprites cristaux / objets
```
