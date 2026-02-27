package entities;

import environment.Tile;

public class Player {
    private String name;
    private int orCollecte;
    private int objectifOr;

    public Player(String name, int objectifOr) {
        this.name = name;
        this.orCollecte = 0;
        this.objectifOr = objectifOr;
    }

    public String getName() {
        return name;
    }

    public void miner(Tile t) {
        // Logique de minage
    }

    public void combattre(Monster m) {
        // Logique de combat
    }
}
