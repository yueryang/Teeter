package com.teeter.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Single-activity host. Owns the sensor stream, vibrator and wake-lock, drives
 * the GameView, and shows the pause and score overlays. Locked to landscape; the
 * GameView always renders the fixed 16:9 world letterboxed into the real screen.
 */
public class MainActivity extends Activity
        implements SensorEventListener, Ball.Host, GameView.Listener {

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Vibrator vibrator;

    private VibrationAttributes vibrationAttributes;   // API 33+
    private AudioAttributes audioAttributes;           // API < 33

    private HandlerThread vibeThread;
    private Handler vibeHandler;

    private FrameLayout root;
    private GameView gameView;
    private View scoreOverlay;

    private boolean touchable = true;

    private static final String PREFS = "teeter_save";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_GAME_OVER = "game_over";
    private static final String KEY_TOTAL_TIME = "total_time";
    private static final String KEY_TOTAL_ATTEMPT = "total_attempt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

        Config.load(getAssets());
        Sound.init(this);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibeThread = new HandlerThread("teeter-vibe");
        vibeThread.start();
        vibeHandler = new Handler(vibeThread.getLooper());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrationAttributes = new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_MEDIA)
                    .build();
        } else {
            audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
        }

        if (accelerometer == null) {
            new AlertDialog.Builder(this)
                    .setMessage("Teeter requires an accelerometer. This device has none.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (d, w) -> finish())
                    .show();
            return;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        gameView = new GameView(this, this);
        gameView.setListener(this);
        root.addView(gameView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int savedLevel = prefs.getInt(KEY_LEVEL, 1);
        boolean savedGameOver = prefs.getBoolean(KEY_GAME_OVER, false);
        long savedTotalTime = prefs.getLong(KEY_TOTAL_TIME, 0L);
        int savedTotalAttempt = prefs.getInt(KEY_TOTAL_ATTEMPT, 1);

        if (!savedGameOver && savedLevel > 1 && savedLevel <= Config.LEVEL_COUNT) {
            promptResume(savedLevel, savedTotalTime, savedTotalAttempt);
        } else {
            startFresh();
        }
    }

    private void promptResume(int savedLevel, long savedTotalTime, int savedTotalAttempt) {
        new AlertDialog.Builder(this)
                .setTitle("Teeter")
                .setMessage("Do you want to continue playing?")
                .setCancelable(false)
                .setPositiveButton("Resume", (d, w) -> {
                    Config.GAME_OVER = false;
                    Score.restore(savedTotalTime, savedTotalAttempt);
                    Config.LEVEL = savedLevel;
                    gameView.loadLevel(Config.LEVEL);
                })
                .setNegativeButton("Restart", (d, w) -> startFresh())
                .show();
    }

    private void startFresh() {
        Config.LEVEL = 1;
        Config.GAME_OVER = false;
        Score.reset();
        gameView.loadLevel(Config.LEVEL);
    }

    private void saveProgress() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_LEVEL, Config.LEVEL)
                .putBoolean(KEY_GAME_OVER, Config.GAME_OVER)
                .putLong(KEY_TOTAL_TIME, Score.totalTime())
                .putInt(KEY_TOTAL_ATTEMPT, Score.totalAttempt())
                .apply();
    }

    @Override protected void onResume() {
        super.onResume();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gameView != null) gameView.setPaused(false);
    }

    @Override protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        if (gameView != null) gameView.setPaused(true);
        saveProgress();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        Sound.release();
        if (vibeThread != null) {
            vibeThread.quitSafely();
            vibeThread = null;
            vibeHandler = null;
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER && gameView != null) {
            gameView.onSensor(event.values[0], event.values[1], event.values[2]);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override public void vibrate(long ms) {
        if (vibrator == null || vibeHandler == null || ms <= 0) return;
        vibeHandler.post(() -> doVibrate(ms));
    }

    @Override public void vibratePattern(long[] pattern) {
        if (vibrator == null || vibeHandler == null || pattern == null || pattern.length == 0) return;
        vibeHandler.post(() -> doVibratePattern(pattern));
    }

    private void doVibrate(long ms) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE),
                    vibrationAttributes);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                    VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE),
                    audioAttributes);
        } else {
            vibrator.vibrate(ms, audioAttributes);
        }
    }

    private void doVibratePattern(long[] pattern) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), vibrationAttributes);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), audioAttributes);
        } else {
            vibrator.vibrate(pattern, -1, audioAttributes);
        }
    }

    @Override public void keepAwake(boolean on) {
        runOnUiThread(() -> {
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @Override public Level level() { return gameView.currentLevel(); }

    @Override public void onLevelComplete() {
        runOnUiThread(() -> showScore(false));
    }

    @Override public void onGameComplete() {
        runOnUiThread(() -> showScore(true));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!touchable) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN && scoreOverlay == null) {
            showPauseDialog();
        }
        return true;
    }

    private void showPauseDialog() {
        gameView.setPaused(true);
        new AlertDialog.Builder(this)
                .setTitle("Teeter")
                .setMessage("Do you want to quit?")
                .setPositiveButton("Quit", (d, w) -> finish())
                .setNegativeButton("Resume", (d, w) -> gameView.setPaused(false))
                .setOnCancelListener(d -> gameView.setPaused(false))
                .show();
    }

    private void showScore(boolean gameOver) {
        touchable = false;
        gameView.setPaused(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.parseColor("#E6000000"));
        panel.setGravity(Gravity.CENTER);
        int pad = dp(24);
        panel.setPadding(pad, pad, pad, pad);

        String title = gameOver ? "Congratulations! All levels complete"
                                : String.format("Level %d completed", Config.LEVEL);
        panel.addView(text(title, 28, true));
        panel.addView(text("", 8, false));
        panel.addView(text("Level time:    " + Score.formatTime(Score.levelTime()), 18, false));
        panel.addView(text("Level attempts: " + Score.levelAttempt(), 18, false));
        panel.addView(text("Total time:    " + Score.formatTime(Score.totalTime()), 18, false));
        panel.addView(text("Total attempts: " + Score.totalAttempt(), 18, false));
        panel.addView(text("", 16, false));
        panel.addView(text(gameOver ? "Tap to restart" : "Tap to continue", 16, false));

        panel.setOnClickListener(v -> {
            root.removeView(scoreOverlay);
            scoreOverlay = null;
            touchable = true;
            if (gameOver) {
                Config.LEVEL = 1;
                Config.GAME_OVER = false;
                Score.reset();
                gameView.loadLevel(1);
            } else {
                gameView.loadLevel(Config.LEVEL + 1);
            }
            gameView.setPaused(false);
        });

        scoreOverlay = panel;
        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
