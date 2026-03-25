package org.example.dmndgame;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class HelloApplication extends Application {
    // --- CONFIGURATION ---
    private static final int TILE_SIZE = 48; // Zoom pour l'immersion [cite: 54, 72]
    private static final int SCREEN_WIDTH = 960;
    private static final int SCREEN_HEIGHT = 640;
    private static final double LIGHT_RADIUS = 220.0;

    private final Set<KeyCode> pressedKeys = new HashSet<>();
    private final Map<String, Image> imageCache = new HashMap<>();

    // Système de brouillard de guerre (false = noir, true = découvert)
    private boolean[][] discovered;

    private CaveMap caveMap;
    private Player player;
    private Image dwarfSpriteSheet;
    private double flickerOffset = 0;

    @Override
    public void start(Stage stage) {
        // Génération de la map (Tile-based pour la simplicité) [cite: 19, 21, 22]
        caveMap = CaveMap.generate(80, 80, 0.46, 5);
        discovered = new boolean[caveMap.getWidth()][caveMap.getHeight()];

        int[] spawn = caveMap.findSpawnNearCenter();
        player = new Player(spawn[0] + 0.5, spawn[1] + 0.5);

        loadResources();

        Canvas canvas = new Canvas(SCREEN_WIDTH, SCREEN_HEIGHT);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setImageSmoothing(false); // Rendu Pixel Art net [cite: 103]

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, SCREEN_WIDTH, SCREEN_HEIGHT);
        scene.setOnKeyPressed(e -> pressedKeys.add(e.getCode()));
        scene.setOnKeyReleased(e -> pressedKeys.remove(e.getCode()));

        stage.setTitle("DMND - Deep Cave Exploration");
        stage.setScene(scene);
        stage.show();

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

    private void loadResources() {
        // Chargement via le cache pour éviter les lags [cite: 72]
        dwarfSpriteSheet = loadImage("/entities/miner/animations/Walk_Base/Walk_Down-Sheet.png");
        // Murs (Kenney Tiny Dungeon) [cite: 18, 60]
        loadImage("/tiles/mur/tile_0001.png");
        loadImage("/tiles/mur/tile_0114.png"); // Haut
        loadImage("/tiles/mur/tile_0113.png"); // Bas
        loadImage("/tiles/mur/tile_0015.png"); // Gauche
        loadImage("/tiles/mur/tile_0013.png"); // Droite
        loadImage("/tiles/mur/tile_0004.png"); // Coin HG
        loadImage("/tiles/mur/coinhautdroit.png"); // Coin HD
        loadImage("/tiles/mur/tile_0016.png"); // Coin BG
        loadImage("/tiles/mur/tile_0017.png"); // Coin BD
        // Sols
        loadImage("/tiles/Sol/sol.png");
        loadImage("/tiles/Sol/tile_0024.png");
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

    private void update(double delta) {
        boolean up = isDown(KeyCode.Z) || isDown(KeyCode.W) || isDown(KeyCode.UP);
        boolean down = isDown(KeyCode.S) || isDown(KeyCode.DOWN);
        boolean left = isDown(KeyCode.Q) || isDown(KeyCode.A) || isDown(KeyCode.LEFT);
        boolean right = isDown(KeyCode.D) || isDown(KeyCode.RIGHT);

        player.update(delta, up, down, left, right, caveMap);

        // Mise à jour du brouillard de guerre (révèle 5 cases autour)
        int px = (int) player.getX();
        int py = (int) player.getY();
        for (int y = py - 5; y <= py + 5; y++) {
            for (int x = px - 5; x <= px + 5; x++) {
                if (x >= 0 && x < caveMap.getWidth() && y >= 0 && y < caveMap.getHeight()) {
                    discovered[x][y] = true;
                }
            }
        }

        // Effet de scintillement de la torche
        flickerOffset = Math.sin(System.currentTimeMillis() * 0.008) * 4;
    }

    private void render(GraphicsContext gc) {
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        // Calcul de la caméra avec bridage aux bords de la map
        double camX = clamp(player.getX() * TILE_SIZE - SCREEN_WIDTH/2.0, 0, caveMap.getWidth() * TILE_SIZE - SCREEN_WIDTH);
        double camY = clamp(player.getY() * TILE_SIZE - SCREEN_HEIGHT/2.0, 0, caveMap.getHeight() * TILE_SIZE - SCREEN_HEIGHT);

        drawMap(gc, camX, camY);
        drawPlayer(gc, camX, camY);
        applyLighting(gc, camX, camY);
    }

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
                    String path = ((x * 7 + y * 13) % 10 > 7) ? "/tiles/Sol/tile_0024.png" : "/tiles/Sol/sol.png";
                    gc.drawImage(imageCache.get(path), dx, dy, TILE_SIZE, TILE_SIZE);
                }
            }
        }
    }

    private Image getWallImage(int x, int y) {
        boolean u = isWallSafe(x, y - 1);
        boolean d = isWallSafe(x, y + 1);
        boolean l = isWallSafe(x - 1, y);
        boolean r = isWallSafe(x + 1, y);

        // Auto-tiling intelligent basé sur les voisins
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

    private void drawPlayer(GraphicsContext gc, double camX, double camY) {
        double px = player.getX() * TILE_SIZE - camX;
        double py = player.getY() * TILE_SIZE - camY;
        if (dwarfSpriteSheet == null) return;

        int cols = 6;
        double fw = dwarfSpriteSheet.getWidth() / cols;
        int frame = player.isMoving() ? player.getAnimationFrame(cols) : 0;

        gc.drawImage(dwarfSpriteSheet, frame * fw, 0, fw, dwarfSpriteSheet.getHeight(),
                px - TILE_SIZE/2.0, py - TILE_SIZE/2.0, TILE_SIZE, TILE_SIZE);

        Image nainFixe = imageCache.get("/entities/miner/nain/nain1.png");
        if (nainFixe != null) {
            gc.drawImage(nainFixe, px - TILE_SIZE/2.0, py - TILE_SIZE/2.0, TILE_SIZE, TILE_SIZE);
        }
    }

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
    public static void main(String[] args) { launch(args); }
}