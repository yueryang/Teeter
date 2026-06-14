package com.teeter.game;

import android.os.SystemClock;

public final class Score {
    private static boolean paused = false;
    private static long levelStart = 0;
    private static long levelTime = 0;
    private static long totalTime = 0;
    private static int levelAttempt = 0;
    private static int totalAttempt = 1;

    private Score() {}

    public static void beginLevel() {
        levelStart = SystemClock.uptimeMillis();
        if (!paused) { levelAttempt = 0; levelTime = 0; }
        else paused = false;
    }

    public static void fallInHole() { levelAttempt++; }

    public static void endLevel() {
        levelTime += SystemClock.uptimeMillis() - levelStart;
        totalTime += levelTime;
        levelAttempt++;
        totalAttempt += levelAttempt - 1;
        paused = false;
    }

    public static void pauseRecord() {
        paused = true;
        levelTime += SystemClock.uptimeMillis() - levelStart;
        levelStart = SystemClock.uptimeMillis();
    }

    public static void reset() {
        paused = false; levelStart = 0; levelTime = 0; totalTime = 0;
        levelAttempt = 0; totalAttempt = 1;
    }

    public static long levelTime() { return levelTime; }
    public static long totalTime() { return totalTime; }
    public static int levelAttempt() { return levelAttempt; }
    public static int totalAttempt() { return totalAttempt; }

    /** Restore cumulative progress from a saved game. */
    public static void restore(long savedTotalTime, int savedTotalAttempt) {
        totalTime = savedTotalTime;
        totalAttempt = savedTotalAttempt;
    }

    public static String formatTime(long ms) {
        long sec = ms / 1000;
        long h = sec / 3600;
        long m = (sec - h * 3600) / 60;
        long s = sec - h * 3600 - m * 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
