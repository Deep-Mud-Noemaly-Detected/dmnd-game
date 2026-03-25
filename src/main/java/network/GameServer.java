package network;

import controller.GameClient;
import controller.GameController;
import entities.Entity;
import entities.Player;
import environment.Tile;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class GameServer extends Application {
    private static final int DEFAULT_MAP_WIDTH = 20;
    private static final int DEFAULT_MAP_HEIGHT = 20;
    private static final int MAX_CLIENT_WINDOWS = 8;

    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final List<GameClient> launchedClients = new CopyOnWriteArrayList<>();
    private Tile[][] map;
    private int totalGoldCollected = 0;
    private static final int OBJECTIF_OR = 10;
    private GameLoop gameLoop;
    private final List<Entity> entities = new CopyOnWriteArrayList<>();
    private int port;
    private volatile boolean acceptingClients = true;
    private final AtomicInteger playerIdCounter = new AtomicInteger(1);
    private final AtomicInteger spawnCounter = new AtomicInteger(0);

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
        this.port = 1234;
        this.gameLoop = new GameLoop(this);
        this.gameLoop.start();
        startNetworkThread();

        try {
            FXMLLoader loader = new FXMLLoader(GameServer.class.getResource("/index.fxml"));
            Parent root = loader.load();
            GameController controller = loader.getController();
            controller.setLaunchClientsAction(this::launchClientWindows);

            primaryStage.setTitle("DMND - Serveur");
            primaryStage.setScene(new Scene(root, 1000, 720));
            primaryStage.show();
        } catch (IOException e) {
            // Fallback minimal si le FXML est indisponible.
            Label title = new Label("Serveur DMND actif");
            Label details = new Label("Port: " + port + " | FXML indisponible");
            VBox root = new VBox(8, title, details);
            root.setStyle("-fx-padding: 16; -fx-background-color: #111; -fx-text-fill: white;");

            primaryStage.setTitle("DMND - Serveur");
            primaryStage.setScene(new Scene(root, 480, 120));
            primaryStage.show();

            System.err.println("Impossible de charger index.fxml: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        acceptingClients = false;
        if (gameLoop != null) {
            gameLoop.stopLoop();
        }
        for (ClientHandler client : clients) {
            client.closeConnection();
        }
        for (GameClient launchedClient : launchedClients) {
            try {
                launchedClient.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private void launchClientWindows(int requestedCount) {
        int count = Math.max(1, Math.min(MAX_CLIENT_WINDOWS, requestedCount));

        for (int i = 0; i < count; i++) {
            try {
                GameClient clientApp = new GameClient();
                Stage clientStage = new Stage();
                clientApp.start(clientStage);
                clientStage.setTitle("DMND Game - Client " + (launchedClients.size() + 1));
                clientStage.setX(80 + (launchedClients.size() * 30.0));
                clientStage.setY(80 + (launchedClients.size() * 30.0));
                launchedClients.add(clientApp);

                clientStage.setOnHidden(event -> {
                    try {
                        clientApp.stop();
                    } catch (Exception ignored) {
                    }
                    launchedClients.remove(clientApp);
                });
            } catch (Exception e) {
                System.err.println("Impossible de lancer un client: " + e.getMessage());
            }
        }
    }

    /**
     * Lance le serveur réseau dans un thread séparé pour ne pas bloquer l'interface graphique
     */
    private void startNetworkThread() {
        Thread serverThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("Serveur DMND demarre sur le port " + port);

                while (acceptingClients) {
                    Socket socket = serverSocket.accept(); // Attend un client sans figer l'ecran
                    ClientHandler handler = new ClientHandler(socket, this);
                    clients.add(handler);
                    handler.start();
                    System.out.println("Nouveau nain connecte !");
                }
            } catch (IOException e) {
                if (acceptingClients) {
                    System.err.println("Erreur Serveur : " + e.getMessage());
                }
            }
        }, "server-network");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void publishServerEvent(GameEvent event) {
        broadcast(event);
    }

    public void broadcast(GameEvent event) {
        for (ClientHandler client : clients) {
            boolean sent = client.sendEvent(event);
            if (!sent) {
                removeClient(client);
            }
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
            broadcast(new GameEvent(GameEvent.UPDATE_TILE, x, y, "EMPTY"));
        }
    }

    public void requestMining(int x, int y, Player p) {
        if (gameLoop != null) {
            gameLoop.requestMining(p, x, y);
            return;
        }
        processMining(x, y, p);
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

    public void removeEntity(Entity e) {
        this.entities.remove(e);
    }

    public void removeClient(ClientHandler client) {
        clients.remove(client);
    }

    public int getMapWidth() {
        return map.length;
    }

    public int getMapHeight() {
        return map[0].length;
    }

    /**
     * Retourne une copie pour eviter les problemes de concurrence pendant l'iteration.
     */
    public List<Entity> getEntities() {
        return new ArrayList<>(entities);
    }

    public String nextPlayerId() {
        return "P" + playerIdCounter.getAndIncrement();
    }

    public int[] allocateSpawnPosition() {
        int centerX = Math.max(2, getMapWidth() / 2);
        int centerY = Math.max(2, getMapHeight() / 2);

        // Spawns proches et jouables pour les premiers joueurs (serveur 1-2 clients local).
        int[][] spawnOffsets = {
                {0, 0},
                {1, 0},
                {-1, 0},
                {0, 1},
                {0, -1},
                {2, 0},
                {-2, 0},
                {0, 2}
        };

        int idx = spawnCounter.getAndIncrement() % spawnOffsets.length;
        int x = centerX + spawnOffsets[idx][0];
        int y = centerY + spawnOffsets[idx][1];

        x = Math.max(1, Math.min(getMapWidth() - 2, x));
        y = Math.max(1, Math.min(getMapHeight() - 2, y));
        return new int[]{x, y};
    }

    public void sendExistingPlayersTo(ClientHandler target) {
        for (ClientHandler client : clients) {
            target.sendEvent(new GameEvent(
                    GameEvent.PLAYER_JOINED,
                    client.getPlayerX(),
                    client.getPlayerY(),
                    client.getPlayerId()
            ));
        }
    }
}
