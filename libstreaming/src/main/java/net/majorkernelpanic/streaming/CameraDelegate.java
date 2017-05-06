package net.majorkernelpanic.streaming;

import android.hardware.Camera;

import net.majorkernelpanic.streaming.exceptions.CameraInUseException;
import net.majorkernelpanic.streaming.exceptions.InvalidSurfaceException;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.video.VideoQuality;

import java.io.IOException;

/**
 * Created by brucexia on 2017-04-17.
 */

public interface CameraDelegate {

    public interface FrameListener {
        void onPreviewFrame(byte[] data, int width, int height, int rotation);

        void onFrameSizeSelected(int width, int height, int rotation);

        void onCameraStarted(boolean success, Throwable error);
    }

    public void addListener(FrameListener listener);

    public void removeListener(FrameListener listener);

    public VideoQuality getRequestedQuality();

    public VideoQuality getQuality();

    public int getOrientation();

    public void setOrientation(int mOrientation);

    /**
     * Switch between the front facing and the back facing camera of the phone.
     * If {@link #startPreview()} has been called, the preview will be  briefly interrupted.
     * If { #start()} has been called, the stream will be  briefly interrupted.
     * You should not call this method from the main thread if you are already streaming.
     *
     * @throws IOException
     * @throws RuntimeException
     **/
    public void switchCamera() throws RuntimeException, IOException;

    public Camera getCamera();

    public void stopPreview();

    public void startPreview()
            throws CameraInUseException,
            InvalidSurfaceException,
            RuntimeException;

    /**
     * Stops the stream.
     */
    public void stop();

    public void setCamera(int camera);

    /**
     * Opens the camera in a new Looper thread so that the preview callback is not called from the main thread
     * If an exception is thrown in this Looper thread, we bring it back into the main thread.
     *
     * @throws RuntimeException Might happen if another app is already using the camera.
     */
    public void openCamera();

    public void createCamera() throws RuntimeException;


    public void lockCamera();

    public void unlockCamera();

    public void setFlashState(boolean state);

    /**
     * Computes the average frame rate at which the preview callback is called.
     * We will then use this average frame rate with the MediaCodec.
     * Blocks the thread in which this function is called.
     */

    public void setSurfaceView(SurfaceView view);
}
