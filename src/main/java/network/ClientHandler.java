package network;

import entities.Player;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientHandler extends Thread {
    private final Socket socket;
    private final GameServer server;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private final Player player;
    private volatile boolean running = true;
    private final String playerId;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ClientHandler(Socket socket, GameServer server) {
        this.socket = socket;
        this.server = server;
        this.playerId = server.nextPlayerId();

        int[] spawn = server.allocateSpawnPosition();
        this.player = new Player(spawn[0], spawn[1], 100, playerId, 10);

        try {
            this.out = new ObjectOutputStream(socket.getOutputStream());
            this.in = new ObjectInputStream(socket.getInputStream());
            server.addEntity(player);

            server.sendExistingPlayersTo(this);
            sendEvent(new GameEvent(GameEvent.WELCOME, spawn[0], spawn[1], playerId));
            server.publishServerEvent(new GameEvent(GameEvent.PLAYER_JOINED, spawn[0], spawn[1], playerId));
        } catch (IOException e) {
            running = false;
            System.err.println("Initialisation ClientHandler impossible : " + e.getMessage());
            closeConnection();
        }
    }

    @Override
    public void run() {
        try {
            while (running && !socket.isClosed()) {
                Object message = in.readObject();
                if (message instanceof GameEvent event) {
                    receiveAction(event);
                }
            }
        } catch (Exception e) {
            System.out.println("Deconnexion d'un joueur.");
        } finally {
            closeConnection();
        }
    }

    public void receiveAction(GameEvent event) {
        if (event == null) {
            return;
        }

        if (GameEvent.MINE.equals(event.type)) {
            server.requestMining(event.x, event.y, this.player);
        } else if (GameEvent.MOVE.equals(event.type)) {
            this.player.setX(event.x);
            this.player.setY(event.y);
            server.publishServerEvent(new GameEvent(GameEvent.MOVE, event.x, event.y, playerId));
        }
    }

    public synchronized boolean sendEvent(GameEvent event) {
        if (!running || out == null) {
            return false;
        }

        try {
            out.writeObject(event);
            out.flush();
            return true;
        } catch (IOException e) {
            running = false;
            closeConnection();
            return false;
        }
    }

    public void closeConnection() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        running = false;
        server.removeEntity(player);
        server.removeClient(this);
        server.publishServerEvent(new GameEvent(GameEvent.PLAYER_LEFT, player.getX(), player.getY(), playerId));

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
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    public String getPlayerId() {
        return playerId;
    }

    public int getPlayerX() {
        return player.getX();
    }

    public int getPlayerY() {
        return player.getY();
    }
}