package network;

import entities.Entity;
import entities.Player;
import environment.Tile;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class GameServer {
    private List<ClientHandler> clients = new ArrayList<>();
    private Tile[][] map;
    private int totalGoldCollected = 0;
    private final int OBJECTIF_OR = 10;
    private GameLoop gameLoop;
    private List<Entity> entities = new ArrayList<>();

    public GameServer(int width, int height) {
        this.map = new Tile[width][height];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                map[i][j] = new Tile(Tile.EMPTY);
            }
        }
    }

    /**
     * Lance le jeu
     * @param port le port sur lequel le serveur écoute
     */
    public void start(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Serveur DMND démarré sur le port " + port);

            while (true) {
                Socket socket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(socket, this);
                clients.add(handler);
                handler.start(); // Lance le thread du ClientHandler
                System.out.println("Nouveau nain connecté !");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void broadcast(GameEvent event) {
        for (ClientHandler client : clients) {
            client.sendEvent(event);
        }
    }

    public synchronized void processMining(int x, int y, Player p) {
        Tile t = map[x][y];
        if (t != null && t.getType() == Tile.GOLD) {
            p.miner(t); // Le joueur gagne +4 or
            totalGoldCollected += 4;
            map[x][y] = new Tile(Tile.EMPTY); // Remplace par du vide

            // On prévient tout le monde que la case a changé
            broadcast(new GameEvent("UPDATE_TILE", x, y, "EMPTY"));
        }
    }

    /**
     * Vérifie si objectif mission rempli
     * @return true si l'objectif est atteint
     */
    public boolean verifierObjectif() {
        return totalGoldCollected >= OBJECTIF_OR;
    }

    /**
     * Méthode pour ajouter une entité à la liste partagée
     * @param e l'entité à ajouter
     */
    public synchronized void addEntity(Entity e) {
        this.entities.add(e);
    }

    public synchronized List<Entity> getEntities() {
        return new ArrayList<>(entities); // Return a copy to avoid external modification
    }

    public static void main(String[] args) {
        GameServer server = new GameServer(20, 20);
        server.gameLoop = new GameLoop(server);
        server.gameLoop.start();
        server.start(1234);
    }
}
