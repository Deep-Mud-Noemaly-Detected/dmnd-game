# dmnd-game

Mini base JavaFX pour afficher une cave procedurale avec un nain jouable.

## Ce qui est en place

- Generation de cave par automate cellulaire (`CaveMap`)
- Rendu de la map sur `Canvas`
- Deplacement du nain avec collisions sur les murs
- Camera qui suit le joueur

## Controles

- Monter: `Z` / `W` / `Up`
- Descendre: `S` / `Down`
- Gauche: `Q` / `A` / `Left`
- Droite: `D` / `Right`

## Lancer le projet

```bash
./mvnw clean javafx:run
```

Sous Windows PowerShell:

```powershell
.\mvnw.cmd clean javafx:run
```

## Fichiers principaux

- `src/main/java/org/example/dmndgame/HelloApplication.java`
- `src/main/java/org/example/dmndgame/CaveMap.java`
- `src/main/java/org/example/dmndgame/Player.java`
