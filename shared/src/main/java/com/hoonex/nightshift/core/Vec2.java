package com.hoonex.nightshift.core;

public final class Vec2 {
    public final double x;
    public final double z;

    public Vec2(double x, double z) {
        this.x = x;
        this.z = z;
    }

    public Vec2 add(Vec2 other) { return new Vec2(x + other.x, z + other.z); }
    public Vec2 subtract(Vec2 other) { return new Vec2(x - other.x, z - other.z); }
    public Vec2 scale(double s) { return new Vec2(x * s, z * s); }
    public double lengthSquared() { return x * x + z * z; }
    public double length() { return Math.sqrt(lengthSquared()); }
    public Vec2 normalized() {
        double len = length();
        return len < 1e-9 ? new Vec2(0.0, 0.0) : scale(1.0 / len);
    }
    public double distance(Vec2 other) { return subtract(other).length(); }
    public static Vec2 lerp(Vec2 a, Vec2 b, double t) {
        return new Vec2(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t);
    }
}
