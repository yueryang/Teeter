package com.teeter.game;

/** 3D point/vector helpers for the facet (tilted-plane) levels. */
final class Geometry {
    private Geometry() {}

    static final class P3 {
        final int x, y, z;
        P3(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    static final class V3 {
        int x, y, z;
        V3(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        V3 cross(V3 v) {
            return new V3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
        }
        int dot(V3 v) { return x * v.x + y * v.y + z * v.z; }
        double length() { return Math.sqrt((double) x * x + (double) y * y + (double) z * z); }
    }

    static final class Triangle {
        final P3 a, b, c;
        private final int acx, acy, abx, aby;
        private final double dot00, dot01, dot11, invDenom;

        Triangle(P3 a, P3 b, P3 c) {
            this.a = a; this.b = b; this.c = c;
            acx = c.x - a.x; acy = c.y - a.y;
            abx = b.x - a.x; aby = b.y - a.y;
            dot00 = (double) acx * acx + (double) acy * acy;
            dot01 = (double) acx * abx + (double) acy * aby;
            dot11 = (double) abx * abx + (double) aby * aby;
            invDenom = 1.0 / (dot00 * dot11 - dot01 * dot01);
        }

        boolean contains(int px, int py) {
            int apx = px - a.x, apy = py - a.y;
            double dot02 = (double) acx * apx + (double) acy * apy;
            double dot12 = (double) abx * apx + (double) aby * apy;
            double u = (dot11 * dot02 - dot01 * dot12) * invDenom;
            double v = (dot00 * dot12 - dot01 * dot02) * invDenom;
            return u > 0.0 && v > 0.0 && u + v < 1.0;
        }
    }

    static float distance(int x1, int y1, int x2, int y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
