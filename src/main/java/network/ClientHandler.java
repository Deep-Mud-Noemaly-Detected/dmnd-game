package network;

import entities.Player;

import java.io.*;
import java.net.Socket;

public class ClientHandler extends Thread {
    private Socket socket;
    private GameServer server;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private Player player;

    public ClientHandler(Socket socket, GameServer server) {
        this.socket = socket;
        this.server = server;
        try {
            this.out = new ObjectOutputStream(socket.getOutputStream());
            this.in = new ObjectInputStream(socket.getInputStream());
            // On crée un joueur pour ce client (position de départ 1,1)
            this.player = new Player(1, 1, 100, "Player", 10);
            server.addEntity(player);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                // Lecture de l'action envoyée par le client
                GameEvent event = (GameEvent) in.readObject();
                receiveAction(event);
            }
        } catch (Exception e) {
            System.out.println("Déconnexion d'un joueur.");
        } finally {
            try { socket.close(); } catch (IOException e) { }
        }
    }

    public void receiveAction(GameEvent event) {
        if (event.type.equals("MINE")) {
            // Logique de minage : le serveur vérifie la case et donne l'or
            server.processMining(event.x, event.y, this.player);
        } else if (event.type.equals("MOVE")) {
            this.player.setX(event.x);
            this.player.setY(event.y);
            // On informe tout le monde du mouvement
            server.broadcast(event);
        }
        // Vérifier si la mission est finie après l'action
        if (server.verifierObjectif()) {
            server.broadcast(new GameEvent("VICTORY", 0, 0, "Objectif atteint !"));
        }
    }

    public void sendEvent(GameEvent event) {
        try {
            out.writeObject(event);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}