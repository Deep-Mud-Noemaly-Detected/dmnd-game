package controller;

import environment.CaveGenerator;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public class GameClient extends Application {
    private static final int TILE_SIZE = 24;
    private static final int SCREEN_WIDTH = 960;
    private static final int SCREEN_HEIGHT = 640;

    private final Set<KeyCode> pressedKeys = new HashSet<>();

    private CaveGenerator caveMap;
    private PlayerView player;
    private Image dwarfSpriteSheet;

    @Override
    public void start(Stage stage) {
        caveMap = CaveGenerator.generate(100, 70, 0.45, 5);
        int[] spawn = caveMap.findSpawnNearCenter();
        player = new PlayerView(spawn[0] + 0.5, spawn[1] + 0.5);
        dwarfSpriteSheet = loadDwarfSpriteSheet();

        Canvas canvas = new Canvas(SCREEN_WIDTH, SCREEN_HEIGHT);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, SCREEN_WIDTH, SCREEN_HEIGHT);
        scene.setOnKeyPressed(event -> pressedKeys.add(event.getCode()));
        scene.setOnKeyReleased(event -> pressedKeys.remove(event.getCode()));

        stage.setTitle("DMND Game - Cave + Nain");
        stage.setScene(scene);
        stage.show();

        canvas.requestFocus();

        AnimationTimer timer = new AnimationTimer() {
            private long previousNano = -1;

            @Override
            public void handle(long now) {
                if (previousNano < 0) {
                    previousNano = now;
                    render(gc);
                    return;
                }

                double deltaSeconds = (now - previousNano) / 1_000_000_000.0;
                previousNano = now;

                update(deltaSeconds);
                render(gc);
            }
        };

        timer.start();
    }

    private void update(double deltaSeconds) {
        boolean up = isDown(KeyCode.Z) || isDown(KeyCode.W) || isDown(KeyCode.UP);
        boolean down = isDown(KeyCode.S) || isDown(KeyCode.DOWN);
        boolean left = isDown(KeyCode.Q) || isDown(KeyCode.A) || isDown(KeyCode.LEFT);
        boolean right = isDown(KeyCode.D) || isDown(KeyCode.RIGHT);

        player.update(deltaSeconds, up, down, left, right, caveMap);
    }

    private void render(GraphicsContext gc) {
        gc.setFill(Color.web("#050505"));
        gc.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        double worldPixelWidth = caveMap.getWidth() * TILE_SIZE;
        double worldPixelHeight = caveMap.getHeight() * TILE_SIZE;

        double cameraX = clamp(player.getX() * TILE_SIZE - SCREEN_WIDTH / 2.0, 0, Math.max(0, worldPixelWidth - SCREEN_WIDTH));
        double cameraY = clamp(player.getY() * TILE_SIZE - SCREEN_HEIGHT / 2.0, 0, Math.max(0, worldPixelHeight - SCREEN_HEIGHT));

        drawMap(gc, cameraX, cameraY);
        drawPlayer(gc, cameraX, cameraY);

        gc.setFill(Color.WHITE);
        gc.fillText("Deplacement: ZQSD / WASD / Fleches", 12, 20);
    }

    private void drawMap(GraphicsContext gc, double cameraX, double cameraY) {
        int startTileX = Math.max(0, (int) (cameraX / TILE_SIZE) - 1);
        int endTileX = Math.min(caveMap.getWidth() - 1, (int) ((cameraX + SCREEN_WIDTH) / TILE_SIZE) + 1);

        int startTileY = Math.max(0, (int) (cameraY / TILE_SIZE) - 1);
        int endTileY = Math.min(caveMap.getHeight() - 1, (int) ((cameraY + SCREEN_HEIGHT) / TILE_SIZE) + 1);

        for (int y = startTileY; y <= endTileY; y++) {
            for (int x = startTileX; x <= endTileX; x++) {
                double drawX = x * TILE_SIZE - cameraX;
                double drawY = y * TILE_SIZE - cameraY;

                if (caveMap.isWall(x, y)) {
                    gc.setFill(Color.web((x + y) % 2 == 0 ? "#262626" : "#202020"));
                } else {
                    gc.setFill(Color.web((x + y) % 2 == 0 ? "#101010" : "#141414"));
                }
                gc.fillRect(drawX, drawY, TILE_SIZE, TILE_SIZE);
            }
        }
    }

    private void drawPlayer(GraphicsContext gc, double cameraX, double cameraY) {
        double px = player.getX() * TILE_SIZE - cameraX;
        double py = player.getY() * TILE_SIZE - cameraY;

        if (dwarfSpriteSheet == null) {
            gc.setFill(Color.ORANGE);
            gc.fillOval(px - TILE_SIZE / 2.0, py - TILE_SIZE / 2.0, TILE_SIZE, TILE_SIZE);
            return;
        }

        int rows = 3;
        int cols = 6;

        double frameWidth = dwarfSpriteSheet.getWidth() / cols;
        double frameHeight = dwarfSpriteSheet.getHeight() / rows;

        int row = switch (player.getFacing()) {
            case DOWN -> 0;
            case LEFT, RIGHT -> 1;
            case UP -> 2;
        };
        int frame = player.isMoving() ? player.getAnimationFrame(cols) : 0;

        gc.drawImage(
                dwarfSpriteSheet,
                frame * frameWidth,
                row * frameHeight,
                frameWidth,
                frameHeight,
                px - TILE_SIZE / 2.0,
                py - TILE_SIZE / 2.0,
                TILE_SIZE,
                TILE_SIZE
        );

        if (player.getFacing() == PlayerView.Facing.LEFT) {
            gc.setStroke(Color.rgb(255, 255, 255, 0.3));
            gc.strokeLine(px - 6, py - 6, px - 10, py - 6);
        }
    }

    private Image loadDwarfSpriteSheet() {
        try (InputStream stream = GameClient.class.getResourceAsStream("/entities/miner/Walk_Base/Walk_Down-Sheet.png")) {
            if (stream == null) {
                return null;
            }
            return new Image(stream);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isDown(KeyCode code) {
        return pressedKeys.contains(code);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
