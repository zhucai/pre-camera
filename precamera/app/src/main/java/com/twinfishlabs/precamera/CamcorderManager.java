package com.twinfishlabs.precamera;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.List;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Message;
import android.util.FloatMath;
import android.util.Log;
import android.util.TimingLogger;
import android.view.Surface;

import com.twinfishlabs.precamera.CircularEncoder.Callback;
import com.twinfishlabs.precamera.egl.EglCore;
import com.twinfishlabs.precamera.egl.WindowSurface;

public class CamcorderManager implements PreviewCallback {

	static private final String TAG = Utilities.TAG + ".Manager";
	static public final boolean DEBUG = true;
	static public CamcorderManager Instance;

    public int mCameraPreviewThousandFps;
    public float mCameraThumbnailFpsStep;
    private Camera mCamera;
    private Camera.Parameters mParms;
    private boolean mIsSaving;
    volatile private boolean mIsStoping;

    private AudioRecord mAudioRecord;

    public File mOutputFile;
    public CircularEncoder mCircEncoder;

    public WindowSurface mEncoderSurface;

    public EglCore mEglCore;
    public WindowSurface mDisplaySurface;
    GLThread mGlThread;

    RenderScriptManager mRsManager;

	public Surface mSurface;
	private Callback mCallback;
	public int mPreviewViewWidth;
	public int mPreviewViewHeight;
	private int mPreviewTextureWidth;
	private int mPreviewTextureHeight;
	private int mPreviewImageSize;

	private int mPreviewImageBufferCount;
	Deque<PreviewImageData> mPreviewImageQueue = new ArrayDeque<PreviewImageData>();
	volatile boolean mIsFlushingPreviewImage;
	PreviewImageData mShootImage;
	volatile boolean mIsTakingShootImage;
	boolean mIsShooting;

	private Runnable mOnPreviewParamsChanged;

	H mHandler;

