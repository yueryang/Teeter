package com.teeter.game;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/** Tiny SoundPool wrapper for the three game SFX. */
public final class Sound {
    public static final int HOLE = 0;
    public static final int LEVEL_COMPLETE = 1;
    public static final int GAME_COMPLETE = 2;

    private static SoundPool pool;
    private static final int[] ids = new int[3];
    private static boolean[] loaded = new boolean[3];

    private Sound() {}

    public static void init(Context ctx) {
        if (pool != null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
        pool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            for (int i = 0; i < ids.length; i++) if (ids[i] == sampleId) loaded[i] = true;
        });
        ids[HOLE] = pool.load(ctx, R.raw.hole, 1);
        ids[LEVEL_COMPLETE] = pool.load(ctx, R.raw.level_complete, 1);
        ids[GAME_COMPLETE] = pool.load(ctx, R.raw.game_complete, 1);
    }

    public static void play(int which) {
        if (pool == null || which < 0 || which >= ids.length) return;
        if (!loaded[which]) return;
        pool.play(ids[which], 1f, 1f, 1, 0, 1f);
    }

    public static void release() {
        if (pool != null) { pool.release(); pool = null; }
        loaded = new boolean[3];
    }
}
