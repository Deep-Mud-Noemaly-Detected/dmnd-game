package network;

import java.io.Serializable;

public class GameEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    public String type; // "MOVE", "MINE", "ATTACK", "UPDATE"
    public int x;
    public int y;
    public String data; // Pour passer un pseudo ou une valeur supplémentaire

    public GameEvent(String type, int x, int y, String data) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.data = data;
    }
}