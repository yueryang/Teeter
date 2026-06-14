package com.teeter.game;

import android.graphics.Point;
import android.graphics.Rect;

/**
 * The marble: accelerometer-driven integer integration with wall/edge/corner
 * collisions, facet-tilt zones, holes and the end target. Runs entirely in the
 * fixed 1280x720 logical space.
 *
 * Android-specific concerns (SensorManager, Vibrator, wake-lock) sit behind a
 * Host callback.
 */
public class Ball {

    public interface Host {
        /** Single impact pulse (wall/edge/corner hit). */
        void vibrate(long ms);
        /** Play an on/off waveform pattern (hole/end fanfare), no repeat. */
        void vibratePattern(long[] pattern);
        void keepAwake(boolean on);
        Level level();
    }

    public static final int STATE_NORMAL = 0;
    public static final int STATE_AT_HOLE = 1;
    public static final int STATE_AT_END = 2;
    public static final int STATE_PAUSE = 3;

    private final Host host;

    private final int top    = Config.BALL_RADIUS_BIG;
    private final int left   = Config.BALL_RADIUS_BIG;
    private final int right  = Config.s2b(Config.SCREEN_WIDTH - Config.BALL_RADIUS);
    private final int bottom = Config.s2b(Config.SCREEN_HEIGHT - Config.BALL_RADIUS);

    private Point ballPos;
    private Point nextPos;
    private final Vec velocity = new Vec(0, 0);
    private final Vec acceleration = new Vec(0, 0);
    private final Vec temp = new Vec(0, 0);
    private int friction;

    private int state = STATE_NORMAL;
    private Rect wall;
    private Rect[] walls;

    private Vec currentDiamond;
    private int ballZone = -1;
    private int nextZone;

    private final float[] sensor = {0, 0, 0};
    private final float[] sensorOld = {0, 0, 0};
    private long idleStamp = 0;
    private boolean keepAwake = true;
    private boolean awakeState = true;

    public Ball(Host host) {
        this.host = host;
        Point begin = host.level().begin;
        this.ballPos = new Point(Config.s2b(begin.x), Config.s2b(begin.y));
        this.nextPos = new Point(ballPos);
    }

    public void updateWalls() { this.walls = host.level().wallsBig; }

    public void reset(Point begin, Vec v, Vec a) {
        if (begin != null) {
            ballPos = new Point(Config.s2b(begin.x), Config.s2b(begin.y));
            nextPos = new Point(ballPos);
        }
        if (v != null) velocity.set(v.x, v.y);
        if (a != null) acceleration.set(a.x, a.y);
        state = STATE_NORMAL;
    }

    public void start(Point pos, Vec v, Vec a) {
        reset(pos, v, a);
    }

    public void stop() { state = STATE_PAUSE; }

    public int checkStatus() { return state; }

    public void getCenter(Point out) { out.x = Config.b2s(ballPos.x); out.y = Config.b2s(ballPos.y); }
    public void getVelocity(Vec out) { out.x = velocity.x; out.y = velocity.y; }
    public void getAcceleration(Vec out) { out.x = acceleration.x; out.y = acceleration.y; }

    /** Push a fresh accelerometer reading (x,y,z in m/s^2). */
    public void onSensor(float vx, float vy, float vz) {
        if (Math.abs(vx) <= 0.2f && Math.abs(vy) <= 0.2f) {
            sensor[0] = 0f; sensor[1] = 0f; sensor[2] = vz;
        } else {
            sensor[0] = vx; sensor[1] = vy; sensor[2] = vz;
        }
    }

    public void updateAttribute(int elapsedMillis) {
        state = updateState();
        if (state != STATE_NORMAL) return;
        updateAcceleration();
        updateVelocity(elapsedMillis);
        nextPos.x += velocity.x;
        nextPos.y += velocity.y;
        wallContactFix();
        screenContactFix();
        wallContactControl();
        screenContactControl();
        int v = velocity.length();
        if (v > Config.MAX_SPEED) {
            float ratio = (float) Config.MAX_SPEED / v;
            velocity.x = (int) (velocity.x * ratio);
            velocity.y = (int) (velocity.y * ratio);
        }
        if (velocity.length() > friction) velocity.decrease(friction);
        else velocity.set(0, 0);
        ballPos.x = nextPos.x;
        ballPos.y = nextPos.y;
    }

