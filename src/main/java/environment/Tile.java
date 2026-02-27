package environment;

public class Tile {
    private int type;
    private boolean isSolid;
    private int goldValue;
    private int ROCK;
    private int GOLD;

    public Tile(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }
}
