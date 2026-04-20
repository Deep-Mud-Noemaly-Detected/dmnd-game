package network;

import entities.Entity;
import entities.Monster;
import entities.Orc;
import entities.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * GameLoop est un thread qui gère la logique de jeu côté serveur :
 * - Minage des joueurs
 * - Vagues de monstres (Orcs uniquement pour l'instant)
 * - Déplacement des monstres vers les joueurs
 * - Attaques des monstres
 */
public class GameLoop extends Thread {
    // --- Configuration du timing (en millisecondes) ---
    private static final long TICK_MILLIS = 50;
    private static final long WAVE_INTERVAL_MILLIS = 20_000;
    private static final long MONSTER_MOVE_MILLIS = 1_000;
    private static final long MONSTER_ATTACK_MILLIS = 1_500;
    private static final long MINING_DURATION_MILLIS = 1_500;

    // --- Configuration des monstres ---
    private static final int ORC_PV = 30;
    private static final int SQUELETTE_PV = 15;

    private final GameServer server;
    private final ConcurrentLinkedQueue<MiningRequest> pendingMiningRequests = new ConcurrentLinkedQueue<>();
    private final Map<Player, MiningProgress> miningByPlayer = new HashMap<>();
    private final Random random = new Random();

    private volatile boolean running = true;
    private int waveNumber = 0;

    public GameLoop(GameServer server) {
        this.server = server;
        setName("game-loop");
        setDaemon(true);
    }

    /**
     * Boucle principale du jeu — tourne tant que le serveur est actif.
     * Gère : minage, vagues, déplacement et attaque des monstres.
     */
    @Override
    public void run() {
        long lastTickTime = System.currentTimeMillis();
        long lastWaveTime = lastTickTime - WAVE_INTERVAL_MILLIS + 5_000;
        long lastMonsterMoveTime = lastTickTime;
        long lastMonsterAttackTime = lastTickTime;

        while (running) {
            LockSupport.parkNanos(TICK_MILLIS * 1_000_000L);
            if (Thread.currentThread().isInterrupted()) {
                running = false;
                break;
            }

            long now = System.currentTimeMillis();
            long elapsed = now - lastTickTime;
            lastTickTime = now;

            // 1. Traitement du minage
            processPendingMiningRequests(now);
            updateMiningProgress(now);

            // 2. Vagues de monstres
            if (now - lastWaveTime >= WAVE_INTERVAL_MILLIS) {
                spawnWave();
                lastWaveTime = now;
            }

            // 3. Déplacement des monstres vers les joueurs
            if (now - lastMonsterMoveTime >= MONSTER_MOVE_MILLIS) {
                updateMonsterMovement();
                lastMonsterMoveTime = now;
            }

            // 4. Attaques des monstres
            if (now - lastMonsterAttackTime >= MONSTER_ATTACK_MILLIS) {
                updateMonsterAttacks();
                lastMonsterAttackTime = now;
            }

            // 5. Nettoyage des monstres morts
            removeDeadMonsters();

            // Garde-fou temps système
            if (elapsed < 0) {
                lastWaveTime = now;
                lastMonsterMoveTime = now;
                lastMonsterAttackTime = now;
            }
        }
    }

    /**
     * Arrête la boucle de jeu.
     */
    public void stopLoop() {
        running = false;
        interrupt();
    }

    public void requestMining(Player player, int x, int y) {
        if (!running || player == null) {
            return;
        }
        pendingMiningRequests.offer(new MiningRequest(player, x, y));
    }

    private void processPendingMiningRequests(long now) {
        MiningRequest request;
        while ((request = pendingMiningRequests.poll()) != null) {
            miningByPlayer.put(request.player, new MiningProgress(request.x, request.y, now));
        }
    }

    private void updateMiningProgress(long now) {
        miningByPlayer.entrySet().removeIf(entry -> {
            MiningProgress progress = entry.getValue();
            if (now - progress.startMillis < MINING_DURATION_MILLIS) {
                return false;
            }

            server.processMining(progress.x, progress.y, entry.getKey());
            if (server.verifierObjectif()) {
                server.publishServerEvent(new GameEvent(GameEvent.VICTORY, 0, 0, "Objectif atteint !"));
            }
            return true;
        });
    }

    /**
     * Génère une vague de monstres.
     * Simplifié : uniquement des Orcs pour le moment car squelette tendue pour le moment.
     */
    private void spawnWave() {
        waveNumber++;
        int mapWidth = server.getMapWidth();
        int mapHeight = server.getMapHeight();

        int monsterCount = Math.min(waveNumber, 3);

        System.out.println("[GameLoop] Vague " + waveNumber + " : " + monsterCount + " Orc(s)");

        for (int i = 0; i < monsterCount; i++) {
            // Spawn aléatoire sur les bords de la map
            int spawnX, spawnY;
            if (random.nextBoolean()) {
                spawnX = random.nextBoolean() ? 1 : mapWidth - 2;
                spawnY = 1 + random.nextInt(Math.max(1, mapHeight - 2));
            } else {
                spawnX = 1 + random.nextInt(Math.max(1, mapWidth - 2));
                spawnY = random.nextBoolean() ? 1 : mapHeight - 2;
            }

            // Uniquement des Orcs (simplifié)
            Monster monster = new Orc(spawnX, spawnY, ORC_PV);
            server.addEntity(monster);

            server.publishServerEvent(new GameEvent(GameEvent.SPAWN_MONSTER, spawnX, spawnY, "ORC"));
        }
    }


    private void updateMonsterMovement() {
        int mapWidth = server.getMapWidth();
        int mapHeight = server.getMapHeight();
        List<Entity> entities = server.getEntities();

        // Trouve tous les joueurs vivants
        List<Player> players = entities.stream()
                .filter(e -> e instanceof Player && e.isAlive())
                .map(e -> (Player) e)
                .toList();

        if (players.isEmpty()) {
            return;
        }

        for (Entity e : entities) {
            if (!(e instanceof Monster monster) || !monster.isAlive()) {
                continue;
            }

            // Trouve le joueur le plus proche
            Player closest = null;
            int minDist = Integer.MAX_VALUE;
            for (Player p : players) {
                int dist = monster.distanceTo(p);
                if (dist < minDist) {
                    minDist = dist;
                    closest = p;
                }
            }

            // Déplace le monstre vers ce joueur
            if (closest != null) {
                monster.moveToward(closest, mapWidth, mapHeight);

                String type = (monster instanceof Orc) ? "ORC" : "SQUELETTE";
                server.publishServerEvent(new GameEvent(
                        GameEvent.MOVE_MONSTER,
                        monster.getX(),
                        monster.getY(),
                        type
                ));
            }
        }
    }

    /**
     * Fait attaquer chaque monstre si un joueur est à portée.
     */
    private void updateMonsterAttacks() {
        List<Entity> entities = server.getEntities();

        // Trouve tous les joueurs vivants
        List<Player> players = entities.stream()
                .filter(e -> e instanceof Player && e.isAlive())
                .map(e -> (Player) e)
                .toList();

        for (Entity e : entities) {
            if (!(e instanceof Monster monster) || !monster.isAlive()) {
                continue;
            }

            // Attaque le joueur le plus proche s'il est à portée
            for (Player p : players) {
                if (monster.attaquer(p)) {
                    String type = (monster instanceof Orc) ? "ORC" : "SQUELETTE";
                    System.out.println("[GameLoop] " + type + " attaque " + p.getName() + " ! PV restants : " + p.getPv());

                    server.publishServerEvent(new GameEvent(
                            GameEvent.MONSTER_ATTACK,
                            p.getX(),
                            p.getY(),
                            p.getName() + ":" + type + ":" + p.getPv()
                    ));

                    // Vérifie si le joueur est mort
                    if (!p.isAlive()) {
                        server.publishServerEvent(new GameEvent(
                                GameEvent.PLAYER_DIED,
                                p.getX(),
                                p.getY(),
                                p.getName()
                        ));
                    }
                    break;
                }
            }
        }
    }

    /**
     * Supprime les monstres morts de la liste des entités.
     */
    private void removeDeadMonsters() {
        List<Entity> entities = server.getEntities();
        for (Entity e : entities) {
            if (e instanceof Monster && !e.isAlive()) {
                server.removeEntity(e);
                System.out.println("[GameLoop] Monstre éliminé !");
            }
        }
    }

    static final class MiningRequest {
        final Player player;
        final int x;
        final int y;

        MiningRequest(Player player, int x, int y) {
            this.player = player;
            this.x = x;
            this.y = y;
        }
    }

    static final class MiningProgress {
        final int x;
        final int y;
        final long startMillis;

        MiningProgress(int x, int y, long startMillis) {
            this.x = x;
            this.y = y;
            this.startMillis = startMillis;
        }
    }
}
