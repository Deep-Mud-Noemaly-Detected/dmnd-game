package entities;

/**
 * Squelette : monstre à distance (archer).
 * - PV : variable
 * - Dégâts : 5
 * - Portée : 3 cases (peut tirer de loin)
 */
public class Squelette extends Monster {
    private static final int DEGATS_SQUELETTE = 5;
    private static final int PORTEE_SQUELETTE = 3; // Arc, attaque à distance

    public Squelette(int x, int y, int pv) {
        super(x, y, pv, "arc", DEGATS_SQUELETTE, PORTEE_SQUELETTE);
    }
}
