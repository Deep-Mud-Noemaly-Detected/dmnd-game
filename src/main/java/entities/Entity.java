package entities;

public abstract class Entity {
        private int x;
        private int y;
        private int pv;

        public Entity(int x, int y) {
            this.x = x;
            this.y = y;
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

        public void update() {
            // Logique de mise à jour de l'entité
        }
}
