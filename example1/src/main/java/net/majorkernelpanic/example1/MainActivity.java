package net.majorkernelpanic.example1;

import net.majorkernelpanic.streaming.CameraDelegate;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.rtsp.RtspServer;
import net.majorkernelpanic.streaming.video.VideoQuality;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.WindowManager;

import static android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;

/**
 * A straightforward example of how to use the RTSP server included in libstreaming.
 */
public class MainActivity extends Activity {

    private final static String TAG = "MainActivity";

    private SurfaceView mSurfaceView;
    CameraDelegate cameraDelegate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        mSurfaceView = (SurfaceView) findViewById(R.id.surface);

        // Sets the port of the RTSP server to 1234
        Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(RtspServer.KEY_PORT, String.valueOf(1234));
        editor.commit();
        cameraDelegate = new CameraDelegate(this);
        cameraDelegate.setCamera(CAMERA_FACING_BACK);
        cameraDelegate.setSurfaceView(mSurfaceView);
        cameraDelegate.setOrientation(90);
        // Configures the SessionBuilder
        SessionBuilder.getInstance()
                .setSurfaceView(mSurfaceView)
                .setPreviewOrientation(90)
                .setCameraDelegate(cameraDelegate)
                .setContext(getApplicationContext())
                .setAudioEncoder(SessionBuilder.AUDIO_AMRNB)
                .setVideoEncoder(SessionBuilder.VIDEO_H264);

        // Starts the RTSP server
        this.startService(new Intent(this, RtspServer.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        cameraDelegate.startPreview();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cameraDelegate.stopPreview();
    }
}
