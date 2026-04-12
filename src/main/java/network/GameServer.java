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
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Classe principale du serveur de jeu DMND.
 * Gère la logique métier, les connexions clients, et la communication réseau.
 */
public class GameServer extends Application {
    private static final int DEFAULT_MAP_WIDTH = 20;
    private static final int DEFAULT_MAP_HEIGHT = 20;
    private static final int MAX_CLIENT_WINDOWS = 8;

    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final List<GameClient> launchedClients = new CopyOnWriteArrayList<>();
    private Tile[][] map;
    private int totalGoldCollected = 0;
    private static final int OBJECTIF_OR = 15;
    private GameLoop gameLoop;
    private final List<Entity> entities = new CopyOnWriteArrayList<>();
    private int port;
    private volatile boolean acceptingClients = true;
    private final AtomicInteger playerIdCounter = new AtomicInteger(1);
    private final AtomicInteger spawnCounter = new AtomicInteger(0);
    private MediaPlayer bgMusicPlayer;

    public GameServer() {
        initMap(DEFAULT_MAP_WIDTH, DEFAULT_MAP_HEIGHT);
    }

    public GameServer(int width, int height) {
        initMap(width, height);
    }

    /**
     * Initialise la carte de jeu avec des tuiles vides.
     * @param width
     * @param height
     */
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

        // Essaye de charger l'interface depuis le FXML.
        try {
            FXMLLoader loader = new FXMLLoader(GameServer.class.getResource("/index.fxml"));
            Parent root = loader.load();
            GameController controller = loader.getController();
            controller.setLaunchClientsAction(this::launchClientWindows);

            primaryStage.setTitle("DMND - Serveur");
            primaryStage.setScene(new Scene(root, 1000, 720));
            primaryStage.show();
            startServerMusic();
        } catch (IOException e) {
            // Fallback minimal si le FXML est indisponible.
            Label title = new Label("Serveur DMND actif");
            Label details = new Label("Port: " + port + " | FXML indisponible");
            VBox root = new VBox(8, title, details);
            root.setStyle("-fx-padding: 16; -fx-background-color: #111; -fx-text-fill: white;");

            primaryStage.setTitle("DMND - Serveur");
            primaryStage.setScene(new Scene(root, 480, 120));
            primaryStage.show();
            startServerMusic();

            System.err.println("Impossible de charger index.fxml: " + e.getMessage());
        }
    }

    /**
     * Arrête le serveur, les connexions clients, la boucle de jeu, et la musique de fond.
     */
    @Override
    public void stop() {
        acceptingClients = false;
        stopServerMusic();
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

    /**
     * Lance un nombre spécifié de fenêtres clients pour tester le serveur localement.
     * @param requestedCount
     */
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

    /**
     * Démarre la musique de fond du serveur.
     */
    private void startServerMusic() {
        if (bgMusicPlayer != null) {
            return;
        }

        try {
            var musicUrl = GameServer.class.getResource("/audio/ancient_slavic_pagan_music.wav");
            if (musicUrl == null) {
                System.err.println("Musique serveur introuvable: /audio/ancient_slavic_pagan_music.wav");
                return;
            }

            Media media = new Media(musicUrl.toExternalForm());
            bgMusicPlayer = new MediaPlayer(media);
            bgMusicPlayer.setCycleCount(MediaPlayer.INDEFINITE);
            bgMusicPlayer.setVolume(0.35);
            bgMusicPlayer.setOnError(() -> System.err.println("Erreur lecture musique serveur: " + bgMusicPlayer.getError()));
            bgMusicPlayer.play();
        } catch (Exception e) {
            System.err.println("Impossible de lancer la musique serveur: " + e.getMessage());
        }
    }

    /**
     * Arrête la musique de fond du serveur et libère les ressources associées.
     */
    private void stopServerMusic() {
        if (bgMusicPlayer == null) {
            return;
        }
        try {
            bgMusicPlayer.stop();
            bgMusicPlayer.dispose();
        } catch (Exception ignored) {
        } finally {
            bgMusicPlayer = null;
        }
    }

    /**
     * Publie un événement à tous les clients connectés.
     * @param event
     */
    public void publishServerEvent(GameEvent event) {
        broadcast(event);
    }

    /**
     * Envoie un événement à tous les clients connectés. Si l'envoi échoue pour un client, il est déconnecté.
     * @param event
     */
    public void broadcast(GameEvent event) {
        for (ClientHandler client : clients) {
            boolean sent = client.sendEvent(event);
            if (!sent) {
                removeClient(client);
            }
        }
    }

    /**
     * Traite une action de minage d'or d'un joueur.
     * Si la tuile ciblée contient de l'or, le joueur gagne 4 pièces,
     * la tuile devient vide, et tous les clients sont informés du changement.
     * @param x
     * @param y
     * @param p
     */
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
            broadcastMissionProgress();
        }
    }

    /**
     * Enregistre la collecte d'un minerai d'or par un joueur. Incrémente le total, informe les clients, et vérifie si l'objectif est atteint.
     */
    public synchronized void registerMineralCollected() {
        totalGoldCollected++;
        broadcastMissionProgress();
        if (verifierObjectif()) {
            publishServerEvent(new GameEvent(GameEvent.VICTORY, 0, 0, "Objectif atteint !"));
        }
    }

    /**
     * Envoie la progression actuelle de la mission (or collecté vs objectif) à un client spécifique.
     * @param target
     */
    public synchronized void sendMissionProgressTo(ClientHandler target) {
        if (target == null) {
            return;
        }
        target.sendEvent(new GameEvent(
                GameEvent.MISSION_PROGRESS,
                totalGoldCollected,
                OBJECTIF_OR,
                ""
        ));
    }

    /**
     * Diffuse à tous les clients la progression actuelle de la mission (or collecté vs objectif).
     */
    private synchronized void broadcastMissionProgress() {
        publishServerEvent(new GameEvent(
                GameEvent.MISSION_PROGRESS,
                totalGoldCollected,
                OBJECTIF_OR,
                ""
        ));
    }

    /**
     * Demande au GameLoop de traiter une action de minage d'or d'un joueur. Si le GameLoop n'est pas actif, traite immédiatement.
     * @param x
     * @param y
     * @param p
     */
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

    /**
     * Méthode pour retirer une entité de la liste partagée
     * @param e
     */
    public void removeEntity(Entity e) {
        this.entities.remove(e);
    }

    /**
     * Méthode pour retirer un client de la liste partagée
     * @param client
     */
    public void removeClient(ClientHandler client) {
        clients.remove(client);
    }

    /**
     * Retourne la largeur de la carte de jeu.
     * @return
     */
    public int getMapWidth() {
        return map.length;
    }

    /**
     * Retourne la hauteur de la carte de jeu.
     * @return
     */
    public int getMapHeight() {
        return map[0].length;
    }

    /**
     * Retourne une copie pour éviter les problèmes de concurrence pendant l'itération.
     */
    public List<Entity> getEntities() {
        return new ArrayList<>(entities);
    }

    /**
     * Génère un nouvel ID de joueur unique en utilisant un compteur atomique pour garantir l'unicité même avec des connexions simultanées.
     * @return
     */
    public String nextPlayerId() {
        return "P" + playerIdCounter.getAndIncrement();
    }

    /**
     * Alloue une position de spawn pour un nouveau joueur.
     * Les positions sont générées autour du centre de la carte pour favoriser les interactions initiales.
     * @return
     */
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

    /**
     * Envoie les informations de tous les joueurs déjà connectés à un client cible, afin qu'il puisse les afficher correctement à son arrivée.
     * @param target
     */
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
