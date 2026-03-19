package environment;

import java.util.Random;

public final class CaveGenerator {
    private final int width;
    private final int height;
    private final boolean[][] walls;

    private CaveGenerator(int width, int height, boolean[][] walls) {
        this.width = width;
        this.height = height;
        this.walls = walls;
    }

    public static CaveGenerator generate(int width, int height, double wallChance, int smoothIterations) {
        Random random = new Random();
        boolean[][] grid = new boolean[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean border = x == 0 || y == 0 || x == width - 1 || y == height - 1;
                grid[y][x] = border || random.nextDouble() < wallChance;
            }
        }

        for (int i = 0; i < smoothIterations; i++) {
            grid = smooth(grid, width, height);
        }

        carveSpawnArea(grid, width, height);
        return new CaveGenerator(width, height, grid);
    }

    private static boolean[][] smooth(boolean[][] source, int width, int height) {
        boolean[][] target = new boolean[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int wallNeighbors = countWallNeighbors(source, x, y, width, height);
                target[y][x] = wallNeighbors >= 5;
            }
        }

        return target;
    }

    private static int countWallNeighbors(boolean[][] grid, int cx, int cy, int width, int height) {
        int count = 0;

        for (int y = cy - 1; y <= cy + 1; y++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                if (x == cx && y == cy) {
                    continue;
                }
                if (x < 0 || y < 0 || x >= width || y >= height || grid[y][x]) {
                    count++;
                }
            }
        }

        return count;
    }

    private static void carveSpawnArea(boolean[][] grid, int width, int height) {
        int centerX = width / 2;
        int centerY = height / 2;

        for (int y = centerY - 2; y <= centerY + 2; y++) {
            for (int x = centerX - 2; x <= centerX + 2; x++) {
                if (x > 0 && y > 0 && x < width - 1 && y < height - 1) {
                    grid[y][x] = false;
                }
            }
        }
    }

    public int[] findSpawnNearCenter() {
        int centerX = width / 2;
        int centerY = height / 2;

        if (!isWall(centerX, centerY)) {
            return new int[]{centerX, centerY};
        }

        int maxRadius = Math.max(width, height);
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int y = centerY - radius; y <= centerY + radius; y++) {
                for (int x = centerX - radius; x <= centerX + radius; x++) {
                    if (x < 0 || y < 0 || x >= width || y >= height) {
                        continue;
                    }
                    if (!isWall(x, y)) {
                        return new int[]{x, y};
                    }
                }
            }
        }

        return new int[]{1, 1};
    }

    public boolean isWall(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return true;
        }
        return walls[y][x];
    }

    public boolean isWalkable(int x, int y) {
        return !isWall(x, y);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
}
