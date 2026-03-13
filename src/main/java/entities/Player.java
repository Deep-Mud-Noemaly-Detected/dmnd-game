package entities;

import environment.Tile;

public class Player extends Entity {
    private String name;
    private int orCollecte;
    private int objectifOr;

    public Player(int x, int y, int pv, String name, int objectifOr) {
        super(x, y, pv);
        this.name = name;
        this.orCollecte = 0;
        this.objectifOr = objectifOr;
    }

    public String getName() {
        return name;
    }

    public int getOrCollecte() {
        return orCollecte;
    }

    public void miner(Tile t) {
        if (t.getType() == Tile.GOLD) {
            orCollecte += 4;
        }
    }

    public void combattre(Monster m) {
        // Logique de combat
    }

    public boolean hasReachedObjectif() {
        return orCollecte >= objectifOr;
    }
}
