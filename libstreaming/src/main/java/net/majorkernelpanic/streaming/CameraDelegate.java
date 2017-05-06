package net.majorkernelpanic.streaming;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import net.majorkernelpanic.streaming.exceptions.CameraInUseException;
import net.majorkernelpanic.streaming.exceptions.InvalidSurfaceException;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.hw.EncoderDebugger;
import net.majorkernelpanic.streaming.hw.NV21Convertor;
import net.majorkernelpanic.streaming.video.VideoQuality;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static net.majorkernelpanic.streaming.MediaStream.MODE_MEDIACODEC_API;
import static net.majorkernelpanic.streaming.MediaStream.MODE_MEDIACODEC_API_2;

/**
 * Created by brucexia on 2017-04-17.
 */

public class CameraDelegate {
    protected final static String TAG = "CameraDelegate";
    protected static final String PREF_PREFIX = "libstreaming-";

    protected VideoQuality mRequestedQuality = VideoQuality.DEFAULT_VIDEO_QUALITY.clone();
    protected VideoQuality mQuality = mRequestedQuality.clone();
    protected SurfaceHolder.Callback mSurfaceHolderCallback = null;
    protected SurfaceView mSurfaceView = null;
    protected SharedPreferences mSettings = null;
    protected int mVideoEncoder, mCameraId = 0;
    protected int mRequestedOrientation = 0, mOrientation = 0;
    protected android.hardware.Camera mCamera;
    protected Thread mCameraThread;
    protected Looper mCameraLooper;

    protected boolean mCameraOpenedManually = true;
    protected boolean mFlashEnabled = false;
    protected boolean mSurfaceReady = false;
    protected boolean mUnlocked = false;
    protected boolean mPreviewStarted = false;
    protected boolean mUpdated = false;

    protected String mMimeType;
    protected String mEncoderName;
    protected int mEncoderColorFormat;
    protected int mCameraImageFormat;
    protected int mMaxFps = 0;
    protected byte mMode, mRequestedMode;
    Context mContext;
    Display display;

    List<FrameListener> listners = new ArrayList<>();

    public interface FrameListener {
        void onPreviewFrame(byte[] data, int width, int height, int rotation);

        void onFrameSizeSelected(int width, int height, double rotation);

        void onCameraStarted(boolean success, Throwable error);
    }

