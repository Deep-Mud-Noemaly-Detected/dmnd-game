package network;

import java.io.Serial;
import java.io.Serializable;

public class GameEvent implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public String type;
    public int x;
    public int y;
    public String data;

    public static final String MOVE = "MOVE";
    public static final String MINE = "MINE";
    public static final String HIT = "HIT";
    public static final String UPDATE_TILE = "UPDATE_TILE";
    public static final String SPAWN_MONSTER = "SPAWN_MONSTER";
    public static final String MOVE_MONSTER = "MOVE_MONSTER";
    public static final String MONSTER_ATTACK = "MONSTER_ATTACK";
    public static final String MONSTER_KILLED = "MONSTER_KILLED";
    public static final String PLAYER_DIED = "PLAYER_DIED";
    public static final String VICTORY = "VICTORY";
    public static final String WELCOME = "WELCOME";
    public static final String PLAYER_JOINED = "PLAYER_JOINED";
    public static final String PLAYER_LEFT = "PLAYER_LEFT";
    public static final String MISSION_PROGRESS = "MISSION_PROGRESS";
    public static final String PLAYER_DAMAGED = "PLAYER_DAMAGED";

    public GameEvent(String type, int x, int y, String data) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.data = data;
    }
}