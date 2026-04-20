package equipment;

/**
 * Hache : arme pour attaquer les monstres.
 * Utilise l'animation Pierce_Base.
 */
public class Hache implements Item {
    private final String nom;
    private final int degats;

    public Hache() {
        this.nom = "Hache de combat";
        this.degats = 10; // Dégâts par coup
    }

    public Hache(String nom, int degats) {
        this.nom = nom;
        this.degats = degats;
    }

    @Override
    public String getNom() {
        return nom;
    }

    @Override
    public int getPuissance() {
        return degats;
    }

    public int getDegats() {
        return degats;
    }
}
