package com.teeter.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Boot animation: the 1280x720 splash_bg with a 60-frame loader animation
 * composited at logical (820,110), size 183x293 — drawn into the fixed 16:9
 * framebuffer and letterboxed into the real screen.
 */
public class SplashView extends View {

    public interface Listener {
        /** Fired once when the animation reaches a given frame index. */
        void onFrame(int frame);
        /** Fired when the animation completes. */
        void onDone();
    }

    private static final int LOADER_X = 820;
    private static final int LOADER_Y = 110;
    private static final int LOADER_W = 183;
    private static final int LOADER_H = 293;
    private static final int FRAME_COUNT = 60;

    private final Context ctx;
    private Listener listener;

    private final Bitmap frame;
    private final Canvas frameCanvas;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    private Bitmap bg;
    private final Bitmap[] frames = new Bitmap[FRAME_COUNT];
    private int current = -1;

    private final RectF loaderDst = new RectF(LOADER_X, LOADER_Y, LOADER_X + LOADER_W, LOADER_Y + LOADER_H);

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            current++;
            if (current >= FRAME_COUNT) {
                if (listener != null) listener.onDone();
                return;
            }
            if (listener != null) listener.onFrame(current);
            invalidate();
            int delay = current <= 29 ? 25 : 50;
            postDelayed(this, delay);
        }
    };

    public SplashView(Context context) {
        super(context);
        this.ctx = context;
        blit.setDither(true);
        frame = Bitmap.createBitmap(Config.SCREEN_WIDTH, Config.SCREEN_HEIGHT, Bitmap.Config.ARGB_8888);
        frameCanvas = new Canvas(frame);
        load();
    }

    public void setListener(Listener l) { this.listener = l; }

    private void load() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        bg = decode("splash_bg", o);
        for (int i = 0; i < FRAME_COUNT; i++) {
            frames[i] = decode(String.format("splash_%04d", i + 1), o);
        }
    }

    private Bitmap decode(String name, BitmapFactory.Options o) {
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return BitmapFactory.decodeResource(ctx.getResources(), id, o);
    }

    public void start() { removeCallbacks(ticker); post(ticker); }
    public void stop() { removeCallbacks(ticker); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float scale = Math.min(w / (float) Config.SCREEN_WIDTH, h / (float) Config.SCREEN_HEIGHT);
        float fw = Config.SCREEN_WIDTH * scale, fh = Config.SCREEN_HEIGHT * scale;
        float x = (w - fw) / 2f, y = (h - fh) / 2f;
        dst.set(x, y, x + fw, y + fh);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (bg != null) frameCanvas.drawBitmap(bg, null, new RectF(0, 0, Config.SCREEN_WIDTH, Config.SCREEN_HEIGHT), blit);
        else frameCanvas.drawColor(Color.BLACK);
        if (current >= 0 && current < FRAME_COUNT && frames[current] != null) {
            frameCanvas.drawBitmap(frames[current], null, loaderDst, blit);
        }
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(frame, null, dst, blit);
    }

    public void recycleAll() {
        stop();
        if (bg != null) { bg.recycle(); bg = null; }
        for (int i = 0; i < frames.length; i++) {
            if (frames[i] != null) { frames[i].recycle(); frames[i] = null; }
        }
    }
}
