package com.teeter.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.NinePatchDrawable;

/**
 * Bakes the full level background (maze/facet art + end target + holes + wall
 * drop-shadows + wall tiles) into a single 1280x720 bitmap.
 */
final class Background {
    private Background() {}

    static Bitmap build(Context ctx, Level level) {
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inScaled = false;
        opt.inMutable = true;
        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap view = decode(ctx, level.isFacet ? "facet" : "maze", opt);
        Canvas canvas = new Canvas(view);

        Bitmap endBmp = decode(ctx, "end", null);
        drawScaled(canvas, level.end, Config.END_RATIO * Config.END_RADIUS * 2f, endBmp);

        Bitmap holeBmp = decode(ctx, "hole", null);
        float holeTarget = Config.HOLE_RATIO * Config.HOLE_RADIUS * 2f;
        Bitmap scaledHole = scale(holeBmp, holeTarget);
        for (Point h : level.holes) {
            canvas.drawBitmap(scaledHole, h.x - holeTarget / 2f, h.y - holeTarget / 2f, null);
        }

        Bitmap[] wallTiles = new Bitmap[level.walls.length];
        for (int i = 0; i < level.walls.length; i++) {
            Rect w = level.walls[i];
            wallTiles[i] = Bitmap.createBitmap(view, w.left, w.top, w.width(), w.height());
        }

        NinePatchDrawable shadow = (NinePatchDrawable) ctx.getResources()
                .getDrawable(R.drawable.teeter_bar_shadow, ctx.getTheme());
        if (shadow != null) {
            for (Rect w : level.walls) {
                int width = w.width() + Config.WALL_PADDING_LEFT + Config.WALL_PADDING_RIGHT;
                int height = w.height() + Config.WALL_PADDING_TOP + Config.WALL_PADDING_BOTTOM;
                shadow.setBounds(0, 0, width, height);
                Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                shadow.draw(c);

                c.drawBitmap(b, 0f, 0f, null);
                canvas.drawBitmap(b, w.left - Config.WALL_PADDING_LEFT, w.top - Config.WALL_PADDING_TOP, null);
                b.recycle();
            }
        }

        for (int i = 0; i < wallTiles.length; i++) {
            canvas.drawBitmap(wallTiles[i], level.walls[i].left, level.walls[i].top, null);
            wallTiles[i].recycle();
        }

        endBmp.recycle();
        holeBmp.recycle();
        if (scaledHole != holeBmp) scaledHole.recycle();
        return view;
    }

    private static Bitmap decode(Context ctx, String name, BitmapFactory.Options opt) {
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        if (opt == null) {
            opt = new BitmapFactory.Options();
            opt.inScaled = false;
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
        }
        return BitmapFactory.decodeResource(ctx.getResources(), id, opt);
    }

    private static Bitmap scale(Bitmap src, float target) {
        Matrix m = new Matrix();
        m.setScale(target / src.getWidth(), target / src.getHeight());
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private static void drawScaled(Canvas canvas, Point dst, float diameter, Bitmap src) {
        if (dst == null) return;
        Bitmap s = scale(src, diameter);
        canvas.drawBitmap(s, dst.x - diameter / 2f, dst.y - diameter / 2f, (Paint) null);
        if (s != src) s.recycle();
    }
}
