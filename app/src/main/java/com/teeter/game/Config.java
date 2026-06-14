package com.teeter.game;

import android.content.res.AssetManager;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.InputStream;

/**
 * Game constants.
 *
 * The whole simulation runs in a fixed LOGICAL space of 1280x720 (16:9), the
 * coordinate space the level XMLs and background art were authored in. The
 * renderer letterboxes this fixed framebuffer into the real device resolution,
 * so gameplay is pixel-identical on every phone regardless of native resolution.
 */
public final class Config {
    private Config() {}

    public static final int SCREEN_WIDTH = 1280;
    public static final int SCREEN_HEIGHT = 720;

    public static final int SCALE_BIT = 10;
    public static int s2b(int n) { return n << SCALE_BIT; }
    public static int b2s(int n) { return n >> SCALE_BIT; }

    public static final double ACC_PER_MILLISEC = 0.066;

    public static int LEVEL_COUNT;
    public static int BALL_RADIUS;
    public static int BALL_RADIUS_BIG;
    public static int HOLE_RADIUS;
    public static int END_RADIUS;
    public static int GRAVITY_FACTOR;
    public static int FRICTION_FACTOR;
    public static float BOUNCE_RATE;
    public static int MAX_SPEED;
    public static int VIBRATION_ACTIVE_SPEED;
    public static int VIBRATION_ACTIVE_SPEED_GROUND;
    public static int VIBRATION_DURATION;
    public static long[] VIBRATION_HOLE;
    public static long[] VIBRATION_END;
    public static float BALL_RATIO = 1.49f;
    public static float HOLE_RATIO = 1f;
    public static float END_RATIO = 1f;
    public static float END_ANIM_RATIO = 2.08f;
    public static float HOLE_ANIM_RATIO = 1.46f;

    public static int WALL_PADDING_TOP = 8;
    public static int WALL_PADDING_BOTTOM = 7;
    public static int WALL_PADDING_LEFT = 7;
    public static int WALL_PADDING_RIGHT = 7;

    public static int LEVEL = 1;
    public static volatile boolean GAME_OVER = false;
    public static volatile boolean HOLE_ON = true;
    public static volatile boolean END_ON = true;

    public static void load(AssetManager am) {
        try (InputStream is = am.open("config.xml")) {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            XmlPullParser p = f.newPullParser();
            p.setInput(is, "UTF-8");
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = p.getName();
                    switch (name) {
                        case "level":   LEVEL_COUNT = intAttr(p); break;
                        case "ball":
                            BALL_RADIUS = intAttr(p);
                            BALL_RADIUS_BIG = s2b(BALL_RADIUS);
                            break;
                        case "hole":    HOLE_RADIUS = intAttr(p); break;
                        case "ending":  END_RADIUS = intAttr(p); break;
                        case "gravity": GRAVITY_FACTOR = intAttr(p); break;
                        case "friction":FRICTION_FACTOR = intAttr(p); break;
                        case "bounce_rate": BOUNCE_RATE = floatAttr(p); break;
                        case "speed_limie": MAX_SPEED = intAttr(p); break;
                        case "vibrate_speed":
                            VIBRATION_ACTIVE_SPEED = intAttr(p);
                            VIBRATION_ACTIVE_SPEED_GROUND = VIBRATION_ACTIVE_SPEED / 2;
                            break;
                        case "v_hit":   VIBRATION_DURATION = intAttr(p); break;
                        case "v_hole":  VIBRATION_HOLE = patternList(p, "v_hole"); break;
                        case "v_ending":VIBRATION_END = patternList(p, "v_ending"); break;
                        case "end_ratio":  END_RATIO = floatAttr(p); break;
                        case "hole_ratio": HOLE_RATIO = floatAttr(p); break;
                        case "ball_ratio": BALL_RATIO = floatAttr(p); break;
                        case "end_anim_ratio":  END_ANIM_RATIO = floatAttr(p); break;
                        case "hole_anim_ratio": HOLE_ANIM_RATIO = floatAttr(p); break;
                        default: break;
                    }
                }
                ev = p.next();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config.xml", e);
        }
    }

    private static int intAttr(XmlPullParser p) {
        return Integer.parseInt(p.getAttributeValue(0).replace("px", "").trim());
    }
    private static float floatAttr(XmlPullParser p) {
        return Float.parseFloat(p.getAttributeValue(0).trim());
    }

    private static long[] patternList(XmlPullParser p, String endTag) throws Exception {
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        int ev = p.next();
        while (!(ev == XmlPullParser.END_TAG && endTag.equals(p.getName()))) {
            if (ev == XmlPullParser.START_TAG && "pattern".equals(p.getName())) {
                out.add((long) intAttr(p));
            }
            ev = p.next();
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }
}
