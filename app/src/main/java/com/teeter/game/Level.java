package com.teeter.game;

import android.content.res.AssetManager;
import android.graphics.Point;
import android.graphics.Rect;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Holds the geometry of the currently-loaded level. Coordinates are in logical
 * (1280x720) screen space; walls_big are in fixed-point (<< SCALE_BIT) space for
 * the integer physics.
 */
public final class Level {
    public Point begin = new Point();
    public Point end = null;
    public Rect[] walls = new Rect[0];
    public Rect[] wallsBig = new Rect[0];
    public Point[] holes = new Point[0];
    public boolean isFacet = false;

    public int holeIndex = -1;

    private Geometry.Triangle[] diamondZone;
    private Vec[] diamondAcceleration;

    static Rect modifyRect(Rect s) {
        return new Rect(Config.s2b(s.left), Config.s2b(s.top), Config.s2b(s.right), Config.s2b(s.bottom));
    }

    public boolean isAtHole(Point posBig) {
        int bigRadius = (int) (Config.s2b(Config.HOLE_RADIUS) * 0.8);
        for (int i = 0; holes != null && i < holes.length; i++) {
            float d = Geometry.distance(Config.s2b(holes[i].x), Config.s2b(holes[i].y), posBig.x, posBig.y);
            if (d > 0f && d < bigRadius) { holeIndex = i; return true; }
        }
        return false;
    }

    public boolean isAtEnd(Point posBig) {
        if (end == null) return false;
        int bigRadius = (int) (Config.s2b(Config.END_RADIUS) * 0.8);
        float d = Geometry.distance(Config.s2b(end.x), Config.s2b(end.y), posBig.x, posBig.y);
        return d > 0f && d < bigRadius;
    }

    public int atZone(int px, int py, int vx, int vy) {
        for (int i = 0; i < diamondZone.length; i++) {
            if (diamondZone[i].contains(px, py)) return i;
        }
        int vx2 = Config.b2s(vx);
        int vy2 = Config.b2s(vy);
        if (vx2 == 0) vx2 = 5;
        if (vy2 == 0) vy2 = 5;
        int px2 = Math.max(0, px + vx2);
        int py2 = Math.max(0, py + vy2);
        return atZone(px2, py2, vx2, vy2);
    }

    public Vec getDiamondAcceleration(int zone) { return diamondAcceleration[zone]; }
    public boolean isDiamondNull() { return diamondAcceleration == null; }

    public void parse(AssetManager am, int level) {
        reset();
        String file = String.format("levels/level%03d.xml", level);
        try (InputStream is = am.open(file)) {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            XmlPullParser p = f.newPullParser();
            p.setInput(is, "UTF-8");
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = p.getName();
                    switch (name) {
                        case "begin":
                            begin.set(attr(p, "x"), attr(p, "y"));
                            break;
                        case "end":
                            end = new Point(attr(p, "x"), attr(p, "y"));
                            break;
                        case "wall": {
                            Rect r = new Rect(attr(p, "left"), attr(p, "top"), attr(p, "right"), attr(p, "bottom"));
                            wallList.add(r);
                            break;
                        }
                        case "hole":
                            holeList.add(new Point(attr(p, "x"), attr(p, "y")));
                            break;
                        case "background":
                            isFacet = attr(p, "no") == 0;
                            break;
                        default: break;
                    }
                }
                ev = p.next();
            }
            walls = wallList.toArray(new Rect[0]);
            wallsBig = new Rect[walls.length];
            for (int i = 0; i < walls.length; i++) wallsBig[i] = modifyRect(walls[i]);
            holes = holeList.toArray(new Point[0]);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse " + file, e);
        }

        if (isFacet && isDiamondNull()) {
            initAcceleration(am);
        }
    }

    private final ArrayList<Rect> wallList = new ArrayList<>();
    private final ArrayList<Point> holeList = new ArrayList<>();

    private void reset() {
        begin.set(0, 0);
        end = null;
        walls = new Rect[0];
        wallsBig = new Rect[0];
        holes = new Point[0];
        isFacet = false;
        holeIndex = -1;
        wallList.clear();
        holeList.clear();
    }

    /**
     * Compute per-zone in-plane acceleration for facet levels from the 11
     * surface-normal samples (arrays a..k).
     */
    private void initAcceleration(AssetManager am) {
        Geometry.P3[] ak = loadNormals(am);
        diamondZone = new Geometry.Triangle[]{
            new Geometry.Triangle(ak[3], ak[10], ak[5]),
            new Geometry.Triangle(ak[3], ak[5], ak[0]),
            new Geometry.Triangle(ak[3], ak[0], ak[1]),
            new Geometry.Triangle(ak[3], ak[1], ak[2]),
            new Geometry.Triangle(ak[3], ak[2], ak[4]),
            new Geometry.Triangle(ak[3], ak[4], ak[8]),
            new Geometry.Triangle(ak[3], ak[8], ak[10]),
            new Geometry.Triangle(ak[10], ak[8], ak[9]),
            new Geometry.Triangle(ak[10], ak[9], ak[7]),
            new Geometry.Triangle(ak[7], ak[6], ak[10]),
            new Geometry.Triangle(ak[10], ak[6], ak[5]),
        };
        diamondAcceleration = new Vec[diamondZone.length];
        for (int i = 0; i < diamondAcceleration.length; i++) {
            Geometry.Triangle z = diamondZone[i];
            Geometry.V3 v01 = new Geometry.V3(z.b.x - z.a.x, z.b.y - z.a.y, z.b.z - z.a.z);
            Geometry.V3 v12 = new Geometry.V3(z.c.x - z.b.x, z.c.y - z.b.y, z.c.z - z.b.z);
            Geometry.V3 normal = v01.cross(v12);
            normal.x /= 100; normal.y /= 100; normal.z /= 100;
            Geometry.V3 v1 = new Geometry.V3(normal.x, normal.y, 0);
            double cosV = (v1.dot(normal) / v1.length()) / normal.length();
            int X = (int) ((normal.x * Config.GRAVITY_FACTOR * 9.80665f * cosV) / normal.length());
            int Y = (int) ((normal.y * Config.GRAVITY_FACTOR * 9.80665f * cosV) / normal.length());
            diamondAcceleration[i] = new Vec(X, Y);
        }
    }

    private Geometry.P3[] loadNormals(AssetManager am) {
        // arrays.xml: <array name="a"><item>..</item>x3</array> for a..k (11 entries)
        ArrayList<int[]> rows = new ArrayList<>();
        try (InputStream is = am.open("arrays.xml")) {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            XmlPullParser p = f.newPullParser();
            p.setInput(is, "UTF-8");
            int ev = p.getEventType();
            int[] cur = null; int idx = 0;
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if ("array".equals(p.getName())) { cur = new int[3]; idx = 0; }
                    else if ("item".equals(p.getName())) {
                        String text = p.nextText().trim();
                        if (cur != null && idx < 3) cur[idx++] = Integer.parseInt(text);
                    }
                } else if (ev == XmlPullParser.END_TAG && "array".equals(p.getName())) {
                    if (cur != null) rows.add(cur);
                    cur = null;
                }
                ev = p.next();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load arrays.xml", e);
        }
        Geometry.P3[] ak = new Geometry.P3[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            int[] r = rows.get(i);
            ak[i] = new Geometry.P3(r[0], r[1], r[2]);
        }
        return ak;
    }

    /** Reads an int attribute by name (level XMLs use named attributes). */
    private static int attr(XmlPullParser p, String name) {
        String v = p.getAttributeValue(null, name);
        return v == null ? -1 : Integer.parseInt(v.trim());
    }
}
