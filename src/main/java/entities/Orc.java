package entities;

/**
 * Orc : monstre de corps à corps.
 * - PV : variable
 * - Dégâts : 10
 * - Portée : 1 case (adjacent uniquement)
 */
public class Orc extends Monster {
    private static final int DEGATS_ORC = 10;
    private static final int PORTEE_ORC = 1; // Corps à corps

    public Orc(int x, int y, int pv) {
        super(x, y, pv, "poignard", DEGATS_ORC, PORTEE_ORC);
    }
}
