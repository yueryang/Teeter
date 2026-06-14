package com.teeter.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Launcher activity: shows the boot animation (splash_bg + 60-frame loader,
 * sound at frame 30, fade into the game), then hands off to the game. Guards
 * against a missing accelerometer.
 */
public class SplashActivity extends Activity {

    private SplashView splash;
    private boolean launched = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Config.load(getAssets());
        Sound.init(this);

        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor accel = sm != null ? sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (accel == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Teeter")
                    .setMessage("Teeter requires a G-Sensor (accelerometer). This device does not have one.")
                    .setCancelable(false)
                    .setPositiveButton("Quit", (d, w) -> finish())
                    .show();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        splash = new SplashView(this);
        splash.setListener(new SplashView.Listener() {
            @Override public void onFrame(int frame) {
                if (frame == 30) Sound.play(Sound.LEVEL_COMPLETE);
            }
            @Override public void onDone() { launchGame(); }
        });
        root.addView(splash, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        splash.start();
    }

    private void launchGame() {
        if (launched) return;
        launched = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        if (splash != null) splash.recycleAll();
        finish();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (splash != null) splash.recycleAll();
    }
}
