package entities;

public abstract class Monster {
    private String typeAttaque;

    public Monster(String typeAttaque) {
        this.typeAttaque = typeAttaque;
    }

    public void attaquer(Player p) {}
}