    private int updateState() {
        long now = System.currentTimeMillis();
        if (Math.abs(sensor[0] - sensorOld[0]) > 0.05f || Math.abs(sensor[1] - sensorOld[1]) > 0.05f) {
            idleStamp = now;
            keepAwake = true;
        } else if (now - idleStamp > 3000) {
            keepAwake = false;
        }
        if (!awakeState && keepAwake) { awakeState = true; host.keepAwake(true); }
        if (awakeState && !keepAwake) { awakeState = false; host.keepAwake(false); }
        sensorOld[0] = sensor[0];
        sensorOld[1] = sensor[1];

        if (state == STATE_PAUSE) return STATE_PAUSE;
        if (Config.END_ON && host.level().isAtEnd(ballPos)) return STATE_AT_END;
        if (Config.HOLE_ON && host.level().isAtHole(ballPos)) return STATE_AT_HOLE;
        return STATE_NORMAL;
    }

    private void updateAcceleration() {
        acceleration.x = (int) (sensor[1] * Config.GRAVITY_FACTOR);
        acceleration.y = (int) (sensor[0] * Config.GRAVITY_FACTOR);
        Level lvl = host.level();
        if (lvl.isFacet) {
            nextZone = lvl.atZone(Config.b2s(nextPos.x), Config.b2s(nextPos.y), velocity.x, velocity.y);
            if (nextZone != ballZone) {
                if (currentDiamond == null) currentDiamond = lvl.getDiamondAcceleration(nextZone);
                int speed = velocity.dot(currentDiamond) / Math.max(1, currentDiamond.length());
                if (speed >= Config.VIBRATION_ACTIVE_SPEED_GROUND || (-speed) >= Config.VIBRATION_ACTIVE_SPEED) {
                    across();
                }
                currentDiamond = lvl.getDiamondAcceleration(nextZone);
            }
            ballZone = nextZone;
            acceleration.x += currentDiamond.x;
            acceleration.y += currentDiamond.y;
        }
        friction = (int) Math.abs(sensor[2] * Config.FRICTION_FACTOR);
    }

    private void updateVelocity(int elapsedMillis) {
        velocity.x = (int) (velocity.x + acceleration.x * elapsedMillis * Config.ACC_PER_MILLISEC);
        velocity.y = (int) (velocity.y + acceleration.y * elapsedMillis * Config.ACC_PER_MILLISEC);
    }

    private void hits() { host.vibrate(Config.VIBRATION_DURATION); }
    private void across() { host.vibrate(Config.VIBRATION_DURATION); }

    public int getInHoleDegree() {
        int len = Math.max(1, acceleration.length());
        int degree = (int) Math.toDegrees(Math.acos((double) acceleration.y / len));
        if (acceleration.x > 0) degree = 360 - degree;
        return degree + 30;
    }

    private void screenContactFix() {
        final float B = Config.BOUNCE_RATE;
        if (nextPos.x < left) {
            if (Math.abs(velocity.x) > Config.VIBRATION_ACTIVE_SPEED) hits();
            velocity.x = (int) (velocity.x * -B);
            nextPos.x += left - nextPos.x;
        }
        if (nextPos.x > right) {
            if (Math.abs(velocity.x) > Config.VIBRATION_ACTIVE_SPEED) hits();
            velocity.x = (int) (velocity.x * -B);
            nextPos.x -= nextPos.x - right;
        }
        if (nextPos.y < top) {
            if (Math.abs(velocity.y) > Config.VIBRATION_ACTIVE_SPEED) hits();
            velocity.y = (int) (velocity.y * -B);
            nextPos.y += top - nextPos.y;
        }
        if (nextPos.y > bottom) {
            if (Math.abs(velocity.y) > Config.VIBRATION_ACTIVE_SPEED) hits();
            velocity.y = (int) (velocity.y * -B);
            nextPos.y -= nextPos.y - bottom;
        }
    }

    private void screenContactControl() {
        if (nextPos.x < left)   { velocity.x = 0; nextPos.x += left - nextPos.x; }
        if (nextPos.x > right)  { velocity.x = 0; nextPos.x -= nextPos.x - right; }
        if (nextPos.y < top)    { velocity.y = 0; nextPos.y += top - nextPos.y; }
        if (nextPos.y > bottom) { velocity.y = 0; nextPos.y -= nextPos.y - bottom; }
    }

