package com.hoonex.nightshift.core;

import java.util.List;

public final class FacilityMap {
    public static final double MIN_X = -12.0;
    public static final double MAX_X = 12.0;
    public static final double MIN_Z = -18.0;
    public static final double MAX_Z = 18.0;

    public static final Vec2[] SPAWNS = {
        new Vec2(-1.8, -15.3), new Vec2(1.8, -15.3),
        new Vec2(-1.8, -12.8), new Vec2(1.8, -12.8)
    };
    public static final Vec2[] FUSES = {
        new Vec2(-8.4, -12.2), new Vec2(8.2, -5.4), new Vec2(4.9, 11.8)
    };
    public static final Vec2 KEYCARD = new Vec2(9.4, 4.8);
    public static final Vec2[] BREAKERS = {
        new Vec2(-8.3, -6.2), new Vec2(7.6, 2.1), new Vec2(-4.5, 12.2)
    };
    public static final Vec2 EXIT = new Vec2(0.0, 16.7);
    public static final Vec2 MONSTER_SPAWN = new Vec2(8.2, 13.8);
    public static final Vec2[] PATROL = {
        new Vec2(8.2, 13.8), new Vec2(-8.0, 10.0), new Vec2(-8.0, -2.0),
        new Vec2(8.0, -7.5), new Vec2(0.0, 4.0)
    };

    public static final List<Wall> WALLS = List.of(
        new Wall(-2.0, 2.0, -10.0, -4.0),
        new Wall(-10.0, -5.0, -1.0, 2.0),
        new Wall(4.0, 9.5, -2.5, 0.0),
        new Wall(-3.5, 3.5, 5.0, 8.0),
        new Wall(-10.0, -6.5, 13.5, 16.0),
        new Wall(6.2, 9.8, 8.5, 11.0)
    );

    private FacilityMap() {}

    public static boolean collides(double x, double z, double radius) {
        if (x - radius < MIN_X || x + radius > MAX_X || z - radius < MIN_Z || z + radius > MAX_Z) {
            return true;
        }
        for (Wall wall : WALLS) {
            if (wall.containsExpanded(x, z, radius)) return true;
        }
        return false;
    }

    public static Vec2 moveWithSlide(Vec2 from, Vec2 delta, double radius) {
        Vec2 full = from.add(delta);
        if (!collides(full.x, full.z, radius)) return full;
        Vec2 xOnly = new Vec2(from.x + delta.x, from.z);
        if (!collides(xOnly.x, xOnly.z, radius)) return xOnly;
        Vec2 zOnly = new Vec2(from.x, from.z + delta.z);
        if (!collides(zOnly.x, zOnly.z, radius)) return zOnly;
        return from;
    }

    public static boolean hasLineOfSight(Vec2 a, Vec2 b) {
        double distance = a.distance(b);
        int steps = Math.max(2, (int) Math.ceil(distance / 0.35));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Vec2 p = Vec2.lerp(a, b, t);
            for (Wall wall : WALLS) {
                if (wall.containsExpanded(p.x, p.z, 0.08)) return false;
            }
        }
        return true;
    }

    public static final class Wall {
        public final double minX, maxX, minZ, maxZ;
        public Wall(double minX, double maxX, double minZ, double maxZ) {
            this.minX = minX; this.maxX = maxX; this.minZ = minZ; this.maxZ = maxZ;
        }
        public boolean containsExpanded(double x, double z, double r) {
            return x >= minX - r && x <= maxX + r && z >= minZ - r && z <= maxZ + r;
        }
    }
}
