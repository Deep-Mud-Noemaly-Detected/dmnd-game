package controller;

import environment.CaveGenerator;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import network.GameEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GameClient extends Application {
    // --- CONFIGURATION ---
    private static final int TILE_SIZE = 48;
    private static final int SCREEN_WIDTH = 960;
    private static final int SCREEN_HEIGHT = 640;
    private static final double LIGHT_RADIUS = 220.0;
    private static final String SERVER_HOST = "127.0.0.1";
    private static final int SERVER_PORT = 1234;
    private static final long REMOTE_MOVE_ANIM_NANOS = 200_000_000L;

    // --- MISSION ---
    private static final int MISSION_OBJECTIF = 15;
    private int mineraisRecoltes = 0;
    private int missionObjectif = MISSION_OBJECTIF;

    // --- HP Joueur ---
    private int playerHp = 150;
    private static final int PLAYER_MAX_HP = 150;

    // --- Minerais ---
    private int[][] mineraiHP;
    private Image[] crystalImages;

    // --- Spritesheets ---
    private Image walkDown, walkSide, walkUp;
    private Image crushDown, crushSide, crushUp;
    private Image pierceDown, pierceSide, pierceUp; // Animation d'attaque (hache)
    private Image pickaxeImg;

    // --- Spritesheets Monstres ---
    private Image orcIdle, orcRun;
    private Image skeletonIdle, skeletonRun;
    private Image hacheImg;

    // --- Cooldown attaque joueur ---
    private double attackCooldown = 0;
    private static final double ATTACK_COOLDOWN_DURATION = 0.5;

    private final Set<KeyCode> pressedKeys = new HashSet<>();
    private final Map<String, Image> imageCache = new HashMap<>();
    private final Map<String, RemotePlayer> remotePlayers = new ConcurrentHashMap<>();
    private final Map<String, MonsterClient> monsters = new ConcurrentHashMap<>();
    private int monsterIdCounter = 0;

    // Brouillard de guerre
    private boolean[][] discovered;

    private CaveGenerator caveMap;
    private PlayerView player;
    private double flickerOffset = 0;

    // Cooldown minage
    private double mineCooldown = 0;
    private static final double MINE_COOLDOWN_DURATION = 0.45;

    // Réseau
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private Thread networkReaderThread;
    private volatile String clientId;
    private volatile String statusText = "Connexion au serveur...";
    private int lastSentX = Integer.MIN_VALUE;
    private int lastSentY = Integer.MIN_VALUE;

    @Override
    public void start(Stage stage) {
        caveMap = CaveGenerator.generate(80, 80, 0.46, 5);
        discovered = new boolean[caveMap.getWidth()][caveMap.getHeight()];
        mineraiHP = new int[caveMap.getWidth()][caveMap.getHeight()];

        int[] spawn = caveMap.findSpawnNearCenter();
        player = new PlayerView(spawn[0] + 0.5, spawn[1] + 0.5);

        loadResources();
        placerMinerais();

        Canvas canvas = new Canvas(SCREEN_WIDTH, SCREEN_HEIGHT);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setImageSmoothing(false);

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, SCREEN_WIDTH, SCREEN_HEIGHT);

        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());

        scene.setOnKeyPressed(e -> {
            pressedKeys.add(e.getCode());
            if (e.getCode() == KeyCode.F11) {
                stage.setFullScreen(!stage.isFullScreen());
            }
        });
        scene.setOnKeyReleased(e -> pressedKeys.remove(e.getCode()));

        scene.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && mineCooldown <= 0) {
                tryMine();
            }
            if (e.getButton() == MouseButton.SECONDARY && attackCooldown <= 0) {
                tryAttack();
            }
        });

        stage.setTitle("DMND Game - Client multijoueur (F11 = Plein écran)");
        stage.setScene(scene);
        stage.setResizable(true); // Fenêtre redimensionnable
        stage.setMaximized(true); // Démarre en pleine fenêtre
        stage.show();

        canvas.requestFocus();
        connectToServer();

        new AnimationTimer() {
            private long lastUpdate = 0;
            @Override
            public void handle(long now) {
                if (lastUpdate == 0) { lastUpdate = now; return; }
                double delta = (now - lastUpdate) / 1_000_000_000.0;
                lastUpdate = now;
                update(delta);
                render(gc);
            }
        }.start();
    }

    @Override
    public void stop() {
        closeNetwork();
    }

    // ========== MINERAIS ==========

    private void placerMinerais() {
        Random rng = new Random(42);
        for (int y = 0; y < caveMap.getHeight(); y++) {
            for (int x = 0; x < caveMap.getWidth(); x++) {
                if (!caveMap.isWall(x, y)
                        && rng.nextDouble() < 0.06
                        && !(Math.abs(x - caveMap.getWidth() / 2) < 4
                        && Math.abs(y - caveMap.getHeight() / 2) < 4)) {
                    mineraiHP[x][y] = 5;
                }
            }
        }
    }

    private void tryMine() {
        if (tryAttackMonster()) {
            return;
        }

        int px = (int) player.getX();
        int py = (int) player.getY();
        int tx = px, ty = py;
        switch (player.getFacing()) {
            case UP    -> ty = py - 1;
            case DOWN  -> ty = py + 1;
            case LEFT  -> tx = px - 1;
            case RIGHT -> tx = px + 1;
        }
        if (tx < 0 || ty < 0 || tx >= caveMap.getWidth() || ty >= caveMap.getHeight()) return;

        if (mineraiHP[tx][ty] <= 0 && mineraiHP[px][py] > 0) {
            tx = px; ty = py;
        }

        if (mineraiHP[tx][ty] > 0) {
            mineraiHP[tx][ty]--;
            player.startCrush();
            mineCooldown = MINE_COOLDOWN_DURATION;
            boolean collected = mineraiHP[tx][ty] <= 0;
            sendEvent(new GameEvent(GameEvent.MINE, tx, ty, collected ? "COLLECTED" : ""));
        }
    }

    // ========== ATTAQUE MONSTRES ==========

    /**
     * Essaie d'attaquer un monstre proche.
     * Utilisation de l'animation Pierce (hache) au lieu de Crush (pioche) dans mon spring.
     * @return true si un monstre a été attaqué
     */
    private boolean tryAttackMonster() {
        String targetId = null;
        double minDist = 2.5;

        for (Map.Entry<String, MonsterClient> entry : monsters.entrySet()) {
            MonsterClient mob = entry.getValue();
            double dist = Math.abs(mob.x - player.getX()) + Math.abs(mob.y - player.getY());
            if (dist < minDist) {
                minDist = dist;
                targetId = entry.getKey();
            }
        }

        if (targetId != null) {
            MonsterClient target = monsters.get(targetId);
            player.startPierce();
            attackCooldown = ATTACK_COOLDOWN_DURATION;

            target.hp -= 10;
            statusText = "Attaque ! HP: " + target.hp;

            if (target.hp <= 0) {
                monsters.remove(targetId);
                statusText = "Orc éliminé !";
            }

            sendEvent(new GameEvent(GameEvent.HIT, (int) target.x, (int) target.y, target.type));
            return true;
        }
        return false;
    }

    private void tryAttack() {
        tryAttackMonster();
    }

    // ========== RÉSEAU ==========

    private void connectToServer() {
        try {
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            statusText = "Connecte a " + SERVER_HOST + ":" + SERVER_PORT;

            networkReaderThread = new Thread(this::readNetworkLoop, "client-network-reader");
            networkReaderThread.setDaemon(true);
            networkReaderThread.start();
        } catch (IOException e) {
            statusText = "Hors ligne (serveur indisponible)";
            closeNetwork();
        }
    }

    private void readNetworkLoop() {
        try {
            while (socket != null && !socket.isClosed()) {
                Object message = in.readObject();
                if (message instanceof GameEvent event) {
                    handleServerEvent(event);
                }
            }
        } catch (Exception e) {
            statusText = "Connexion serveur fermee";
        } finally {
            closeNetwork();
        }
    }

    private void handleServerEvent(GameEvent event) {
        if (event == null || event.type == null) return;
        switch (event.type) {
            case GameEvent.WELCOME -> {
                clientId = event.data;
                player.setPosition(event.x + 0.5, event.y + 0.5);
                statusText = "Bienvenue " + clientId;
            }
            case GameEvent.PLAYER_JOINED, GameEvent.MOVE -> {
                if (event.data == null || event.data.equals(clientId)) return;
                remotePlayers.compute(event.data, (id, existing) -> {
                    if (existing == null) return new RemotePlayer(event.x + 0.5, event.y + 0.5);
                    existing.updatePosition(event.x + 0.5, event.y + 0.5);
                    return existing;
                });
            }
            case GameEvent.PLAYER_LEFT -> {
                if (event.data != null) remotePlayers.remove(event.data);
            }
            case GameEvent.MISSION_PROGRESS -> {
                mineraisRecoltes = Math.max(0, event.x);
                missionObjectif = event.y > 0 ? event.y : missionObjectif;
            }
            case GameEvent.VICTORY -> statusText = event.data == null ? "Victoire" : event.data;
            // --- GESTION DES MONSTRES ---
            case GameEvent.SPAWN_MONSTER -> {
                String monsterId = "MOB_" + (++monsterIdCounter);
                String type = event.data != null ? event.data : "ORC";
                monsters.put(monsterId, new MonsterClient(event.x + 0.5, event.y + 0.5, type));
                System.out.println("[Client] Monstre spawné : " + type + " à (" + event.x + "," + event.y + ")");
            }
            case GameEvent.MOVE_MONSTER -> {
                String type = event.data != null ? event.data : "ORC";
                updateClosestMonster(event.x + 0.5, event.y + 0.5, type);
            }
            case GameEvent.MONSTER_ATTACK -> {
                if (clientId == null) {
                    System.out.println("[Client] MONSTER_ATTACK ignoré - clientId pas encore reçu");
                    return;
                }
                if (event.data != null) {
                    String[] parts = event.data.split(":");
                    String attackedPlayerId = parts[0];

                    System.out.println("[Client " + clientId + "] MONSTER_ATTACK reçu: cible=" + attackedPlayerId + " moi=" + clientId);

                    // Vérifie si c'est NOTRE joueur qui est attaqué (par ID exact)
                    if (attackedPlayerId.equals(clientId)) {
                        statusText = "Ouch ! Attaque de monstre !";
                        playerHp = Math.max(0, playerHp - 10);
                        System.out.println("[Client " + clientId + "] JE SUIS ATTAQUE ! HP: " + playerHp);
                    }
                }
            }
            case GameEvent.PLAYER_DAMAGED -> {
                // data contient "PLAYER_ID:HP_RESTANTS"
                if (event.data != null && clientId != null && event.data.startsWith(clientId)) {
                    try {
                        String[] parts = event.data.split(":");
                        if (parts.length > 1) {
                            playerHp = Integer.parseInt(parts[1]);
                        }
                    } catch (Exception ignored) {}
                }
            }
            case GameEvent.PLAYER_DIED -> {
                // Vérifie si c'est notre joueur (par nom/ID)
                if (event.data != null && clientId != null && event.data.equals(clientId)) {
                    statusText = "Vous êtes mort !";
                    playerHp = 0;
                }
            }
            case GameEvent.MONSTER_KILLED -> {
                removeMonsterAt(event.x + 0.5, event.y + 0.5, event.data);
            }
        }
    }

    /**
     * Supprime un monstre à une position donnée.
     */
    private void removeMonsterAt(double x, double y, String type) {
        monsters.entrySet().removeIf(entry -> {
            MonsterClient m = entry.getValue();
            return Math.abs(m.x - x) < 1 && Math.abs(m.y - y) < 1 && m.type.equals(type);
        });
    }

    /**
     * Met à jour la position du monstre du bon type le plus proche de la destination.
     * Simple mais efficace pour un petit nombre de monstres.
     */
    private void updateClosestMonster(double newX, double newY, String type) {
        MonsterClient closest = null;
        double minDist = Double.MAX_VALUE;
        for (MonsterClient m : monsters.values()) {
            if (!m.type.equals(type)) continue;
            double dist = Math.abs(m.x - newX) + Math.abs(m.y - newY);
            if (dist < minDist) {
                minDist = dist;
                closest = m;
            }
        }
        if (closest != null) {
            closest.setPosition(newX, newY);
        }
    }

    private void sendMoveIfChanged() {
        int tileX = Math.max(0, Math.min(caveMap.getWidth() - 1, (int) Math.round(player.getX())));
        int tileY = Math.max(0, Math.min(caveMap.getHeight() - 1, (int) Math.round(player.getY())));
        if (tileX == lastSentX && tileY == lastSentY) return;
        lastSentX = tileX;
        lastSentY = tileY;
        sendEvent(new GameEvent(GameEvent.MOVE, tileX, tileY, ""));
    }

    private void sendEvent(GameEvent event) {
        if (event == null || out == null) return;
        synchronized (this) {
            try {
                out.writeObject(event);
                out.flush();
            } catch (IOException e) {
                statusText = "Envoi impossible";
                closeNetwork();
            }
        }
    }

    private void closeNetwork() {
        if (networkReaderThread != null && networkReaderThread != Thread.currentThread()) {
            networkReaderThread.interrupt();
        }
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        Platform.runLater(() -> statusText = "Hors ligne");
    }

    // ========== CHARGEMENT RESSOURCES ==========

    private void loadResources() {
        // Animation de marche
        walkDown = loadImage("/entities/miner/animations/Walk_Base/Walk_Down-Sheet.png");
        walkSide = loadImage("/entities/miner/animations/Walk_Base/Walk_Side-Sheet.png");
        walkUp   = loadImage("/entities/miner/animations/Walk_Base/Walk_Up-Sheet.png");

        // Animation de minage (Crush = pioche)
        crushDown = loadImage("/entities/miner/animations/Crush_Base/Crush_Down-Sheet.png");
        crushSide = loadImage("/entities/miner/animations/Crush_Base/Crush_Side-Sheet.png");
        crushUp   = loadImage("/entities/miner/animations/Crush_Base/Crush_Up-Sheet.png");

        // Animation d'attaque (Pierce = hache)
        pierceDown = loadImage("/entities/miner/animations/Pierce_Base/Pierce_Down-Sheet.png");
        pierceSide = loadImage("/entities/miner/animations/Pierce_Base/Pierce_Side-Sheet.png");
        pierceUp   = loadImage("/entities/miner/animations/Pierce_Base/Pierce_Top-Sheet.png");

        pickaxeImg = loadImage("/entities/weapon/nain/marteau.png");
        hacheImg = loadImage("/entities/weapon/nain/hache.png");

        // Spritesheets Monstres
        orcIdle = loadImage("/entities/mobs/Orc/animations/Idle/Idle-Sheet.png");
        orcRun = loadImage("/entities/mobs/Orc/animations/Run/Run-Sheet.png");
        skeletonIdle = loadImage("/entities/mobs/Skeleton/animations/Idle/Idle-Sheet.png");
        skeletonRun = loadImage("/entities/mobs/Skeleton/animations/Run/Run-Sheet.png");

        // Debug
        System.out.println("[Client] Orc Idle chargé: " + (orcIdle != null));
        System.out.println("[Client] Orc Run chargé: " + (orcRun != null));
        System.out.println("[Client] Pierce Down chargé: " + (pierceDown != null));

        // Murs
        loadImage("/tiles/mur/tile_0001.png");
        loadImage("/tiles/mur/tile_0114.png");
        loadImage("/tiles/mur/tile_0113.png");
        loadImage("/tiles/mur/tile_0015.png");
        loadImage("/tiles/mur/tile_0013.png");
        loadImage("/tiles/mur/tile_0004.png");
        loadImage("/tiles/mur/coinhautdroit.png");
        loadImage("/tiles/mur/tile_0016.png");
        loadImage("/tiles/mur/tile_0017.png");
        // Sols
        loadImage("/tiles/Sol/sol.png");
        loadImage("/tiles/Sol/tile_0024.png");
        // Cristaux
        crystalImages = new Image[5];
        for (int i = 0; i < 5; i++) {
            crystalImages[i] = loadImage("/items/crystal/crystals_blue/crystal_blue" + (i + 1) + ".png");
        }
    }

    private Image loadImage(String path) {
        if (imageCache.containsKey(path)) return imageCache.get(path);
        try (InputStream s = getClass().getResourceAsStream(path)) {
            if (s != null) {
                Image img = new Image(s);
                imageCache.put(path, img);
                return img;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ========== UPDATE ==========

    private void update(double delta) {
        boolean up = isDown(KeyCode.Z) || isDown(KeyCode.W) || isDown(KeyCode.UP);
        boolean down = isDown(KeyCode.S) || isDown(KeyCode.DOWN);
        boolean left = isDown(KeyCode.Q) || isDown(KeyCode.A) || isDown(KeyCode.LEFT);
        boolean right = isDown(KeyCode.D) || isDown(KeyCode.RIGHT);

        player.update(delta, up, down, left, right, caveMap);
        player.updateCrush(delta);
        if (mineCooldown > 0) mineCooldown -= delta;
        if (attackCooldown > 0) attackCooldown -= delta;
        sendMoveIfChanged();

        // Brouillard
        int px = (int) player.getX();
        int py = (int) player.getY();
        for (int y = py - 5; y <= py + 5; y++) {
            for (int x = px - 5; x <= px + 5; x++) {
                if (x >= 0 && x < caveMap.getWidth() && y >= 0 && y < caveMap.getHeight()) {
                    discovered[x][y] = true;
                }
            }
        }
        flickerOffset = Math.sin(System.currentTimeMillis() * 0.008) * 4;
    }

    // ========== RENDER ==========

    private void render(GraphicsContext gc) {
        double screenW = gc.getCanvas().getWidth();
        double screenH = gc.getCanvas().getHeight();

        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, screenW, screenH);

        double camX = clamp(player.getX() * TILE_SIZE - screenW / 2.0,
                0, caveMap.getWidth() * TILE_SIZE - screenW);
        double camY = clamp(player.getY() * TILE_SIZE - screenH / 2.0,
                0, caveMap.getHeight() * TILE_SIZE - screenH);

        drawMap(gc, camX, camY, screenW, screenH);
        drawMonsters(gc, camX, camY);
        drawRemotePlayers(gc, camX, camY);
        drawPlayer(gc, camX, camY);
        applyLighting(gc, camX, camY, screenW, screenH);
        drawHUD(gc, screenW, screenH);
    }

    // ========== MAP (auto-tiling + minerais) ==========

    private void drawMap(GraphicsContext gc, double camX, double camY, double screenW, double screenH) {
        int startX = Math.max(0, (int) (camX / TILE_SIZE));
        int startY = Math.max(0, (int) (camY / TILE_SIZE));
        int endX = Math.min(caveMap.getWidth() - 1, (int) ((camX + screenW) / TILE_SIZE) + 1);
        int endY = Math.min(caveMap.getHeight() - 1, (int) ((camY + screenH) / TILE_SIZE) + 1);

        for (int y = startY; y <= endY; y++) {
            for (int x = startX; x <= endX; x++) {
                if (!discovered[x][y]) continue;
                double dx = x * TILE_SIZE - camX;
                double dy = y * TILE_SIZE - camY;

                if (caveMap.isWall(x, y)) {
                    Image wallImg = getWallImage(x, y);
                    if (wallImg != null) gc.drawImage(wallImg, dx, dy, TILE_SIZE, TILE_SIZE);
                } else {
                    String path = ((x * 7 + y * 13) % 10 > 7)
                            ? "/tiles/Sol/tile_0024.png" : "/tiles/Sol/sol.png";
                    Image solImg = imageCache.get(path);
                    if (solImg != null) gc.drawImage(solImg, dx, dy, TILE_SIZE, TILE_SIZE);

                    // Minerais - TAILLE QUI RÉTRÉCIT selon les HP
                    int hp = mineraiHP[x][y];
                    if (hp > 0 && crystalImages != null) {
                        Image crystalImg = crystalImages[5 - hp];
                        if (crystalImg != null) {
                            double sizeRatio = 0.4 + (hp * 0.12);
                            double cSize = TILE_SIZE * sizeRatio;
                            double off = (TILE_SIZE - cSize) / 2.0;
                            gc.drawImage(crystalImg, dx + off, dy + off, cSize, cSize);
                        }
                    }
                }
            }
        }
    }

    private Image getWallImage(int x, int y) {
        boolean u = isWallSafe(x, y - 1);
        boolean d = isWallSafe(x, y + 1);
        boolean l = isWallSafe(x - 1, y);
        boolean r = isWallSafe(x + 1, y);

        if (d && r && !u && !l) return imageCache.get("/tiles/mur/tile_0004.png");
        if (d && l && !u && !r) return imageCache.get("/tiles/mur/coinhautdroit.png");
        if (u && r && !d && !l) return imageCache.get("/tiles/mur/tile_0016.png");
        if (u && l && !d && !r) return imageCache.get("/tiles/mur/tile_0017.png");
        if (!u && l && r) return imageCache.get("/tiles/mur/tile_0114.png");
        if (!d && l && r) return imageCache.get("/tiles/mur/tile_0113.png");
        if (!l && u && d) return imageCache.get("/tiles/mur/tile_0015.png");
        if (!r && u && d) return imageCache.get("/tiles/mur/tile_0013.png");
        return imageCache.get("/tiles/mur/tile_0001.png");
    }

    private boolean isWallSafe(int x, int y) {
        if (x < 0 || y < 0 || x >= caveMap.getWidth() || y >= caveMap.getHeight()) return true;
        return caveMap.isWall(x, y);
    }

    // ========== JOUEUR (Walk + Crush + Pioche) ==========

    private void drawPlayer(GraphicsContext gc, double camX, double camY) {
        double px = player.getX() * TILE_SIZE - camX;
        double py = player.getY() * TILE_SIZE - camY;
        Image sheet;
        int frame;
        int cols = 6;

        // Priorité : Attaque (Pierce) > Minage (Crush) > Marche > Idle
        if (player.isPiercing()) {
            sheet = switch (player.getFacing()) {
                case UP -> pierceUp;
                case DOWN -> pierceDown;
                case LEFT, RIGHT -> pierceSide;
            };
            if (sheet == null) sheet = pierceDown;
            frame = player.getPierceFrame(cols);
        } else if (player.isCrushing()) {
            sheet = switch (player.getFacing()) {
                case UP -> crushUp;
                case DOWN -> crushDown;
                case LEFT, RIGHT -> crushSide;
            };
            if (sheet == null) sheet = crushDown;
            frame = player.getCrushFrame(cols);
        } else if (player.isMoving()) {
            sheet = switch (player.getFacing()) {
                case UP -> walkUp;
                case DOWN -> walkDown;
                case LEFT, RIGHT -> walkSide;
            };
            if (sheet == null) sheet = walkDown;
            frame = player.getAnimationFrame(cols);
        } else {
            sheet = walkDown;
            frame = 0;
        }

        if (sheet != null) {
            double fw = sheet.getWidth() / cols;
            double fh = sheet.getHeight();
            boolean flipH = (player.getFacing() == PlayerView.Facing.LEFT);
            gc.save();
            if (flipH) {
                gc.translate(px + TILE_SIZE / 2.0, py - TILE_SIZE / 2.0);
                gc.scale(-1, 1);
                gc.drawImage(sheet, frame * fw, 0, fw, fh, 0, 0, TILE_SIZE, TILE_SIZE);
            } else {
                gc.drawImage(sheet, frame * fw, 0, fw, fh,
                        px - TILE_SIZE / 2.0, py - TILE_SIZE / 2.0, TILE_SIZE, TILE_SIZE);
            }
            gc.restore();
        }

        // Pioche pendant le minage
        if (player.isCrushing() && pickaxeImg != null) {
            double pSize = TILE_SIZE * 0.5;
            switch (player.getFacing()) {
                case DOWN  -> gc.drawImage(pickaxeImg, px - pSize / 2, py + TILE_SIZE * 0.2, pSize, pSize);
                case UP    -> gc.drawImage(pickaxeImg, px - pSize / 2, py - TILE_SIZE * 0.7, pSize, pSize);
                case LEFT  -> gc.drawImage(pickaxeImg, px - TILE_SIZE * 0.7, py - pSize / 2, pSize, pSize);
                case RIGHT -> gc.drawImage(pickaxeImg, px + TILE_SIZE * 0.2, py - pSize / 2, pSize, pSize);
            }
        }

        // Hache pendant l'attaque
        if (player.isPiercing() && hacheImg != null) {
            double pSize = TILE_SIZE * 0.5;
            switch (player.getFacing()) {
                case DOWN  -> gc.drawImage(hacheImg, px - pSize / 2, py + TILE_SIZE * 0.2, pSize, pSize);
                case UP    -> gc.drawImage(hacheImg, px - pSize / 2, py - TILE_SIZE * 0.7, pSize, pSize);
                case LEFT  -> gc.drawImage(hacheImg, px - TILE_SIZE * 0.7, py - pSize / 2, pSize, pSize);
                case RIGHT -> gc.drawImage(hacheImg, px + TILE_SIZE * 0.2, py - pSize / 2, pSize, pSize);
            }
        }
    }

    // ========== AUTRES JOUEURS ==========

    private void drawRemotePlayers(GraphicsContext gc, double camX, double camY) {
        for (RemotePlayer other : remotePlayers.values()) {
            double px = other.getX() * TILE_SIZE - camX;
            double py = other.getY() * TILE_SIZE - camY;
            if (walkDown != null) {
                int cols = 6;
                double fw = walkDown.getWidth() / cols;
                double fh = walkDown.getHeight();
                int frame = other.isMoving() ? ((int) ((System.nanoTime() / 100_000_000L) % cols)) : 0;
                gc.drawImage(walkDown, frame * fw, 0, fw, fh,
                        px - TILE_SIZE / 2.0, py - TILE_SIZE / 2.0, TILE_SIZE, TILE_SIZE);
            } else {
                gc.setFill(Color.CORNFLOWERBLUE);
                gc.fillOval(px - TILE_SIZE / 2.0, py - TILE_SIZE / 2.0, TILE_SIZE, TILE_SIZE);
            }
        }
    }

    // ========== MONSTRES (avec spritesheets) ==========

    private void drawMonsters(GraphicsContext gc, double camX, double camY) {
        long now = System.nanoTime();

        for (MonsterClient mob : monsters.values()) {
            double mx = mob.x * TILE_SIZE - camX;
            double my = mob.y * TILE_SIZE - camY;

            // Choisir le spritesheet selon le type et l'état
            Image sheet;
            int cols = 4; // Nombre de frames dans le spritesheet

            if ("SQUELETTE".equals(mob.type)) {
                sheet = mob.isMoving() ? skeletonRun : skeletonIdle;
            } else {
                sheet = mob.isMoving() ? orcRun : orcIdle;
            }

            // Si le spritesheet existe, l'utiliser
            if (sheet != null) {
                double fw = sheet.getWidth() / cols;
                double fh = sheet.getHeight();
                int frame = (int) ((now / 150_000_000L) % cols); // Animation à ~6 FPS

                gc.drawImage(sheet, frame * fw, 0, fw, fh,
                        mx - TILE_SIZE / 2.0, my - TILE_SIZE / 2.0, TILE_SIZE, TILE_SIZE);
            } else {
                if ("SQUELETTE".equals(mob.type)) {
                    gc.setFill(Color.LIGHTGRAY);
                } else {
                    gc.setFill(Color.DARKGREEN);
                }
                double size = TILE_SIZE * 0.8;
                double offset = (TILE_SIZE - size) / 2.0;
                gc.fillOval(mx - TILE_SIZE / 2.0 + offset, my - TILE_SIZE / 2.0 + offset, size, size);
                gc.setStroke(Color.BLACK);
                gc.setLineWidth(2);
                gc.strokeOval(mx - TILE_SIZE / 2.0 + offset, my - TILE_SIZE / 2.0 + offset, size, size);
            }

            // Barre de vie au-dessus du monstre
            double barW = TILE_SIZE * 0.8;
            double barH = 6;
            double barX = mx - barW / 2.0;
            double barY = my - TILE_SIZE / 2.0 - 10;
            double hpRatio = (double) mob.hp / mob.maxHp;

            gc.setFill(Color.DARKRED);
            gc.fillRect(barX, barY, barW, barH);
            gc.setFill(Color.LIMEGREEN);
            gc.fillRect(barX, barY, barW * hpRatio, barH);
            gc.setStroke(Color.BLACK);
            gc.setLineWidth(1);
            gc.strokeRect(barX, barY, barW, barH);
        }
    }

    // ========== HUD ==========

    private void drawHUD(GraphicsContext gc, double screenW, double screenH) {
        gc.setGlobalAlpha(1.0);
        gc.setFill(Color.rgb(0, 0, 0, 0.6));
        gc.fillRoundRect(10, 8, 320, 90, 12, 12);
        if (crystalImages != null && crystalImages[0] != null) {
            gc.drawImage(crystalImages[0], 18, 14, 28, 28);
        }
        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 16));
        gc.setFill(Color.CYAN);
        gc.fillText("Minerais : " + mineraisRecoltes + " / " + missionObjectif, 54, 34);

        double barX = 54, barY = 42, barW = 220, barH = 14;
        gc.setFill(Color.rgb(40, 40, 40));
        gc.fillRoundRect(barX, barY, barW, barH, 6, 6);
        double progress = Math.min(1.0, (double) mineraisRecoltes / missionObjectif);
        gc.setFill(progress >= 1.0 ? Color.LIMEGREEN : Color.DEEPSKYBLUE);
        gc.fillRoundRect(barX, barY, barW * progress, barH, 6, 6);
        gc.setStroke(Color.rgb(100, 100, 100));
        gc.strokeRoundRect(barX, barY, barW, barH, 6, 6);

        // Barre de vie du joueur
        gc.setFill(Color.RED);
        gc.fillText("♥ " + playerHp + "/" + PLAYER_MAX_HP, 54, 72);
        double hpBarY = 78;
        gc.setFill(Color.rgb(40, 40, 40));
        gc.fillRoundRect(barX, hpBarY, barW, barH, 6, 6);
        double hpRatio = Math.min(1.0, (double) playerHp / PLAYER_MAX_HP);
        gc.setFill(hpRatio > 0.3 ? Color.LIMEGREEN : Color.RED);
        gc.fillRoundRect(barX, hpBarY, barW * hpRatio, barH, 6, 6);
        gc.setStroke(Color.rgb(100, 100, 100));
        gc.strokeRoundRect(barX, hpBarY, barW, barH, 6, 6);

        gc.setFont(Font.font("Monospaced", 12));
        gc.setFill(Color.LIGHTGRAY);
        gc.fillText("Clic G = Miner/Attaquer | ZQSD | F11 | Mobs: " + monsters.size() + " | " + statusText, 12, 112);

        if (mineraisRecoltes >= missionObjectif) {
            gc.setFill(Color.rgb(0, 0, 0, 0.7));
            gc.fillRoundRect(screenW / 2.0 - 200, screenH / 2.0 - 40, 400, 80, 20, 20);
            gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 28));
            gc.setFill(Color.GOLD);
            gc.fillText("MISSION ACCOMPLIE !", screenW / 2.0 - 170, screenH / 2.0 + 8);
        }

        // Écran de mort
        if (playerHp <= 0) {
            gc.setFill(Color.rgb(0, 0, 0, 0.8));
            gc.fillRect(0, 0, screenW, screenH);
            gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 36));
            gc.setFill(Color.DARKRED);
            gc.fillText("VOUS ÊTES MORT", screenW / 2.0 - 180, screenH / 2.0);
        }
    }

    // ========== ÉCLAIRAGE ==========

    private void applyLighting(GraphicsContext gc, double camX, double camY, double screenW, double screenH) {
        double px = player.getX() * TILE_SIZE - camX;
        double py = player.getY() * TILE_SIZE - camY;
        gc.setGlobalBlendMode(javafx.scene.effect.BlendMode.MULTIPLY);
        double radius = LIGHT_RADIUS + flickerOffset;
        RadialGradient g = new RadialGradient(0, 0, px, py, radius, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.WHITE),
                new Stop(0.4, Color.rgb(150, 150, 180, 0.7)),
                new Stop(1.0, Color.BLACK));
        gc.setFill(g);
        gc.fillRect(0, 0, screenW, screenH);
        gc.setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER);
    }

    private boolean isDown(KeyCode c) { return pressedKeys.contains(c); }
    private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    // ========== REMOTE PLAYER ==========

    private static final class RemotePlayer {
        private volatile double x, y;
        private volatile PlayerView.Facing facing = PlayerView.Facing.DOWN;
        private volatile long movingUntilNano = 0L;

        private RemotePlayer(double x, double y) { this.x = x; this.y = y; }

        private synchronized void updatePosition(double newX, double newY) {
            double dx = newX - x, dy = newY - y;
            if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 0.001)
                facing = dx < 0 ? PlayerView.Facing.LEFT : PlayerView.Facing.RIGHT;
            else if (Math.abs(dy) > 0.001)
                facing = dy < 0 ? PlayerView.Facing.UP : PlayerView.Facing.DOWN;
            x = newX; y = newY;
            movingUntilNano = System.nanoTime() + REMOTE_MOVE_ANIM_NANOS;
        }

        private double getX() { return x; }
        private double getY() { return y; }
        private PlayerView.Facing getFacing() { return facing; }
        private boolean isMoving() { return System.nanoTime() < movingUntilNano; }
    }

    // ========== MONSTER CLIENT (représentation locale des monstres) ==========

    private static final class MonsterClient {
        private volatile double x, y;
        private volatile double prevX, prevY;
        private final String type; // "ORC" ou "SQUELETTE"
        private int hp;
        private final int maxHp;
        private volatile long movingUntilNano = 0L;

        private MonsterClient(double x, double y, String type) {
            this.x = x;
            this.y = y;
            this.prevX = x;
            this.prevY = y;
            this.type = type;
            this.maxHp = "SQUELETTE".equals(type) ? 15 : 30;
            this.hp = this.maxHp;
        }

        private void setPosition(double newX, double newY) {
            if (Math.abs(newX - x) > 0.01 || Math.abs(newY - y) > 0.01) {
                prevX = x;
                prevY = y;
                movingUntilNano = System.nanoTime() + 500_000_000L; // 0.5s d'animation
            }
            x = newX;
            y = newY;
        }

        private boolean isMoving() {
            return System.nanoTime() < movingUntilNano;
        }
    }
}