    public void addListener(FrameListener listener) {
        listners.add(listener);
        if(mCamera!=null)
        mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                for (FrameListener l : listners) {
                    l.onPreviewFrame(data, mQuality.resX, mQuality.resY, mOrientation);
                }
            }
        });
    }

    public void removeListener(FrameListener listener) {
        listners.remove(listener);
    }
    int displayRotation;

    public CameraDelegate(Context context) {
        mCameraImageFormat = ImageFormat.NV21;
        mContext = context.getApplicationContext();
        mSettings = PreferenceManager.getDefaultSharedPreferences(mContext);
        display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        displayRotation = display.getRotation();
    }

    public VideoQuality getRequestedQuality() {
        return mRequestedQuality;
    }

    public VideoQuality getQuality() {
        return mQuality;
    }

    public int getOrientation() {
        return mOrientation;
    }

    public void setOrientation(int mOrientation) {
        this.mOrientation = mOrientation;
    }

    /**
     * Switch between the front facing and the back facing camera of the phone.
     * If {@link #startPreview()} has been called, the preview will be  briefly interrupted.
     * If { #start()} has been called, the stream will be  briefly interrupted.
     * You should not call this method from the main thread if you are already streaming.
     *
     * @throws IOException
     * @throws RuntimeException
     **/
    public void switchCamera() throws RuntimeException, IOException {
        if (android.hardware.Camera.getNumberOfCameras() == 1)
            throw new IllegalStateException("Phone only has one camera !");
        boolean previewing = mCamera != null && mCameraOpenedManually;
        mCameraId = (mCameraId == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) ? android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT : android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
        setCamera(mCameraId);
        stopPreview();
        mFlashEnabled = false;
        if (previewing) startPreview();
    }

    public Camera getCamera() {
        return mCamera;
    }

    public synchronized void stopPreview() {
        mCameraOpenedManually = false;
        stop();
    }

    public synchronized void startPreview()
            throws CameraInUseException,
            InvalidSurfaceException,
            RuntimeException {

        mCameraOpenedManually = true;
        if (!mPreviewStarted) {
            if (mSurfaceView.getHolder() == null || !mSurfaceReady) {
                startRequested = true;
                return;
            }
            createCamera();
            updateCamera();
        }
    }

    /**
     * Stops the stream.
     */
    public synchronized void stop() {
        if (mCamera != null) {
            if (mMode == MODE_MEDIACODEC_API) {
//                mCamera.setPreviewCallbackWithBuffer(null);
            }
            if (mMode == MODE_MEDIACODEC_API_2) {
                ((SurfaceView) mSurfaceView).removeMediaCodecSurface();
            }
//			super.stop();
            // We need to restart the preview
            destroyCamera();
//			} else {
//				try {
//					startPreview();
//				} catch (RuntimeException e) {
//					e.printStackTrace();
//				}
//			}
        }
    }

    public void setCamera(int camera) {
        android.hardware.Camera.CameraInfo cameraInfo = new android.hardware.Camera.CameraInfo();
        int numberOfCameras = android.hardware.Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            android.hardware.Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == camera) {
                mCameraId = i;
                break;
            }
        }
    }

    /**
     * Opens the camera in a new Looper thread so that the preview callback is not called from the main thread
     * If an exception is thrown in this Looper thread, we bring it back into the main thread.
     *
     * @throws RuntimeException Might happen if another app is already using the camera.
     */
    public void openCamera() throws RuntimeException {
        final Semaphore lock = new Semaphore(0);
        final RuntimeException[] exception = new RuntimeException[1];
        mCameraThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                mCameraLooper = Looper.myLooper();
                try {

                    mCamera = android.hardware.Camera.open(mCameraId);
                    for (FrameListener listener : listners) {
                        listener.onCameraStarted(true, null);
                    }

                } catch (RuntimeException e) {
                    exception[0] = e;
                } finally {
                    lock.release();
                    Looper.loop();
                }
            }
        });
        mCameraThread.start();
        lock.acquireUninterruptibly();
        if (exception[0] != null) throw new CameraInUseException(exception[0].getMessage());
    }

    boolean startRequested;

    public synchronized void createCamera() throws RuntimeException {
        if (mSurfaceView == null)
            throw new InvalidSurfaceException("Invalid surface !");
        if (mSurfaceView.getHolder() == null || !mSurfaceReady) {
            startRequested = true;
            return;
        }
//            throw new InvalidSurfaceException("Invalid surface !");

        if (mCamera == null) {
            openCamera();
            mUpdated = false;
            mUnlocked = false;
            mCamera.setErrorCallback(new android.hardware.Camera.ErrorCallback() {
                @Override
                public void onError(int error, android.hardware.Camera camera) {
                    // On some phones when trying to use the camera facing front the media server will die
                    // Whether or not this callback may be called really depends on the phone
                    if (error == android.hardware.Camera.CAMERA_ERROR_SERVER_DIED) {
                        // In this case the application must release the camera and instantiate a new one
                        Log.e(TAG, "Media server died !");
                        // We don't know in what thread we are so stop needs to be synchronized
                        mCameraOpenedManually = false;
                        stop();
                    } else {
                        Log.e(TAG, "Error unknown with the camera: " + error);
                    }
                }
            });

            try {

                // If the phone has a flash, we turn it on/off according to mFlashEnabled
                // setRecordingHint(true) is a very nice optimization if you plane to only use the Camera for recording
                android.hardware.Camera.Parameters parameters = mCamera.getParameters();
                if (parameters.getFlashMode() != null) {
                    parameters.setFlashMode(mFlashEnabled ? android.hardware.Camera.Parameters.FLASH_MODE_TORCH : android.hardware.Camera.Parameters.FLASH_MODE_OFF);
                }
                parameters.setRecordingHint(true);
                mCamera.setParameters(parameters);
                mCamera.setDisplayOrientation(mOrientation);

                try {
                    if (mMode == MODE_MEDIACODEC_API_2) {
                        mSurfaceView.startGLThread();
                        mCamera.setPreviewTexture(mSurfaceView.getSurfaceTexture());
                    } else {
                        mCamera.setPreviewDisplay(mSurfaceView.getHolder());
                    }
                } catch (IOException e) {
                    throw new InvalidSurfaceException("Invalid surface !");
                }

            } catch (RuntimeException e) {
                destroyCamera();
                throw e;
            }

        }
    }

    protected synchronized void destroyCamera() {
        if (mCamera != null) {
            lockCamera();
            mCamera.stopPreview();
            try {
                mCamera.release();
            } catch (Exception e) {
                Log.e(TAG, e.getMessage() != null ? e.getMessage() : "unknown error");
            }
            mCamera = null;
            mCameraLooper.quit();
            mUnlocked = false;
            mPreviewStarted = false;
        }
    }

    protected synchronized void updateCamera() throws RuntimeException {

        // The camera is already correctly configured
        if (mUpdated) return;

        if (mPreviewStarted) {
            mPreviewStarted = false;
            mCamera.stopPreview();
        }

        android.hardware.Camera.Parameters parameters = mCamera.getParameters();
        mQuality = VideoQuality.determineClosestSupportedResolution(parameters, mQuality);
        for (CameraDelegate.FrameListener listener : listners) {
            listener.onFrameSizeSelected(mQuality.resX, mQuality.resY, mOrientation);
        }

        int[] max = VideoQuality.determineMaximumSupportedFramerate(parameters);

        double ratio = (double) mQuality.resX / (double) mQuality.resY;
        mSurfaceView.requestAspectRatio(ratio);

        parameters.setPreviewFormat(mCameraImageFormat);
        parameters.setPreviewSize(mQuality.resX, mQuality.resY);
        parameters.setPreviewFpsRange(max[0], max[1]);

        try {
            mCamera.setParameters(parameters);
            mCamera.setDisplayOrientation(mOrientation);
            mCamera.setPreviewDisplay(mSurfaceView.getHolder());
            mCamera.startPreview();
            mPreviewStarted = true;
            mUpdated = true;
//            measureFramerate();
            mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    for (FrameListener l : listners) {
                        l.onPreviewFrame(data, mQuality.resX, mQuality.resY, mOrientation);
                    }
                }
            });

            EncoderDebugger debugger = EncoderDebugger.debug(mSettings, mQuality.resX, mQuality.resY);
            final NV21Convertor convertor = debugger.getNV21Convertor();
            for (int i = 0; i < 10; i++)
                mCamera.addCallbackBuffer(new byte[convertor.getBufferSize()]);

        } catch (RuntimeException e) {
            destroyCamera();
            throw e;
        } catch (IOException ioe) {
            destroyCamera();
            Log.d(TAG, ioe.getMessage(), ioe);
        }
    }

    public void lockCamera() {
        if (mUnlocked) {
            Log.d(TAG, "Locking camera");
            try {
                mCamera.reconnect();
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
            mUnlocked = false;
        }
    }

    public void unlockCamera() {
        if (!mUnlocked) {
            Log.d(TAG, "Unlocking camera");
            try {
                mCamera.unlock();
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
            mUnlocked = true;
        }
    }

    public synchronized void setFlashState(boolean state) {
        // If the camera has already been opened, we apply the change immediately
        if (mCamera != null) {

//            if (mStreaming && mMode == MODE_MEDIARECORDER_API) {
            lockCamera();
//            }

            Camera.Parameters parameters = mCamera.getParameters();

            // We test if the phone has a flash
            if (parameters.getFlashMode() == null) {
                // The phone has no flash or the choosen camera can not toggle the flash
                throw new RuntimeException("Can't turn the flash on !");
            } else {
                parameters.setFlashMode(state ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
                try {
                    mCamera.setParameters(parameters);
                    mFlashEnabled = state;
                } catch (RuntimeException e) {
                    mFlashEnabled = false;
                    throw new RuntimeException("Can't turn the flash on !");
                } finally {
//                    if (mStreaming && mMode == MODE_MEDIARECORDER_API) {
                    unlockCamera();
//                    }
                }
            }
        } else {
            mFlashEnabled = state;
        }
    }

    /**
     * Computes the average frame rate at which the preview callback is called.
     * We will then use this average frame rate with the MediaCodec.
     * Blocks the thread in which this function is called.
     */
    private void measureFramerate() {
        final Semaphore lock = new Semaphore(0);

        final Camera.PreviewCallback callback = new Camera.PreviewCallback() {
            int i = 0, t = 0;
            long now, oldnow, count = 0;

            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {
                i++;
                now = System.nanoTime() / 1000;
                if (i > 3) {
                    t += now - oldnow;
                    count++;
                }
                if (i > 20) {
                    mQuality.framerate = (int) (1000000 / (t / count) + 1);
                    lock.release();
                }
                oldnow = now;
            }
        };

        mCamera.setPreviewCallback(callback);

        try {
            lock.tryAcquire(2, TimeUnit.SECONDS);
            Log.d(TAG, "Actual framerate: " + mQuality.framerate);
            if (mSettings != null) {
                SharedPreferences.Editor editor = mSettings.edit();
                editor.putInt(PREF_PREFIX + "fps" + mRequestedQuality.framerate + "," + mCameraImageFormat + "," + mRequestedQuality.resX + mRequestedQuality.resY, mQuality.framerate);
                editor.commit();
            }
        } catch (InterruptedException e) {
        }

        mCamera.setPreviewCallback(null);
    }

    public synchronized void setSurfaceView(SurfaceView view) {
        mSurfaceView = view;
        if (mSurfaceHolderCallback != null && mSurfaceView != null && mSurfaceView.getHolder() != null) {
            mSurfaceView.getHolder().removeCallback(mSurfaceHolderCallback);
        }
        if (mSurfaceView != null && mSurfaceView.getHolder() != null) {
            mSurfaceHolderCallback = new SurfaceHolder.Callback() {
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    mSurfaceReady = false;
                    stopPreview();
                    Log.d(TAG, "Surface destroyed !");
                }

                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    mSurfaceReady = true;
                    if(mCamera!=null)
                    startPreview();
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    Log.d(TAG, "Surface Changed !");
                }
            };
            mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
//            mSurfaceReady = true;
        }
    }

    private void setCameraDisplayOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraId, info);

        int degrees = 0;
        switch (displayRotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int rotation;

        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            //determine amount to rotate image and call computeFrameRotation()
            //to have the Frame.ROTATE object ready for CameraDetector to use
            rotation = (info.orientation + degrees) % 360;

//            computeFrameRotation(rotation);

            //Android mirrors the image that will be displayed on screen, but not the image
            //that will be sent as bytes[] in onPreviewFrame(), so we compensate for mirroring after
            //calling computeFrameRotation()
            rotation = (360 - rotation) % 360; // compensate the mirror
        } else { // back-facing
            //determine amount to rotate image and call computeFrameRotation()
            //to have the Frame.ROTATE object ready for CameraDetector to use
            rotation = (info.orientation - degrees + 360) % 360;

//            computeFrameRotation(rotation);
        }
        if (mCamera != null) {
            mCamera.setDisplayOrientation(rotation);
        }
//        Log.d(CameraDelegate.class.getSimpleName(), String.format("setCameraDisplayOrientation previewWidth %d, previewHeight %d, cameraInfo orientation %d, displayOrientation %d, frameRotation %s",
//                cameraWrapper.previewWidth, cameraWrapper.previewHeight, info.orientation, rotation, frameRotation));
        //Now that rotation has been determined (or updated) inform listener of new frame size.
//        if (listener != null) {
//            listener.onFrameSizeSelected(cameraWrapper.previewWidth, cameraWrapper.previewHeight, frameRotation);
//        }
    }

}
