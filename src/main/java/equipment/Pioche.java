package equipment;

/**
 * Pioche : outil pour miner les minerais.
 * Utilise l'animation Crush_Base.
 */
public class Pioche implements Item {
    private final String nom;
    private final int puissance;

    public Pioche() {
        this.nom = "Pioche de mineur";
        this.puissance = 1; // Dégâts par coup sur les minerais
    }

    public Pioche(String nom, int puissance) {
        this.nom = nom;
        this.puissance = puissance;
    }

    @Override
    public String getNom() {
        return nom;
    }

    @Override
    public int getPuissance() {
        return puissance;
    }
}
