package network;

import entities.Entity;
import entities.Monster;
import entities.Orc;
import entities.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

public class GameLoop extends Thread {
    private static final long TICK_MILLIS = 50;
    private static final long WAVE_INTERVAL_MILLIS = 30_000;
    private static final long MONSTER_MOVE_INTERVAL_MILLIS = 1_000;
    private static final long MINING_DURATION_MILLIS = 1_500;

    private final GameServer server;
    private final ConcurrentLinkedQueue<MiningRequest> pendingMiningRequests = new ConcurrentLinkedQueue<>();
    private final Map<Player, MiningProgress> miningByPlayer = new HashMap<>();

    private volatile boolean running = true;

    public GameLoop(GameServer server) {
        this.server = server;
        setName("game-loop");
        setDaemon(true);
    }

    @Override
    public void run() {
        long lastTickTime = System.currentTimeMillis();
        long lastWaveTime = lastTickTime;
        long lastMonsterMoveTime = lastTickTime;

        while (running) {
            LockSupport.parkNanos(TICK_MILLIS * 1_000_000L);
            if (Thread.currentThread().isInterrupted()) {
                running = false;
                break;
            }

            long now = System.currentTimeMillis();
            long elapsed = now - lastTickTime;
            lastTickTime = now;

            processPendingMiningRequests(now);
            updateMiningProgress(now);

            if (now - lastWaveTime >= WAVE_INTERVAL_MILLIS) {
                spawnWave();
                lastWaveTime = now;
            }

            if (now - lastMonsterMoveTime >= MONSTER_MOVE_INTERVAL_MILLIS) {
                updateMonsters();
                lastMonsterMoveTime = now;
            }

            // Garde-fou contre d'eventuels retours temps systeme tres rapides.
            if (elapsed < 0) {
                lastWaveTime = now;
                lastMonsterMoveTime = now;
            }
        }
    }

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

    private void spawnWave() {
        int spawnX = Math.max(1, server.getMapWidth() / 2);
        int spawnY = Math.max(1, server.getMapHeight() / 2);

        Monster orc = new Orc(spawnX, spawnY, 20);
        server.addEntity(orc);

        server.publishServerEvent(new GameEvent(GameEvent.SPAWN_MONSTER, spawnX, spawnY, "ORC"));
    }

    private void updateMonsters() {
        int mapWidth = server.getMapWidth();
        int mapHeight = server.getMapHeight();

        for (Entity e : server.getEntities()) {
            if (!(e instanceof Monster)) {
                continue;
            }

            int newX = e.getX() + (Math.random() > 0.5 ? 1 : -1);
            int newY = e.getY() + (Math.random() > 0.5 ? 1 : -1);

            if (newX >= 0 && newX < mapWidth && newY >= 0 && newY < mapHeight) {
                e.setX(newX);
                e.setY(newY);
                server.publishServerEvent(new GameEvent(GameEvent.MOVE_MONSTER, newX, newY, "ORC"));
            }
        }
    }

    private static final class MiningRequest {
        private final Player player;
        private final int x;
        private final int y;

        private MiningRequest(Player player, int x, int y) {
            this.player = player;
            this.x = x;
            this.y = y;
        }
    }

    private static final class MiningProgress {
        private final int x;
        private final int y;
        private final long startMillis;

        private MiningProgress(int x, int y, long startMillis) {
            this.x = x;
            this.y = y;
            this.startMillis = startMillis;
        }
    }
}
