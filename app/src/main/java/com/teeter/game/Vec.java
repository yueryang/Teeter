package com.teeter.game;

public class Vec {
    public int x;
    public int y;

    public Vec() { this(0, 0); }

    public Vec(int x, int y) { this.x = x; this.y = y; }

    public void set(int x, int y) { this.x = x; this.y = y; }

    public int length() {
        return (int) Math.sqrt((double) x * x + (double) y * y);
    }

    public int dot(Vec o) { return x * o.x + y * o.y; }

    public Vec mul(float t) { return new Vec((int) (x * t), (int) (y * t)); }

    public void decrease(int friction) {
        int len = length();
        if (len == 0) return;
        x -= (x * friction) / len;
        y -= (y * friction) / len;
    }
}
