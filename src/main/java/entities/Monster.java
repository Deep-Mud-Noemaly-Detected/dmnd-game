package entities;

public abstract class Monster extends Entity {
    private String typeAttaque;

    public Monster(int x, int y, int pv, String typeAttaque) {
        super(x, y, pv);
        this.typeAttaque = typeAttaque;
    }

    public void attaquer(Player p) {}
}
