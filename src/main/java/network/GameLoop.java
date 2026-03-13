package network;

import entities.Entity;
import entities.Monster;
import entities.Orc;

public class GameLoop extends Thread {
    private GameServer server;
    private boolean running = true;
    private int waveTimer = 0;

    public GameLoop(GameServer server) {
        this.server = server;
    }

    @Override
    public void run() {
        while (running) {
            try {
                // On boucle toutes les secondes (1000ms)
                Thread.sleep(1000);

                waveTimer++;

                // Logique des vagues : toutes les 30 secondes
                if (waveTimer >= 30) {
                    spawnWave();
                    waveTimer = 0;
                }

                // Faire bouger les monstres existants
                updateMonsters();

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void spawnWave() {
        System.out.println("Une nouvelle vague apparaît !");
        // Exemple : Faire apparaître un Orc à une position aléatoire
        Monster orc = new Orc(5, 5, 20);
        server.addEntity(orc);

        // On prévient tous les clients pour qu'ils affichent le nouveau monstre
        server.broadcast(new GameEvent("SPAWN_MONSTER", 5, 5, "ORC"));
    }

    private void updateMonsters() {
        for (Entity e : server.getEntities()) {
            if (e instanceof Monster) {
                int newX = e.getX() + (Math.random() > 0.5 ? 1 : -1);
                int newY = e.getY() + (Math.random() > 0.5 ? 1 : -1);
                // Limites (map de 20x20)
                if (newX >= 0 && newX < 20 && newY >= 0 && newY < 20) {
                    e.setX(newX);
                    e.setY(newY);
                    server.broadcast(new GameEvent("MOVE_MONSTER", newX, newY, "ORC"));
                }
            }
        }
    }
}
