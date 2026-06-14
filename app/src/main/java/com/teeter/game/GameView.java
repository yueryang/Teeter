package com.teeter.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * The renderer + game loop. Everything is drawn into a fixed 1280x720 logical
 * framebuffer and then blitted, letterboxed and centered, into the real surface.
 * This guarantees identical gameplay and framing on any device resolution: the
 * 16:9 world is scaled uniformly to fit and black bars fill the remainder.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    public interface Listener {
        void onLevelComplete();
        void onGameComplete();
    }

    private static final int FRAME_MS = 16;        // ~60fps render
    private static final int PHYSICS_MS = 20;       // simulation step
    private static final int MAX_ACCUM = PHYSICS_MS * 5;

    private final Context ctx;
    private Listener listener;

    private Thread thread;
    private volatile boolean running = false;
    private volatile boolean paused = false;

    private final Bitmap frame;
    private final Canvas frameCanvas;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    private Bitmap background;
    private Bitmap ballBmp;
    private Bitmap[] holeAnim;
    private Bitmap[] endAnim;

    private Level level;
    private Ball ball;
    private final Point ballCenter = new Point();
    private long lastPhysics = 0;
    private int accumulator = 0;
    private long fadeInStart = 0;

    private volatile int pendingLevel = -1;

    private enum Phase { PLAY, HOLE_ANIM, END_ANIM, COMPLETE }
    private volatile Phase phase = Phase.PLAY;
    private int animFrame = 0;
    private long animFrameStart = 0;
    private int holeAnimDegree = 0;
    private Point animPos = new Point();

    private final Ball.Host host;

    public GameView(Context context, Ball.Host host) {
        super(context);
        this.ctx = context;
        this.host = host;
        frame = Bitmap.createBitmap(Config.SCREEN_WIDTH, Config.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888);
        frameCanvas = new Canvas(frame);
        getHolder().setFormat(PixelFormat.RGBA_8888);
        getHolder().addCallback(this);
        blit.setDither(true);
        setFocusable(true);
        loadStaticAssets();
    }

    public void setListener(Listener l) { this.listener = l; }

    private void loadStaticAssets() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;

        int half = (int) (Config.BALL_RATIO * Config.BALL_RADIUS);
        Bitmap rawBall = decode("ball", o);
        Matrix m = new Matrix();
        m.postScale((half * 2f) / rawBall.getWidth(), (half * 2f) / rawBall.getHeight());
        ballBmp = Bitmap.createBitmap(rawBall, 0, 0, rawBall.getWidth(), rawBall.getHeight(), m, true);
        if (ballBmp != rawBall) rawBall.recycle();

        holeAnim = new Bitmap[20];
        for (int i = 0; i < 20; i++) {
            holeAnim[i] = decode(String.format("hole_anim_%03d", i + 1), o);
        }

        Bitmap strip = decode("end_anim", o);
        int h = strip.getHeight();
        int count = strip.getWidth() / h;
        endAnim = new Bitmap[count];
        for (int i = 0; i < count; i++) {
            endAnim[i] = Bitmap.createBitmap(strip, i * h, 0, h, h);
        }
        strip.recycle();
    }

    private Bitmap decode(String name, BitmapFactory.Options o) {
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return BitmapFactory.decodeResource(ctx.getResources(), id, o);
    }

    /**
     * Request a level (re)load. Safe to call from the UI thread; the parse/bake/
     * recycle runs on the loop thread in doLoadLevel(). Before the loop thread
     * starts (first load) it runs inline.
     */
    public void loadLevel(int levelNo) {
        if (running) {
            pendingLevel = levelNo;
        } else {
            doLoadLevel(levelNo);
        }
    }

    /** Performs the load. Runs on the loop thread (or before it starts). */
    private void doLoadLevel(int levelNo) {
        Config.LEVEL = levelNo;
        level = new Level();
        level.parse(ctx.getAssets(), levelNo);
        Bitmap old = background;
        background = Background.build(ctx, level);
        if (old != null) old.recycle();

        ball = new Ball(host);
        ball.updateWalls();
        ball.start(level.begin, new Vec(0, 0), new Vec(0, 0));
        phase = Phase.PLAY;
        fadeInStart = System.currentTimeMillis();
        lastPhysics = 0;
        accumulator = 0;
        Score.beginLevel();
    }

    public Level currentLevel() { return level; }
    public Ball currentBall() { return ball; }

    public void onSensor(float x, float y, float z) {
        if (ball != null) ball.onSensor(x, y, z);
    }

    public void setPaused(boolean p) {
        paused = p;
        if (!p) { lastPhysics = 0; accumulator = 0; if (ball != null) ball.reset(null, null, null); }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        running = true;
        thread = new Thread(this, "teeter-loop");
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        computeDst(width, height);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        try { if (thread != null) thread.join(); } catch (InterruptedException ignored) {}
    }

    private void computeDst(int viewW, int viewH) {
        float scale = Math.min(viewW / (float) Config.SCREEN_WIDTH, viewH / (float) Config.SCREEN_HEIGHT);
        float w = Config.SCREEN_WIDTH * scale;
        float h = Config.SCREEN_HEIGHT * scale;
        float x = (viewW - w) / 2f;
        float y = (viewH - h) / 2f;
        dst.set(x, y, x + w, y + h);
    }

    @Override public void run() {
        while (running) {
            long t0 = System.currentTimeMillis();
            int want = pendingLevel;
            if (want >= 0) {
                pendingLevel = -1;
                doLoadLevel(want);
            }
            if (!paused) step();
            render();
            long dt = System.currentTimeMillis() - t0;
            long sleep = FRAME_MS - dt;
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void step() {
        if (ball == null) return;
        switch (phase) {
            case PLAY: {
                long now = System.currentTimeMillis();
                int state = ball.checkStatus();
                if (state == Ball.STATE_NORMAL || state == Ball.STATE_AT_HOLE || state == Ball.STATE_AT_END) {
                    if (lastPhysics == 0) {
                        ball.updateAttribute(PHYSICS_MS);
                    } else {
                        accumulator += (int) (now - lastPhysics);
                        if (accumulator > MAX_ACCUM) accumulator = MAX_ACCUM;
                        while (accumulator >= PHYSICS_MS) {
                            ball.updateAttribute(PHYSICS_MS);
                            accumulator -= PHYSICS_MS;
                            if (ball.checkStatus() != Ball.STATE_NORMAL) { accumulator = 0; break; }
                        }
                    }
                    lastPhysics = now;
                    int after = ball.checkStatus();
                    if (after == Ball.STATE_AT_HOLE) startHoleAnim();
                    else if (after == Ball.STATE_AT_END) startEndAnim();
                }
                break;
            }
            case HOLE_ANIM:
                advanceAnim(holeAnim.length, this::endHoleAnim);
                break;
            case END_ANIM:
                advanceAnim(endAnim.length, this::endEndAnim);
                break;
            case COMPLETE:
                break;
        }
    }

    private void startHoleAnim() {
        ball.stop();
        Score.fallInHole();
        // mark paused so the respawn's beginLevel() keeps the attempt/time tally
        Score.pauseRecord();
        host.vibratePattern(Config.VIBRATION_HOLE);
        Sound.play(Sound.HOLE);
        ball.getCenter(ballCenter);
        Point hp = (level.holeIndex >= 0 && level.holeIndex < level.holes.length)
                ? level.holes[level.holeIndex] : ballCenter;
        animPos.set(hp.x, hp.y);
        holeAnimDegree = ball.getInHoleDegree();
        phase = Phase.HOLE_ANIM;
        animFrame = 0;
        animFrameStart = System.currentTimeMillis();
    }

    private void endHoleAnim() {
        ball.reset(level.begin, new Vec(0, 0), new Vec(0, 0));
        phase = Phase.PLAY;
        fadeInStart = System.currentTimeMillis();
        lastPhysics = 0;
        accumulator = 0;
        Score.beginLevel();
    }

    private void startEndAnim() {
        ball.stop();
        host.vibratePattern(Config.VIBRATION_END);
        boolean finalLevel = Config.LEVEL >= Config.LEVEL_COUNT;
        Sound.play(finalLevel ? Sound.GAME_COMPLETE : Sound.LEVEL_COMPLETE);
        if (finalLevel) Config.GAME_OVER = true;
        animPos.set(level.end.x, level.end.y);
        phase = Phase.END_ANIM;
        animFrame = 0;
        animFrameStart = System.currentTimeMillis();
    }

    private void endEndAnim() {
        phase = Phase.COMPLETE;
        Score.endLevel();
        if (Config.GAME_OVER) {
            if (listener != null) listener.onGameComplete();
        } else {
            if (listener != null) listener.onLevelComplete();
        }
    }

    private interface AnimDone { void run(); }

    private void advanceAnim(int frameCount, AnimDone done) {
        long now = System.currentTimeMillis();
        int dur = 45;
        if (now - animFrameStart >= dur) {
            animFrame++;
            animFrameStart = now;
            if (animFrame >= frameCount) { done.run(); }
        }
    }

    private void render() {
        SurfaceHolder holder = getHolder();
        Canvas c = holder.lockCanvas();
        if (c == null) return;
        try {
            drawFrame();
            c.drawColor(Color.BLACK);
            c.drawBitmap(frame, null, dst, blit);
        } finally {
            holder.unlockCanvasAndPost(c);
        }
    }

    private void drawFrame() {
        if (background != null) frameCanvas.drawBitmap(background, 0, 0, null);
        else frameCanvas.drawColor(Color.BLACK);

        if (ball == null) return;
        int half = (int) (Config.BALL_RATIO * Config.BALL_RADIUS);

        switch (phase) {
            case PLAY: {
                ball.getCenter(ballCenter);
                long elapsed = System.currentTimeMillis() - fadeInStart;
                if (elapsed < 500) ballPaint.setAlpha((int) (elapsed / 2)); else ballPaint.setAlpha(255);
                frameCanvas.drawBitmap(ballBmp, ballCenter.x - half, ballCenter.y - half, ballPaint);
                break;
            }
            case HOLE_ANIM: {
                int f = Math.min(animFrame, holeAnim.length - 1);
                Bitmap b = holeAnim[f];
                float r = Config.HOLE_ANIM_RATIO * Config.BALL_RADIUS;
                drawRotated(b, animPos.x, animPos.y, r * 2f, holeAnimDegree);
                break;
            }
            case END_ANIM: {
                int f = Math.min(animFrame, endAnim.length - 1);
                Bitmap b = endAnim[f];
                float r = Config.END_ANIM_RATIO * Config.BALL_RADIUS;
                drawScaledCentered(b, animPos.x, animPos.y, r * 2f);
                break;
            }
            case COMPLETE:
                break;
        }
    }

    private final Matrix tmpM = new Matrix();

    private void drawScaledCentered(Bitmap b, int cx, int cy, float diameter) {
        tmpM.reset();
        float s = diameter / b.getWidth();
        tmpM.postScale(s, s);
        tmpM.postTranslate(cx - diameter / 2f, cy - diameter / 2f);
        frameCanvas.drawBitmap(b, tmpM, blit);
    }

    private void drawRotated(Bitmap b, int cx, int cy, float diameter, float degrees) {
        tmpM.reset();
        float s = diameter / b.getWidth();
        tmpM.postScale(s, s);
        tmpM.postRotate(degrees, diameter / 2f, diameter / 2f);
        tmpM.postTranslate(cx - diameter / 2f, cy - diameter / 2f);
        frameCanvas.drawBitmap(b, tmpM, blit);
    }
}
