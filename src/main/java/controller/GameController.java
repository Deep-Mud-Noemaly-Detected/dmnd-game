package controller;

import environment.CaveGenerator;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;

public class GameController {
    private static final int TILE_SIZE = 24;
    private static final int DEFAULT_VIEW_WIDTH = 960;
    private static final int DEFAULT_VIEW_HEIGHT = 640;

    @FXML
    private StackPane gameContainer;

    @FXML
    private StackPane startOverlay;

    @FXML
    private Label statusLabel;

    @FXML
    private ImageView startImageView;

    @FXML
    private Spinner<Integer> clientsSpinner;

    private final Set<KeyCode> pressedKeys = new HashSet<>();

    private Canvas canvas;
    private CaveGenerator caveMap;
    private PlayerView player;
    private Image dwarfSpriteSheet;
    private Image floorTile;
    private Image wallTile;

    private boolean gameStarted;
    private boolean serverMode;
    private IntConsumer launchClientsAction;

    @FXML
    private void initialize() {
        caveMap = CaveGenerator.generate(100, 70, 0.45, 5);
        int[] spawn = caveMap.findSpawnNearCenter();
        player = new PlayerView(spawn[0] + 0.5, spawn[1] + 0.5);

        floorTile = loadImage("/tiles/tile_0000.png");
        wallTile = loadImage("/tiles/tile_0001.png");
        dwarfSpriteSheet = loadImage("/entities/miner/Walk_Base/Walk_Down-Sheet.png");

        canvas = new Canvas(DEFAULT_VIEW_WIDTH, DEFAULT_VIEW_HEIGHT);
        // Le canvas doit rester en fond; l'overlay de demarrage reste au-dessus jusqu'a Entree.
        gameContainer.getChildren().add(0, canvas);
        startOverlay.toFront();

        // Le canvas suit la taille du conteneur pour rester intégré à la fenêtre FXML.
        canvas.widthProperty().bind(gameContainer.widthProperty());
        canvas.heightProperty().bind(gameContainer.heightProperty());

        if (clientsSpinner != null) {
            clientsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 8, 2));
            clientsSpinner.setEditable(true);
            clientsSpinner.setOnKeyPressed(event -> {
                if (serverMode && event.getCode() == KeyCode.ENTER) {
                    requestLaunchClients();
                    event.consume();
                }
            });
        }

        gameContainer.setFocusTraversable(true);
        gameContainer.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                if (serverMode) {
                    requestLaunchClients();
                    return;
                }
                if (!gameStarted) {
                    startGame();
                    return;
                }
            }
            if (gameStarted && !serverMode) {
                pressedKeys.add(event.getCode());
            }
        });
        gameContainer.setOnKeyReleased(event -> pressedKeys.remove(event.getCode()));

        Platform.runLater(() -> {
            gameContainer.requestFocus();
            render(canvas.getGraphicsContext2D());
        });

        Image startImage = loadImage("/pages/dmnd-game-start.png");
        if (startImageView != null) {
            startImageView.setImage(startImage);
        }

        statusLabel.setText("Appuie sur ENTREE pour commencer");
        if (startImage == null) {
            statusLabel.setText("Image de demarrage introuvable - appuie sur ENTREE pour commencer");
        }
    }

    public void setLaunchClientsAction(IntConsumer launchClientsAction) {
        this.launchClientsAction = launchClientsAction;
        this.serverMode = launchClientsAction != null;
        if (serverMode && statusLabel != null) {
            statusLabel.setText("Serveur actif - choisis un nombre de clients puis appuie sur ENTREE");
        }
    }

    private void requestLaunchClients() {
        if (launchClientsAction == null) {
            statusLabel.setText("Mode local actif");
            return;
        }

        int count = 2;
        if (clientsSpinner != null && clientsSpinner.getValue() != null) {
            count = clientsSpinner.getValue();
        }

        launchClientsAction.accept(count);
        statusLabel.setText("Lancement de " + count + " client(s) en cours...");
    }

    private void startGame() {
        if (gameStarted) {
            return;
        }
        gameStarted = true;
        startOverlay.setVisible(false);
        startOverlay.setManaged(false);
        startGameLoop();
        statusLabel.setText("Partie lancée - Déplacement: ZQSD / WASD / Flèches");
    }

    private void startGameLoop() {
        GraphicsContext gc = canvas.getGraphicsContext2D();

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
        if (!gameStarted) {
            return;
        }

        boolean up = isDown(KeyCode.Z) || isDown(KeyCode.W) || isDown(KeyCode.UP);
        boolean down = isDown(KeyCode.S) || isDown(KeyCode.DOWN);
        boolean left = isDown(KeyCode.Q) || isDown(KeyCode.A) || isDown(KeyCode.LEFT);
        boolean right = isDown(KeyCode.D) || isDown(KeyCode.RIGHT);

        player.update(deltaSeconds, up, down, left, right, caveMap);
    }

    private void render(GraphicsContext gc) {
        double viewWidth = canvas.getWidth();
        double viewHeight = canvas.getHeight();

        gc.setFill(Color.web("#050505"));
        gc.fillRect(0, 0, viewWidth, viewHeight);

        double worldPixelWidth = caveMap.getWidth() * TILE_SIZE;
        double worldPixelHeight = caveMap.getHeight() * TILE_SIZE;

        double cameraX = clamp(player.getX() * TILE_SIZE - viewWidth / 2.0, 0, Math.max(0, worldPixelWidth - viewWidth));
        double cameraY = clamp(player.getY() * TILE_SIZE - viewHeight / 2.0, 0, Math.max(0, worldPixelHeight - viewHeight));

        drawMap(gc, cameraX, cameraY, viewWidth, viewHeight);
        drawPlayer(gc, cameraX, cameraY);
    }

    private void drawMap(GraphicsContext gc, double cameraX, double cameraY, double viewWidth, double viewHeight) {
        int startTileX = Math.max(0, (int) (cameraX / TILE_SIZE) - 1);
        int endTileX = Math.min(caveMap.getWidth() - 1, (int) ((cameraX + viewWidth) / TILE_SIZE) + 1);

        int startTileY = Math.max(0, (int) (cameraY / TILE_SIZE) - 1);
        int endTileY = Math.min(caveMap.getHeight() - 1, (int) ((cameraY + viewHeight) / TILE_SIZE) + 1);

        for (int y = startTileY; y <= endTileY; y++) {
            for (int x = startTileX; x <= endTileX; x++) {
                double drawX = x * TILE_SIZE - cameraX;
                double drawY = y * TILE_SIZE - cameraY;

                if (caveMap.isWall(x, y)) {
                    if (wallTile != null) {
                        gc.drawImage(wallTile, drawX, drawY, TILE_SIZE, TILE_SIZE);
                    } else {
                        gc.setFill(Color.web((x + y) % 2 == 0 ? "#262626" : "#202020"));
                        gc.fillRect(drawX, drawY, TILE_SIZE, TILE_SIZE);
                    }
                } else {
                    if (floorTile != null) {
                        gc.drawImage(floorTile, drawX, drawY, TILE_SIZE, TILE_SIZE);
                    } else {
                        gc.setFill(Color.web((x + y) % 2 == 0 ? "#101010" : "#141414"));
                        gc.fillRect(drawX, drawY, TILE_SIZE, TILE_SIZE);
                    }
                }
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
    }

    private boolean isDown(KeyCode code) {
        return pressedKeys.contains(code);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Image loadImage(String resourcePath) {
        try (InputStream stream = GameController.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            return new Image(stream);
        } catch (Exception ignored) {
            return null;
        }
    }
}