    private void wallContactFix() {
        final int R = Config.BALL_RADIUS_BIG;
        final float B = Config.BOUNCE_RATE;
        final int VS = Config.VIBRATION_ACTIVE_SPEED;
        for (int i = 0; i < walls.length; i++) {
            wall = walls[i];
            if (nextPos.y >= wall.top && nextPos.y <= wall.bottom) {
                int baseL = wall.left - R;
                if (Math.abs(baseL - nextPos.x) < R && nextPos.x > baseL) {
                    if (Math.abs(velocity.x) > VS) hits();
                    velocity.x = (int) (velocity.x * -B);
                    nextPos.x += baseL - nextPos.x;
                }
                if (ballPos.x < baseL && nextPos.x > baseL) {
                    if (Math.abs(velocity.x) > VS) hits();
                    velocity.x = (int) (velocity.x * -B);
                    nextPos.x += baseL - nextPos.x;
                }
                int baseR = wall.right + R;
                if (Math.abs(baseR - nextPos.x) < R && nextPos.x < baseR) {
                    if (Math.abs(velocity.x) > VS) hits();
                    velocity.x = (int) (velocity.x * -B);
                    nextPos.x += baseR - nextPos.x;
                }
                if (ballPos.x > baseR && nextPos.x < baseR) {
                    if (Math.abs(velocity.x) > VS) hits();
                    velocity.x = (int) (velocity.x * -B);
                    nextPos.x += baseR - nextPos.x;
                }
            }
            if (nextPos.x >= wall.left && nextPos.x <= wall.right) {
                int baseT = wall.top - R;
                if (Math.abs(baseT - nextPos.y) < R && nextPos.y > baseT) {
                    if (Math.abs(velocity.y) > VS) hits();
                    velocity.y = (int) (velocity.y * -B);
                    nextPos.y += baseT - nextPos.y;
                }
                if (ballPos.y < baseT && nextPos.y > baseT) {
                    if (Math.abs(velocity.y) > VS) hits();
                    velocity.y = (int) (velocity.y * -B);
                    nextPos.y += baseT - nextPos.y;
                }
                int baseB = wall.bottom + R;
                if (Math.abs(baseB - nextPos.y) < R && nextPos.y < baseB) {
                    if (Math.abs(velocity.y) > VS) hits();
                    velocity.y = (int) (velocity.y * -B);
                    nextPos.y += baseB - nextPos.y;
                }
                if (ballPos.y > baseB && nextPos.y < baseB) {
                    if (Math.abs(velocity.y) > VS) hits();
                    velocity.y = (int) (velocity.y * -B);
                    nextPos.y += baseB - nextPos.y;
                }
            }
            cornerFix(wall.left, wall.top, true, true);
            edgeSlip(wall.left, wall.top, true, true);
            cornerFix(wall.left, wall.bottom, true, false);
            edgeSlip(wall.left, wall.bottom, true, false);
            cornerFix(wall.right, wall.top, false, true);
            edgeSlip(wall.right, wall.top, false, true);
            cornerFix(wall.right, wall.bottom, false, false);
            edgeSlip(wall.right, wall.bottom, false, false);
        }
    }

    /** Rounded-corner bounce against a wall corner (cx,cy). */
    private void cornerFix(int cx, int cy, boolean leftSide, boolean topSide) {
        final int R = Config.BALL_RADIUS_BIG;
        final float B = Config.BOUNCE_RATE;
        boolean inX = leftSide ? (nextPos.x <= cx && nextPos.x >= cx - R)
                               : (nextPos.x >= cx && nextPos.x <= cx + R);
        boolean inY = topSide  ? (nextPos.y <= cy && nextPos.y >= cy - R)
                               : (nextPos.y >= cy && nextPos.y <= cy + R);
        if (!(inX && inY)) return;
        int dist = (int) Geometry.distance(cx, cy, nextPos.x, nextPos.y);
        if (dist >= R) return;
        temp.set(cx - nextPos.x, cy - nextPos.y);
        float dot = temp.dot(velocity);
        if (dot <= 0f) return;
        Vec force = temp.mul((float) dot / Math.max(1, temp.dot(temp)));
        velocity.x = (int) (velocity.x - force.x * (B + 1f));
        velocity.y = (int) (velocity.y - force.y * (B + 1f));
        Vec push = temp.mul(((float) R / Math.max(1, dist)) - 1f);
        nextPos.x -= push.x;
        nextPos.y -= push.y;
        if (Math.abs(force.length()) > Config.VIBRATION_ACTIVE_SPEED / 3) hits();
    }

