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
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import network.GameEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GameClient extends Application {
    private static final int TILE_SIZE = 24;
    private static final int SCREEN_WIDTH = 960;
    private static final int SCREEN_HEIGHT = 640;
    private static final String SERVER_HOST = "127.0.0.1";
    private static final int SERVER_PORT = 1234;
    private static final long REMOTE_MOVE_ANIM_NANOS = 200_000_000L;

    private final Set<KeyCode> pressedKeys = new HashSet<>();
    private final Map<String, RemotePlayer> remotePlayers = new ConcurrentHashMap<>();

    private CaveGenerator caveMap;
    private PlayerView player;
    private Image dwarfSpriteSheet;

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
        caveMap = CaveGenerator.generate(100, 70, 0.45, 5);
        int[] spawn = caveMap.findSpawnNearCenter();
        player = new PlayerView(spawn[0] + 0.5, spawn[1] + 0.5);
        dwarfSpriteSheet = loadDwarfSpriteSheet();

        Canvas canvas = new Canvas(SCREEN_WIDTH, SCREEN_HEIGHT);
        GraphicsContext gc = canvas.getGraphicsContext2D();

        StackPane root = new StackPane(canvas);
        Scene scene = new Scene(root, SCREEN_WIDTH, SCREEN_HEIGHT);
        scene.setOnKeyPressed(event -> {
            pressedKeys.add(event.getCode());
            if (event.getCode() == KeyCode.SPACE) {
                sendMineEvent();
            }
        });
        scene.setOnKeyReleased(event -> pressedKeys.remove(event.getCode()));

        stage.setTitle("DMND Game - Client multijoueur");
        stage.setScene(scene);
        stage.show();

        canvas.requestFocus();

        connectToServer();

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

    @Override
    public void stop() {
        closeNetwork();
    }

    private void connectToServer() {
        try {
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            statusText = "Connecte au serveur " + SERVER_HOST + ":" + SERVER_PORT;

            networkReaderThread = new Thread(this::readNetworkLoop, "client-network-reader");
            networkReaderThread.setDaemon(true);
            networkReaderThread.start();
        } catch (IOException e) {
            statusText = "Connexion impossible: " + e.getMessage();
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
        if (event == null || event.type == null) {
            return;
        }

        switch (event.type) {
            case GameEvent.WELCOME -> {
                clientId = event.data;
                player.setPosition(event.x + 0.5, event.y + 0.5);
                statusText = "Bienvenue " + clientId + " - 2 joueurs possibles en local";
            }
            case GameEvent.PLAYER_JOINED, GameEvent.MOVE -> {
                if (event.data == null) {
                    return;
                }
                if (event.data.equals(clientId)) {
                    return;
                }
                remotePlayers.compute(event.data, (id, existing) -> {
                    if (existing == null) {
                        return new RemotePlayer(event.x + 0.5, event.y + 0.5);
                    }
                    existing.updatePosition(event.x + 0.5, event.y + 0.5);
                    return existing;
                });
            }
            case GameEvent.PLAYER_LEFT -> {
                if (event.data != null) {
                    remotePlayers.remove(event.data);
                }
            }
            case GameEvent.VICTORY -> statusText = event.data == null ? "Victoire" : event.data;
            default -> {
                // Ignore pour le client MVP (tuiles/monstres reseau peuvent etre ajoutes ensuite).
            }
        }
    }

    private void update(double deltaSeconds) {
        boolean up = isDown(KeyCode.Z) || isDown(KeyCode.W) || isDown(KeyCode.UP);
        boolean down = isDown(KeyCode.S) || isDown(KeyCode.DOWN);
        boolean left = isDown(KeyCode.Q) || isDown(KeyCode.A) || isDown(KeyCode.LEFT);
        boolean right = isDown(KeyCode.D) || isDown(KeyCode.RIGHT);

        player.update(deltaSeconds, up, down, left, right, caveMap);
        sendMoveIfChanged();
    }

    private void sendMoveIfChanged() {
        int tileX = clampToTile((int) Math.round(player.getX()), caveMap.getWidth());
        int tileY = clampToTile((int) Math.round(player.getY()), caveMap.getHeight());

        if (tileX == lastSentX && tileY == lastSentY) {
            return;
        }

        lastSentX = tileX;
        lastSentY = tileY;
        sendEvent(new GameEvent(GameEvent.MOVE, tileX, tileY, ""));
    }

    private void sendMineEvent() {
        int tileX = clampToTile((int) Math.round(player.getX()), caveMap.getWidth());
        int tileY = clampToTile((int) Math.round(player.getY()), caveMap.getHeight());
        sendEvent(new GameEvent(GameEvent.MINE, tileX, tileY, ""));
    }

    private void sendEvent(GameEvent event) {
        if (event == null || out == null) {
            return;
        }

        synchronized (this) {
            try {
                out.writeObject(event);
                out.flush();
            } catch (IOException e) {
                statusText = "Envoi impossible: " + e.getMessage();
                closeNetwork();
            }
        }
    }

    private void render(GraphicsContext gc) {
        gc.setFill(Color.web("#050505"));
        gc.fillRect(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        double worldPixelWidth = caveMap.getWidth() * TILE_SIZE;
        double worldPixelHeight = caveMap.getHeight() * TILE_SIZE;

        double cameraX = clamp(player.getX() * TILE_SIZE - SCREEN_WIDTH / 2.0, 0, Math.max(0, worldPixelWidth - SCREEN_WIDTH));
        double cameraY = clamp(player.getY() * TILE_SIZE - SCREEN_HEIGHT / 2.0, 0, Math.max(0, worldPixelHeight - SCREEN_HEIGHT));

        drawMap(gc, cameraX, cameraY);
        drawRemotePlayers(gc, cameraX, cameraY);
        drawPlayer(gc, cameraX, cameraY);

        gc.setFill(Color.WHITE);
        gc.fillText("Deplacement: ZQSD / WASD / Fleches | Miner: Clic gauche", 12, 20);
        gc.fillText(statusText, 12, 38);
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

    private void drawRemotePlayers(GraphicsContext gc, double cameraX, double cameraY) {
        if (dwarfSpriteSheet == null) {
            gc.setFill(Color.CORNFLOWERBLUE);
            for (RemotePlayer other : remotePlayers.values()) {
                double px = other.getX() * TILE_SIZE - cameraX;
                double py = other.getY() * TILE_SIZE - cameraY;
                gc.fillOval(px - TILE_SIZE / 2.0, py - TILE_SIZE / 2.0, TILE_SIZE, TILE_SIZE);
            }
            return;
        }

        for (RemotePlayer other : remotePlayers.values()) {
            drawCharacterSprite(gc, other.getX(), other.getY(), other.getFacing(), other.isMoving(), cameraX, cameraY);
        }
    }

    private void drawPlayer(GraphicsContext gc, double cameraX, double cameraY) {
        if (dwarfSpriteSheet == null) {
            double px = player.getX() * TILE_SIZE - cameraX;
            double py = player.getY() * TILE_SIZE - cameraY;
            gc.setFill(Color.ORANGE);
            gc.fillOval(px - TILE_SIZE / 2.0, py - TILE_SIZE / 2.0, TILE_SIZE, TILE_SIZE);
            return;
        }

        drawCharacterSprite(gc, player.getX(), player.getY(), player.getFacing(), player.isMoving(), cameraX, cameraY);
    }

    private void drawCharacterSprite(
            GraphicsContext gc,
            double entityX,
            double entityY,
            PlayerView.Facing facing,
            boolean moving,
            double cameraX,
            double cameraY
    ) {
        double px = entityX * TILE_SIZE - cameraX;
        double py = entityY * TILE_SIZE - cameraY;

        int rows = 3;
        int cols = 6;

        double frameWidth = dwarfSpriteSheet.getWidth() / cols;
        double frameHeight = dwarfSpriteSheet.getHeight() / rows;

        int row = switch (facing) {
            case DOWN -> 0;
            case LEFT, RIGHT -> 1;
            case UP -> 2;
        };
        int frame = moving ? ((int) ((System.nanoTime() / 100_000_000L) % cols)) : 0;

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

    private int clampToTile(int value, int mapSize) {
        return Math.max(0, Math.min(mapSize - 1, value));
    }

    private void closeNetwork() {
        if (networkReaderThread != null && networkReaderThread != Thread.currentThread()) {
            networkReaderThread.interrupt();
        }

        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }

        Platform.runLater(() -> statusText = "Hors ligne");
    }

    private static final class RemotePlayer {
        private volatile double x;
        private volatile double y;
        private volatile PlayerView.Facing facing = PlayerView.Facing.DOWN;
        private volatile long movingUntilNano = 0L;

        private RemotePlayer(double x, double y) {
            this.x = x;
            this.y = y;
        }

        private synchronized void updatePosition(double newX, double newY) {
            double dx = newX - x;
            double dy = newY - y;

            if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 0.001) {
                facing = dx < 0 ? PlayerView.Facing.LEFT : PlayerView.Facing.RIGHT;
            } else if (Math.abs(dy) > 0.001) {
                facing = dy < 0 ? PlayerView.Facing.UP : PlayerView.Facing.DOWN;
            }

            x = newX;
            y = newY;
            movingUntilNano = System.nanoTime() + REMOTE_MOVE_ANIM_NANOS;
        }

        private double getX() {
            return x;
        }

        private double getY() {
            return y;
        }

        private PlayerView.Facing getFacing() {
            return facing;
        }

        private boolean isMoving() {
            return System.nanoTime() < movingUntilNano;
        }
    }
}
