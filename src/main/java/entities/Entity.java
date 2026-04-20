package entities;

public abstract class Entity {
        private int x;
        private int y;
        private int pv;

        public Entity(int x, int y, int pv) {
            this.x = x;
            this.y = y;
            this.pv = pv;
        }

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
        }

        public int getPv() {
            return pv;
        }

        public void setPv(int pv) {
            this.pv = pv;
        }

        public boolean isAlive() {
            return this.pv > 0;
        }

        /**
         * Inflige des dégâts à l'entité.
         * @param damage quantité de dégâts
         */
        public void takeDamage(int damage) {
            this.pv = Math.max(0, this.pv - damage);
        }

        /**
         * Calcule la distance Manhattan avec une autre entité.
         * Simple et efficace pour un jeu tile-based.
         */
        public int distanceTo(Entity other) {
            return Math.abs(this.x - other.getX()) + Math.abs(this.y - other.getY());
        }

        public void update() {
            // Logique de mise à jour de l'entité
        }
}