	class H extends Handler {
		static final int MSG_SET_PREVIEW_BUFFER = 1;
		static final int MSG_FLUSH_PREVIEW_FINISHED = 2;
		static final int MSG_SEND_THUMBNAIL = 3;
		static final int MSG_ON_STOPED = 4;
		static final int MSG_PICTURE_SAVE_FINISHED = 5;
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_SET_PREVIEW_BUFFER:
				addPreviewBufferAndSchedule();
				break;
			case MSG_FLUSH_PREVIEW_FINISHED:
				flushAllPreviewImageFinished();
				break;
			case MSG_SEND_THUMBNAIL:
				if (MainActivity.Instance != null) {
					TakedPictureData d = (TakedPictureData)msg.obj;
					MainActivity.Instance.addTakedPictureThumbnail(d.thumbnail, d.fileName, d.isUsed);
				}
				break;
			case MSG_ON_STOPED:
				if (MainActivity.Instance != null) {
					MainActivity.Instance.onRecordStoped(mOutputFile.getAbsolutePath());
				}
				break;
			case MSG_PICTURE_SAVE_FINISHED:
				if (MainActivity.Instance != null) {
					MainActivity.Instance.onPictureSaveFinished();
				}
				break;
			}
		}
	}

	static class PreviewImageData {

		static Deque<PreviewImageData> sQueue = new ArrayDeque<PreviewImageData>();

		private PreviewImageData() { }

		static public PreviewImageData obtain() {
			PreviewImageData data = sQueue.pollLast();
			if (data == null) data = new PreviewImageData();
			data.mSaved = false;
			data.mSaving = false;
			return data;
		}

		public byte[] mData;
		public long mTime;
		volatile public boolean mSaving;
		volatile public boolean mSaved;

		public void recycle() {
			if (!sQueue.contains(this)) {
				sQueue.add(this);
			}
		}
	}

	static class TakedPictureData {
		public Bitmap thumbnail;
		public String fileName;
		public boolean isUsed;
		public TakedPictureData(Bitmap b, String f, boolean i) {
			thumbnail = b;
			fileName = f;
			isUsed = i;
		}
	}

	static public void init() {
		Instance = new CamcorderManager();
	}

	private CamcorderManager() {
		mRsManager = new RenderScriptManager();
	}

    public void setupEncode(Surface surface, Callback cb, int previewViewWidth, int previewViewHeight) {
    	if (mCamera == null) return;

    	mCallback = cb;
    	mPreviewViewWidth = previewViewWidth;
    	mPreviewViewHeight = previewViewHeight;
    	mSurface = surface;

    	mGlThread = new GLThread();
    	mGlThread.start();

        setupAudioRecord();

        Log.d(TAG, "setupEncode()");
        try {
            mCamera.setPreviewTexture(mGlThread.mCameraTexture);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera.startPreview();

        // TODO: adjust bit rate based on frame rate?
        // TODO: adjust video width/height based on what we're getting from the camera preview?
        //       (can we guarantee that camera preview size is compatible with AVC video encoder?)
		try {
			mCircEncoder = new CircularEncoder(Configs.VIDEO_WIDTH, Configs.VIDEO_HEIGHT, mCameraPreviewThousandFps / 1000, mAudioRecord, cb);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
        mEncoderSurface = new WindowSurface(mEglCore, mCircEncoder.getInputSurface(), true);
    }

    public void reset() {
    	releaseAllResource();

		CamcorderManager.Instance.openCamera();
		CamcorderManager.Instance.setupAudioRecord();
		CamcorderManager.Instance.setupEncode(mSurface, mCallback, mPreviewViewWidth, mPreviewViewHeight);
    }

    public void setupAudioRecord(){
        int min_buffer_size = AudioRecord.getMinBufferSize(Configs.SAMPLE_RATE, Configs.CHANNEL_CONFIG, Configs.AUDIO_FORMAT);
        int buffer_size = Configs.SAMPLES_PER_FRAME * 10;
        if (buffer_size < min_buffer_size)
            buffer_size = ((min_buffer_size / Configs.SAMPLES_PER_FRAME) + 1) * Configs.SAMPLES_PER_FRAME * 2;

        mAudioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,       // source
                Configs.SAMPLE_RATE,                         // sample rate, hz
                Configs.CHANNEL_CONFIG,                      // channels
                Configs.AUDIO_FORMAT,                        // audio format
                buffer_size);                        // buffer size (bytes)
    }

    public boolean openCamera() {
        return openCamera(Configs.VIDEO_WIDTH, Configs.VIDEO_HEIGHT, Configs.DESIRED_PREVIEW_FPS);
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets mCameraPreviewFps to the expected frame rate (which might actually be variable).
     */
    private boolean openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

    	if (mHandler == null) {
    		mHandler = new H();
    	}

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == PrefUtils.getCameraType()) {
            	try {
            		mCamera = Camera.open(i);
            	} catch (Exception ex) {
            		Utilities.sLastException = ex;
            		ex.printStackTrace();
            		return false;
            	}
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
        	try {
        		mCamera = Camera.open();    // opens first back-facing camera
        	} catch (Exception ex) {
        		Utilities.sLastException = ex;
        		ex.printStackTrace();
        		return false;
        	}
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        mParms = mCamera.getParameters();

//        CameraUtils.choosePreviewSize(mParms, desiredWidth, desiredHeight);
        CameraUtils.chooseMaxPreviewSize(mParms);

//        mParms.setPictureFormat(ImageFormat.JPEG);
//        CameraUtils.chooseMaxPictureSize(mParms);

        // Try to set the frame rate to a constant value.
        mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(mParms, desiredFps * 1000);
        mCameraThumbnailFpsStep = 1.0f/(CamcorderManager.Instance.mCameraPreviewThousandFps/1000.0f/Configs.THUMBNAIL_FPS);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
//        mParms.setRecordingHint(true);
        mParms.setRotation(90);
        mParms.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);

//        mParms.set("orientation", "portrait");
//        mParms.set("rotation", 90);

        mCamera.setParameters(mParms);
        mCamera.setDisplayOrientation(90);

        Camera.Size cameraPreviewSize = mParms.getPreviewSize();
        mPreviewTextureWidth = cameraPreviewSize.width;
        mPreviewTextureHeight = cameraPreviewSize.height;
    	mPreviewImageSize = (int) FloatMath.ceil(mPreviewTextureWidth * mPreviewTextureHeight
    			* (ImageFormat.getBitsPerPixel(mParms.getPreviewFormat()) / 8.0f));

        String previewFacts = cameraPreviewSize.width + "x" + cameraPreviewSize.height +
                " @" + (mCameraPreviewThousandFps / 1000.0f) + "fps";
        Log.i(TAG, "Camera config: " + previewFacts);

    	mCamera.setPreviewCallbackWithBuffer(this);
        addPreviewBufferAndSchedule();

        if (mOnPreviewParamsChanged != null) mOnPreviewParamsChanged.run();

        return true;
    }

    public void setOnPreviewParamsChanged(Runnable listener) {
    	mOnPreviewParamsChanged = listener;
    }

    private byte[] dequeuePreviewImageBufferLocked() {
    	if (mPreviewImageBufferCount < Configs.SHOT_COUNT) {
    		mPreviewImageBufferCount++;
    		return new byte[mPreviewImageSize];
    	}

    	// 已经出列过了，因此这里不再出列
    	if (mPreviewImageQueue.size() < mPreviewImageBufferCount) {
    		return null;
    	}

    	// 还在保存中，暂时不出列
    	if (mPreviewImageQueue.peekFirst().mSaving) {
    		return null;
    	}

    	PreviewImageData previewData;
    	previewData = mPreviewImageQueue.removeFirst();
    	byte[] data = previewData.mData;
    	previewData.recycle();
    	return data;
    }

    private void addPreviewBufferAndSchedule() {
    	mHandler.removeMessages(H.MSG_SET_PREVIEW_BUFFER);
    	if (!mIsFlushingPreviewImage) {
			synchronized (mPreviewImageQueue) {
		    	if (!mIsFlushingPreviewImage) {
		    		byte[] buffer = dequeuePreviewImageBufferLocked();
		    		if (buffer != null) mCamera.addCallbackBuffer(buffer);
		    	}
			}
    	}
    	mHandler.sendEmptyMessageDelayed(H.MSG_SET_PREVIEW_BUFFER, Configs.PRE_SHOT_INTERVAL_MS);
    }

	public boolean isSupportFlash() {
		List<String> flashModes = mParms.getSupportedFlashModes();
		if (flashModes == null) return false;
		for (int i = flashModes.size() - 1; i > 0; i--) {
			if (Camera.Parameters.FLASH_MODE_TORCH.equals(flashModes.get(i))) return true;
		}
		return false;
	}

    public boolean hasMultiCamera() {
    	return Camera.getNumberOfCameras() > 1;
    }

    public int getPreviewTextureWidth() {
    	return mPreviewTextureWidth;
    }

    public int getPreviewTextureHeight() {
    	return mPreviewTextureHeight;
    }

    public void flushAllPreviewImage() {
    	Thread thread = new Thread("SavePreviewImage") {
    		public void run() {
				boolean needSleep = false;
    			while (true) {
    				if (needSleep) {
	    				try {
							Thread.sleep(10);
						} catch (InterruptedException e) { }
    				}
	    			synchronized (mPreviewImageQueue) {
	    				if (mPreviewImageQueue.size() < Configs.SHOT_COUNT) {
	    					needSleep = true;
		    				continue;
	    				} else {
		    				mIsFlushingPreviewImage = true;
		        	    	for (PreviewImageData previewData : mPreviewImageQueue) {
		        	    		savePreviewImage(previewData, true, false);
		        			}
		        	    	while (mIsTakingShootImage) {
		        	    		try { Thread.sleep(20); } catch (InterruptedException e) { }
		        	    	}
		        	    	savePreviewImage(mShootImage, true, true);

		        	    	mIsFlushingPreviewImage = false;
			    			break;
	    				}
					}
    			}
    			mHandler.sendEmptyMessage(H.MSG_FLUSH_PREVIEW_FINISHED);
    		}
    	};
    	thread.start();
    }

    public void flushAllPreviewImageFinished() {
    	mIsShooting = false;
    }

    public void savePreviewImage(final PreviewImageData previewData, final boolean isUsed, final boolean isLast) {
    	if (previewData.mSaving || previewData.mSaved) return;
    	previewData.mSaving = true;

    	final TimingLogger logger = DEBUG ? new TimingLogger("ZC", "savePreviewImage") : null;

    	int smallHeight = Utilities.getSmallPictureHeight();
    	int smallWidth = Utilities.getSmallPictureWidth();
    	byte[] smallYuvData = Caches.getSmallYuvBuffer(smallWidth, smallHeight);
    	mRsManager.scaleDownYuv(previewData.mData, mPreviewTextureWidth, mPreviewTextureHeight,
    			smallYuvData, smallHeight, smallWidth);  // 宽高调换，这里还未旋转
    	if (DEBUG) logger.addSplit("==== ScaleDownYuv");

    	Bitmap smallPicture = Caches.getSmallBitmap(smallWidth, smallHeight, Config.ARGB_8888);
    	mRsManager.yuvToRgbAndRotate(smallYuvData, smallPicture);

    	Caches.recycleSmallBitmap(smallYuvData);
    	smallYuvData = null;
    	if (DEBUG) logger.addSplit("==== YuvToRgbAndRotate: small img");

    	final File file = Utilities.getImageFile(new Date(previewData.mTime));
    	TakedPictureData data = new TakedPictureData(smallPicture, file.getAbsolutePath(), isUsed);
    	Message.obtain(mHandler, H.MSG_SEND_THUMBNAIL, data).sendToTarget();

    	// landscape is angle degress 0°
    	int angleDegrees = MainActivity.Instance.mOrientation + 90;
    	if (angleDegrees == 360) angleDegrees = 0;
    	final boolean isLandscape = angleDegrees % 180 == 0;
		mRsManager.yuvRotate(previewData.mData, mPreviewTextureWidth, mPreviewTextureHeight, angleDegrees);
		if (DEBUG) logger.addSplit("==== YuvRotate: original img");

    	new Thread() {
    		public void run() {
    			try {
    				int w = isLandscape ? mPreviewTextureWidth : mPreviewTextureHeight;
    				int h = isLandscape ? mPreviewTextureHeight : mPreviewTextureWidth;
    				FileOutputStream fos = new FileOutputStream(file);
    				new YuvImage(previewData.mData, mParms.getPreviewFormat(), w, h, null)
    					.compressToJpeg(new Rect(0, 0, w, h), 80, fos);
    		    	fos.close();
    			} catch (IOException ex) {
    				ex.printStackTrace();
    			}
    			if (DEBUG) logger.addSplit("==== savePNG: rotated original img");
    			if (DEBUG) logger.dumpToLog();

	    		previewData.mSaved = true;
	        	previewData.mSaving = false;

	        	// 全部保存完
	        	if (isLast) {
	        		mHandler.sendEmptyMessage(H.MSG_PICTURE_SAVE_FINISHED);
	        	}
    		}
    	}.start();
    }

    public void saveVideoThumbnail(File videoThumbnailFile) {
    	PreviewImageData previewData = mPreviewImageQueue.peekLast();
    	int h = Utilities.getScreenWidth();
    	int w = Utilities.getScreenHeight();

    	byte[] scaledYuvData = Utilities.newYuvBuffer(w, h);
    	mRsManager.scaleDownYuv(previewData.mData, mPreviewTextureWidth, mPreviewTextureHeight,
    			scaledYuvData, w, h);

    	try {
			FileOutputStream fos = new FileOutputStream(videoThumbnailFile);
			new YuvImage(scaledYuvData, mParms.getPreviewFormat(), w, h, null)
				.compressToJpeg(new Rect(0, 0, w, h), 70, fos);
	    	fos.close();
		} catch (IOException ex) {
			ex.printStackTrace();
		}
    }

    public void shoot() {
    	if (mIsShooting) return;
    	mIsShooting = true;

    	flushAllPreviewImage();
    	if (mShootImage == null) {
    		mShootImage = PreviewImageData.obtain();
    		mShootImage.mData = new byte[mPreviewImageSize];
    	}
    	mIsTakingShootImage = true;
    	mCamera.addCallbackBuffer(mShootImage.mData);
    }

    public boolean isSaving() {
    	return mIsSaving;
    }

    public boolean isStoping() {
    	return mIsStoping;
    }

    public void startSaving() {
    	Date now = new Date();
    	mOutputFile = Utilities.getVideoFile(now);
    	File videoThumbnailFile = Utilities.getVideoThumbnailFile(now);
		Log.d(TAG, "video = " + mOutputFile.getAbsolutePath()+", thumbnail = "+videoThumbnailFile.getAbsolutePath());

		saveVideoThumbnail(videoThumbnailFile);
    	mCircEncoder.startSaveVideo();
    	mIsSaving = true;
    }

	public void stopSaving() {
    	mCircEncoder.waitState(CircularEncoder.STATE_SAVE_DIRECT);
    	mIsStoping = true;
    	mGlThread.signalEndOfStream();
    }

    public void onStoped() {
    	mIsSaving = false;
    	mIsStoping = false;
    	mHandler.sendEmptyMessage(H.MSG_ON_STOPED);
    	Utilities.sendBroadcastScanFile(mOutputFile);
    }

    public void toggleTorch(boolean isOpen) {
//		mCamera.cancelAutoFocus();
		mParms.setFlashMode(isOpen ? Parameters.FLASH_MODE_TORCH : Parameters.FLASH_MODE_OFF);
		mCamera.setParameters(mParms);
//		mCamera.autoFocus(autoFocusCallback);
    }

    public void releaseAllResource() {
    	if (isSaving()) {
    		mCircEncoder.stopSaving();
    	}

    	if (mGlThread == null) return;

    	mHandler.removeMessages(H.MSG_SET_PREVIEW_BUFFER);

        mGlThread.finish();
        mGlThread = null;

        releaseCamera();

        if (mCircEncoder != null) {
            mCircEncoder.shutdown();
            mCircEncoder = null;
        }

        releaseAudioRecord();

        if (mEncoderSurface != null) {
        	mEncoderSurface.release();
        	mEncoderSurface = null;
        }

        if (mDisplaySurface != null) {
            mDisplaySurface.release();
            mDisplaySurface = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    private void releaseAudioRecord() {
    	if (mAudioRecord != null) {
    		mAudioRecord.stop();
    		mAudioRecord.release();
    		mAudioRecord = null;
            Log.d(TAG, "releaseAudioRecord -- done");
    	}
    }

    /* begin to implement PreviewCallback interface */
    @Override
	public void onPreviewFrame(byte[] data, Camera camera) {
    	if (mShootImage != null && data == mShootImage.mData) {
    		mShootImage.mTime = System.currentTimeMillis();
        	mShootImage.mSaved = false;
    		mIsTakingShootImage = false;
    	} else {
	    	PreviewImageData previewData = PreviewImageData.obtain();
	    	previewData.mData = data;
	    	previewData.mTime = System.currentTimeMillis();
			if (!mIsFlushingPreviewImage) {
		    	synchronized (mPreviewImageQueue) {
		    		if (!mIsFlushingPreviewImage) {
			        	mPreviewImageQueue.add(previewData);
					}
		    	}
			}
    	}
	}
    /* end PreviewCallback */

}
