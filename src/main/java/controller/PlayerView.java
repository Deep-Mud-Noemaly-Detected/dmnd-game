package controller;

import environment.CaveGenerator;

public final class PlayerView {
    public enum Facing {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    private static final double SPEED_TILES_PER_SECOND = 5.0;
    private static final double COLLISION_RADIUS = 0.30;

    private double x;
    private double y;
    private boolean moving;
    private Facing facing = Facing.DOWN;
    private double animationTime;

    // --- État de minage (Crush = pioche) ---
    private boolean crushing = false;
    private double crushAnimTime = 0;
    private static final double CRUSH_DURATION = 0.4;

    // --- État d'attaque (Pierce = hache) ---
    private boolean piercing = false;
    private double pierceAnimTime = 0;
    private static final double PIERCE_DURATION = 0.35;

    public PlayerView(double x, double y) {
        this.x = x;
        this.y = y;
    }

    // --- Minage ---
    public void startCrush() {
        crushing = true;
        crushAnimTime = 0;
    }

    public void updateCrush(double delta) {
        if (crushing) {
            crushAnimTime += delta;
            if (crushAnimTime >= CRUSH_DURATION) {
                crushing = false;
                crushAnimTime = 0;
            }
        }
        if (piercing) {
            pierceAnimTime += delta;
            if (pierceAnimTime >= PIERCE_DURATION) {
                piercing = false;
                pierceAnimTime = 0;
            }
        }
    }

    public boolean isCrushing() { return crushing; }

    public int getCrushFrame(int frameCount) {
        return (int) (crushAnimTime / CRUSH_DURATION * frameCount) % Math.max(1, frameCount);
    }

    // --- Attaque ---
    public void startPierce() {
        piercing = true;
        pierceAnimTime = 0;
    }

    public boolean isPiercing() { return piercing; }

    public int getPierceFrame(int frameCount) {
        return (int) (pierceAnimTime / PIERCE_DURATION * frameCount) % Math.max(1, frameCount);
    }

    public void update(double deltaSeconds, boolean up, boolean down, boolean left, boolean right, CaveGenerator map) {
        double moveX = 0;
        double moveY = 0;

        if (up) {
            moveY -= 1;
        }
        if (down) {
            moveY += 1;
        }
        if (left) {
            moveX -= 1;
        }
        if (right) {
            moveX += 1;
        }

        moving = moveX != 0 || moveY != 0;
        if (moving) {
            double length = Math.hypot(moveX, moveY);
            moveX /= length;
            moveY /= length;

            if (Math.abs(moveX) > Math.abs(moveY)) {
                facing = moveX < 0 ? Facing.LEFT : Facing.RIGHT;
            } else {
                facing = moveY < 0 ? Facing.UP : Facing.DOWN;
            }

            double step = SPEED_TILES_PER_SECOND * deltaSeconds;
            tryMove(moveX * step, moveY * step, map);
            animationTime += deltaSeconds;
        } else {
            animationTime = 0;
        }
    }

    private void tryMove(double dx, double dy, CaveGenerator map) {
        double nextX = x + dx;
        double nextY = y + dy;

        if (canStand(nextX, y, map)) {
            x = nextX;
        }
        if (canStand(x, nextY, map)) {
            y = nextY;
        }
    }

    private boolean canStand(double cx, double cy, CaveGenerator map) {
        int minX = (int) Math.floor(cx - COLLISION_RADIUS);
        int maxX = (int) Math.floor(cx + COLLISION_RADIUS);
        int minY = (int) Math.floor(cy - COLLISION_RADIUS);
        int maxY = (int) Math.floor(cy + COLLISION_RADIUS);

        for (int ty = minY; ty <= maxY; ty++) {
            for (int tx = minX; tx <= maxX; tx++) {
                if (!map.isWalkable(tx, ty)) {
                    return false;
                }
            }
        }

        return true;
    }

    public int getAnimationFrame(int frameCount) {
        return (int) (animationTime * 10) % Math.max(1, frameCount);
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public boolean isMoving() {
        return moving;
    }

    public Facing getFacing() {
        return facing;
    }

    public void setPosition(double x, double y) {
        this.x = x;
        this.y = y;
    }
}