    /** Ball passing diagonally over a corner; deflect to whichever face it hits first. */
    private void edgeSlip(int cx, int cy, boolean leftSide, boolean topSide) {
        final int R = Config.BALL_RADIUS_BIG;
        final float B = Config.BOUNCE_RATE;
        boolean cross;
        if (leftSide && topSide)        cross = ballPos.x <= cx && ballPos.y <= cy && nextPos.x >= cx && nextPos.y >= cy;
        else if (leftSide)              cross = ballPos.x <= cx && ballPos.y >= cy && nextPos.x >= cx && nextPos.y <= cy;
        else if (topSide)               cross = ballPos.x >= cx && ballPos.y <= cy && nextPos.x <= cx && nextPos.y >= cy;
        else                            cross = ballPos.x >= cx && ballPos.y >= cy && nextPos.x <= cx && nextPos.y <= cy;
        if (!cross) return;

        int vlen = Math.max(1, velocity.length());
        int signX = leftSide ? 1 : -1;
        int cosV = (signX * velocity.x * 1000) / vlen;
        temp.set(cx - ballPos.x, cy - ballPos.y);
        int tlen = Math.max(1, temp.length());
        int cosCenter = (signX * temp.x * 1000) / tlen;

        int targetX = leftSide ? (cx - R) : (cx + R);
        int targetY = topSide  ? (cy - R) : (cy + R);

        if (cosV > cosCenter) {
            if (Math.abs(velocity.y) > Config.VIBRATION_ACTIVE_SPEED) hits();
            velocity.y = (int) (velocity.y * -B);
            nextPos.y += targetY - nextPos.y;
        } else if (cosV < cosCenter) {
            if (Math.abs(velocity.x) > Config.VIBRATION_ACTIVE_SPEED) hits();
            velocity.x = (int) (velocity.x * -B);
            nextPos.x += targetX - nextPos.x;
        } else {
            if (Math.abs(velocity.length()) > Config.VIBRATION_ACTIVE_SPEED / 3) hits();
            velocity.x = (int) (velocity.x * -B);
            velocity.y = (int) (velocity.y * -B);
            nextPos.x += targetX - nextPos.x;
            nextPos.y += targetY - nextPos.y;
        }
    }

    private void wallContactControl() {
        final int R = Config.BALL_RADIUS_BIG;
        for (int i = 0; i < walls.length; i++) {
            wall = walls[i];
            if (nextPos.y >= wall.top && nextPos.y <= wall.bottom) {
                int baseL = wall.left - R;
                if (Math.abs(baseL - nextPos.x) < R && nextPos.x > baseL) { velocity.x = 0; nextPos.x += baseL - nextPos.x; }
                if (ballPos.x < baseL && nextPos.x > baseL)               { velocity.x = 0; nextPos.x += baseL - nextPos.x; }
                int baseR = wall.right + R;
                if (Math.abs(baseR - nextPos.x) < R && nextPos.x < baseR) { velocity.x = 0; nextPos.x += baseR - nextPos.x; }
                if (ballPos.x > baseR && nextPos.x < baseR)               { velocity.x = 0; nextPos.x += baseR - nextPos.x; }
            }
            if (nextPos.x >= wall.left && nextPos.x <= wall.right) {
                int baseT = wall.top - R;
                if (Math.abs(baseT - nextPos.y) < R && nextPos.y > baseT) { velocity.y = 0; nextPos.y += baseT - nextPos.y; }
                if (ballPos.y < baseT && nextPos.y > baseT)               { velocity.y = 0; nextPos.y += baseT - nextPos.y; }
                int baseB = wall.bottom + R;
                if (Math.abs(baseB - nextPos.y) < R && nextPos.y < baseB) { velocity.y = 0; nextPos.y += baseB - nextPos.y; }
                if (ballPos.y > baseB && nextPos.y < baseB)               { velocity.y = 0; nextPos.y += baseB - nextPos.y; }
            }
            cornerControl(wall.left, wall.top, true, true);
            cornerControl(wall.left, wall.bottom, true, false);
            cornerControl(wall.right, wall.top, false, true);
            cornerControl(wall.right, wall.bottom, false, false);
        }
    }

    private void cornerControl(int cx, int cy, boolean leftSide, boolean topSide) {
        final int R = Config.BALL_RADIUS_BIG;
        boolean inX = leftSide ? (nextPos.x <= cx && nextPos.x >= cx - R)
                               : (nextPos.x >= cx && nextPos.x <= cx + R);
        boolean inY = topSide  ? (nextPos.y <= cy && nextPos.y >= cy - R)
                               : (nextPos.y >= cy && nextPos.y <= cy + R);
        if (!(inX && inY)) return;
        int dist = (int) Geometry.distance(cx, cy, nextPos.x, nextPos.y);
        if (dist >= R) return;
        temp.set(cx - nextPos.x, cy - nextPos.y);
        if (temp.dot(velocity) <= 0f) return;
        velocity.x = 0; velocity.y = 0;
        Vec push = temp.mul(((float) R / Math.max(1, dist)) - 1f);
        nextPos.x -= push.x;
        nextPos.y -= push.y;
    }
}
