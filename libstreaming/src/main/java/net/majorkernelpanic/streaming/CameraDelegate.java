package net.majorkernelpanic.streaming;

import android.content.SharedPreferences;
import android.hardware.Camera;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;

import net.majorkernelpanic.streaming.exceptions.CameraInUseException;
import net.majorkernelpanic.streaming.exceptions.InvalidSurfaceException;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.video.VideoQuality;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import static net.majorkernelpanic.streaming.MediaStream.MODE_MEDIACODEC_API;
import static net.majorkernelpanic.streaming.MediaStream.MODE_MEDIACODEC_API_2;

/**
 * Created by brucexia on 2017-04-17.
 */

public class CameraDelegate {
    protected final static String TAG = "VideoStream";

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

    /**
     * Switch between the front facing and the back facing camera of the phone.
     * If {@link #startPreview()} has been called, the preview will be  briefly interrupted.
     * If {@link #start()} has been called, the stream will be  briefly interrupted.
     * You should not call this method from the main thread if you are already streaming.
     *
     * @throws IOException
     * @throws RuntimeException
     **/
    public void switchCamera() throws RuntimeException, IOException {
        if (android.hardware.Camera.getNumberOfCameras() == 1)
            throw new IllegalStateException("Phone only has one camera !");
//        boolean streaming = mStreaming;
        boolean previewing = mCamera != null && mCameraOpenedManually;
        mCameraId = (mCameraId == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) ? android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT : android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
        setCamera(mCameraId);
        stopPreview();
        mFlashEnabled = false;
//		if (previewing) startPreview();
    }

    public Camera getCamera() {
        return mCamera;
    }

    public synchronized void stopPreview() {
//		mCameraOpenedManually = false;
		stop();
    }

    public synchronized void startPreview()
            throws CameraInUseException,
            InvalidSurfaceException,
            RuntimeException {

//		mCameraOpenedManually = true;
		if (!mPreviewStarted) {
			createCamera();
			updateCamera();
		}
    }
    /** Stops the stream. */
    public synchronized void stop() {
		if (mCamera != null) {
			if (mMode == MODE_MEDIACODEC_API) {
				mCamera.setPreviewCallbackWithBuffer(null);
			}
			if (mMode == MODE_MEDIACODEC_API_2) {
				((SurfaceView)mSurfaceView).removeMediaCodecSurface();
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

    public synchronized void createCamera() throws RuntimeException {
        if (mSurfaceView == null)
            throw new InvalidSurfaceException("Invalid surface !");
        if (mSurfaceView.getHolder() == null || !mSurfaceReady)
            throw new InvalidSurfaceException("Invalid surface !");

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
        int[] max = VideoQuality.determineMaximumSupportedFramerate(parameters);

        double ratio = (double) mQuality.resX / (double) mQuality.resY;
        mSurfaceView.requestAspectRatio(ratio);

        parameters.setPreviewFormat(mCameraImageFormat);
        parameters.setPreviewSize(mQuality.resX, mQuality.resY);
        parameters.setPreviewFpsRange(max[0], max[1]);

        try {
            mCamera.setParameters(parameters);
            mCamera.setDisplayOrientation(mOrientation);
            mCamera.startPreview();
            mPreviewStarted = true;
            mUpdated = true;
        } catch (RuntimeException e) {
            destroyCamera();
            throw e;
        }
    }

    protected void lockCamera() {
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

    protected void unlockCamera() {
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
            if (parameters.getFlashMode()==null) {
                // The phone has no flash or the choosen camera can not toggle the flash
                throw new RuntimeException("Can't turn the flash on !");
            } else {
                parameters.setFlashMode(state? Camera.Parameters.FLASH_MODE_TORCH: Camera.Parameters.FLASH_MODE_OFF);
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
}
