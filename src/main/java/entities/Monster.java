package entities;

/**
 * Classe mère des monstres.
 * Chaque monstre a une portée d'attaque et des dégâts.
 * - Orc : portée 1 (corps à corps)
 */
public abstract class Monster extends Entity {
    private String typeAttaque;
    private int degats;
    private int portee;

    public Monster(int x, int y, int pv, String typeAttaque, int degats, int portee) {
        super(x, y, pv);
        this.typeAttaque = typeAttaque;
        this.degats = degats;
        this.portee = portee;
    }

    /**
     * Attaque un joueur si celui-ci est à portée.
     * @return true si l'attaque a eu lieu
     */
    public boolean attaquer(Player p) {
        if (p == null || !p.isAlive()) {
            return false;
        }
        // Vérifie si le joueur est à portée
        if (distanceTo(p) <= portee) {
            p.takeDamage(degats);
            return true;
        }
        return false;
    }

    /**
     * Déplace le monstre d'une case vers le joueur cible.
     * Mouvement simple : une case à la fois, priorité horizontale puis verticale.
     */
    public void moveToward(Player target, int mapWidth, int mapHeight) {
        if (target == null) {
            return;
        }
        int dx = Integer.compare(target.getX(), getX());
        int dy = Integer.compare(target.getY(), getY());

        // Priorité au déplacement horizontal
        int newX = getX() + dx;
        int newY = getY() + dy;

        // Vérification des bornes de la map
        if (newX >= 0 && newX < mapWidth) {
            setX(newX);
        }
        if (newY >= 0 && newY < mapHeight) {
            setY(newY);
        }
    }

    public String getTypeAttaque() {
        return typeAttaque;
    }

    public int getDegats() {
        return degats;
    }

    public int getPortee() {
        return portee;
    }
}
