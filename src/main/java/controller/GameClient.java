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

    // --- Minerais ---
    private int[][] mineraiHP;
    private Image[] crystalImages;

    // --- Spritesheets ---
    private Image walkDown, walkSide, walkUp;
    private Image crushDown, crushSide, crushUp;
    private Image pickaxeImg;

    private final Set<KeyCode> pressedKeys = new HashSet<>();
    private final Map<String, Image> imageCache = new HashMap<>();
    private final Map<String, RemotePlayer> remotePlayers = new ConcurrentHashMap<>();

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
        scene.setOnKeyPressed(e -> pressedKeys.add(e.getCode()));
        scene.setOnKeyReleased(e -> pressedKeys.remove(e.getCode()));

        scene.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && mineCooldown <= 0) {
                tryMine();
            }
        });

        stage.setTitle("DMND Game - Client multijoueur");
        stage.setScene(scene);
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

        // Fallback : mine sous le joueur si rien devant
        if (mineraiHP[tx][ty] <= 0 && mineraiHP[px][py] > 0) {
            tx = px; ty = py;
        }

        if (mineraiHP[tx][ty] > 0) {
            mineraiHP[tx][ty]--;
            player.startCrush();
            mineCooldown = MINE_COOLDOWN_DURATION;
            if (mineraiHP[tx][ty] <= 0) {
                mineraisRecoltes++;
            }
            // Envoyer l'action de minage au serveur
            sendEvent(new GameEvent(GameEvent.MINE, tx, ty, ""));
        }
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
            case GameEvent.VICTORY -> statusText = event.data == null ? "Victoire" : event.data;
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
        walkDown = loadImage("/entities/miner/animations/Walk_Base/Walk_Down-Sheet.png");
        walkSide = loadImage("/entities/miner/animations/Walk_Base/Walk_Side-Sheet.png");
        walkUp   = loadImage("/entities/miner/animations/Walk_Base/Walk_Up-Sheet.png");
        crushDown = loadImage("/entities/miner/animations/Crush_Base/Crush_Down-Sheet.png");
        crushSide = loadImage("/entities/miner/animations/Crush_Base/Crush_Side-Sheet.png");
        crushUp   = loadImage("/entities/miner/animations/Crush_Base/Crush_Up-Sheet.png");
        pickaxeImg = loadImage("/entities/weapon/nain/marteau.png");
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
        sendMoveIfChanged();

        // Brouillard de guerre
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
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        double camX = clamp(player.getX() * TILE_SIZE - SCREEN_WIDTH / 2.0,
                0, caveMap.getWidth() * TILE_SIZE - SCREEN_WIDTH);
        double camY = clamp(player.getY() * TILE_SIZE - SCREEN_HEIGHT / 2.0,
                0, caveMap.getHeight() * TILE_SIZE - SCREEN_HEIGHT);

        drawMap(gc, camX, camY);
        drawRemotePlayers(gc, camX, camY);
        drawPlayer(gc, camX, camY);
        applyLighting(gc, camX, camY);
        drawHUD(gc);
    }

    // ========== MAP (auto-tiling + minerais) ==========

    private void drawMap(GraphicsContext gc, double camX, double camY) {
        int startX = Math.max(0, (int) (camX / TILE_SIZE));
        int startY = Math.max(0, (int) (camY / TILE_SIZE));
        int endX = Math.min(caveMap.getWidth() - 1, (int) ((camX + SCREEN_WIDTH) / TILE_SIZE));
        int endY = Math.min(caveMap.getHeight() - 1, (int) ((camY + SCREEN_HEIGHT) / TILE_SIZE));

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

                    // Minerais
                    int hp = mineraiHP[x][y];
                    if (hp > 0 && crystalImages != null) {
                        Image crystalImg = crystalImages[5 - hp];
                        if (crystalImg != null) {
                            double cSize = TILE_SIZE * 0.7;
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

        if (player.isCrushing()) {
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

    // ========== HUD ==========

    private void drawHUD(GraphicsContext gc) {
        gc.setGlobalAlpha(1.0);
        gc.setFill(Color.rgb(0, 0, 0, 0.6));
        gc.fillRoundRect(10, 8, 320, 60, 12, 12);
        if (crystalImages != null && crystalImages[0] != null) {
            gc.drawImage(crystalImages[0], 18, 14, 28, 28);
        }
        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 16));
        gc.setFill(Color.CYAN);
        gc.fillText("Minerais : " + mineraisRecoltes + " / " + MISSION_OBJECTIF, 54, 34);

        double barX = 54, barY = 42, barW = 220, barH = 14;
        gc.setFill(Color.rgb(40, 40, 40));
        gc.fillRoundRect(barX, barY, barW, barH, 6, 6);
        double progress = Math.min(1.0, (double) mineraisRecoltes / MISSION_OBJECTIF);
        gc.setFill(progress >= 1.0 ? Color.LIMEGREEN : Color.DEEPSKYBLUE);
        gc.fillRoundRect(barX, barY, barW * progress, barH, 6, 6);
        gc.setStroke(Color.rgb(100, 100, 100));
        gc.strokeRoundRect(barX, barY, barW, barH, 6, 6);

        gc.setFont(Font.font("Monospaced", 12));
        gc.setFill(Color.LIGHTGRAY);
        gc.fillText("Clic gauche = Miner | ZQSD = Bouger | " + statusText, 12, 84);

        if (mineraisRecoltes >= MISSION_OBJECTIF) {
            gc.setFill(Color.rgb(0, 0, 0, 0.7));
            gc.fillRoundRect(SCREEN_WIDTH / 2.0 - 200, SCREEN_HEIGHT / 2.0 - 40, 400, 80, 20, 20);
            gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 28));
            gc.setFill(Color.GOLD);
            gc.fillText("MISSION ACCOMPLIE !", SCREEN_WIDTH / 2.0 - 170, SCREEN_HEIGHT / 2.0 + 8);
        }
    }

    // ========== ÉCLAIRAGE ==========

    private void applyLighting(GraphicsContext gc, double camX, double camY) {
        double px = player.getX() * TILE_SIZE - camX;
        double py = player.getY() * TILE_SIZE - camY;
        gc.setGlobalBlendMode(javafx.scene.effect.BlendMode.MULTIPLY);
        double radius = LIGHT_RADIUS + flickerOffset;
        RadialGradient g = new RadialGradient(0, 0, px, py, radius, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.WHITE),
                new Stop(0.4, Color.rgb(150, 150, 180, 0.7)),
                new Stop(1.0, Color.BLACK));
        gc.setFill(g);
        gc.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
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
}
