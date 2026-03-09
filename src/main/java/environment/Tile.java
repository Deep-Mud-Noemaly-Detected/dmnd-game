package environment;

public class Tile {
    public static final int EMPTY = 0;
    public static final int GOLD = 1;
    public static final int ROCK = 2;

    private int type;
    private boolean isSolid;
    private int goldValue;

    public Tile(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }
}
