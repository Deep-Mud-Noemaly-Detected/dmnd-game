package network;

import entities.Entity;
import entities.Player;
import environment.Tile;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameServer extends Application {
    private static final int DEFAULT_MAP_WIDTH = 20;
    private static final int DEFAULT_MAP_HEIGHT = 20;

    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private Tile[][] map;
    private int totalGoldCollected = 0;
    private static final int OBJECTIF_OR = 10;
    private GameLoop gameLoop;
    private final List<Entity> entities = new CopyOnWriteArrayList<>();
    private int port;

    public GameServer() {
        initMap(DEFAULT_MAP_WIDTH, DEFAULT_MAP_HEIGHT);
    }

    public GameServer(int width, int height) {
        initMap(width, height);
    }

    private void initMap(int width, int height) {
        this.map = new Tile[width][height];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                map[i][j] = new Tile(Tile.EMPTY);
            }
        }
    }

    /**
     * Point d'entrée de l'application JavaFX
     * Le main ne fait que lancer JavaFX
     * @param args arguments de lancement
     */
    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Initialise la logique métier et affiche l'interface graphique.
     * JavaFX appelle automatiquement cette méthode sur le thread FX.
     */
    @Override
    public void start(Stage primaryStage) {
        // Initialisation de la logique métier
        this.port = 1234;
        this.gameLoop = new GameLoop(this);
        this.gameLoop.start();

        // Lancement de l'UI
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/index.fxml"));
            Parent root = loader.load();

            primaryStage.setTitle("DMND - Serveur");
            primaryStage.setScene(new Scene(root));
            primaryStage.setMaximized(true);
            primaryStage.setFullScreenExitHint("");
            primaryStage.show();

            // Lancer le serveur réseau dans un thread séparé (pour ne pas figer l'UI)
            startNetworkThread();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stop() {
        if (gameLoop != null) {
            gameLoop.stopLoop();
        }
    }

    /**
     * Lance le serveur réseau dans un thread séparé pour ne pas bloquer l'interface graphique
     */
    private void startNetworkThread() {
        Thread serverThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("Serveur DMND démarré sur le port " + port);

                while (true) {
                    Socket socket = serverSocket.accept(); // Attend un client sans figer l'écran
                    ClientHandler handler = new ClientHandler(socket, this);
                    clients.add(handler);
                    handler.start();
                    System.out.println("Nouveau nain connecté !");
                }
            } catch (IOException e) {
                System.err.println("Erreur Serveur : " + e.getMessage());
            }
        }, "server-network");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void broadcast(GameEvent event) {
        for (ClientHandler client : clients) {
            client.sendEvent(event);
        }
    }

    public synchronized void processMining(int x, int y, Player p) {
        if (x < 0 || y < 0 || x >= map.length || y >= map[0].length) {
            return;
        }

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
    public void addEntity(Entity e) {
        this.entities.add(e);
    }

    /**
     * Méthode pour récupérer la liste des entités de manière thread-safe
     * @return une copie de la liste des entités
     */
    public List<Entity> getEntities() {
        return new ArrayList<>(entities);
    }
}
