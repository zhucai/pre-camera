package com.twinfishlabs.precamera;

import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Trace;
import android.util.Log;

import com.twinfishlabs.precamera.egl.EglCore;
import com.twinfishlabs.precamera.egl.WindowSurface;
import com.twinfishlabs.precamera.gles.FullFrameRect;
import com.twinfishlabs.precamera.gles.GlUtil;
import com.twinfishlabs.precamera.gles.Texture2dProgram;

public class GLThread extends HandlerThread implements OnFrameAvailableListener {

	static private final String TAG = "GLThread";
	static private final boolean DRAW_EXTRA = true;
	static private final int MSG_FRAME_AVAILABLE = 1;
	static private final int MSG_FINISH = 2;

	private MainHandler mHandler;

    public SurfaceTexture mCameraTexture;  // receives the output from the camera preview
    private FullFrameRect mInternalTexDrawer;
    private FullFrameRect mGeneralTexDrawer;
    private FullFrameRect mColorTexDrawer;

    private final float[] mTmp1Matrix = new float[16];
    private final float[] mTmp2Matrix = new float[16];
    private final float[] mTextureRotateMatrix = new float[16];
    {
    	Matrix.setIdentityM(mTextureRotateMatrix, 0);
        Matrix.translateM(mTextureRotateMatrix, 0, 0.5f, 0.5f, 0);
        Matrix.scaleM(mTextureRotateMatrix, 0, -1, -1, 1);
        Matrix.rotateM(mTextureRotateMatrix, 0, 90, 0, 0, 1);
        Matrix.translateM(mTextureRotateMatrix, 0, -0.5f, -0.5f, 0);
    }

    private int mTextureId;
    public long mLastTimestamp;

//    private int mTestTextureId;

    private int[] mThumbnailTextureIds;
    private int mCurrWriteThumbnail = -1;
    private float mCurrentThumbnailFloat;
    private boolean mAllThumbnailAvailable;

	public class MainHandler extends Handler {

		public MainHandler() {
			super(GLThread.this.getLooper());
		}

        @Override
        public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_FRAME_AVAILABLE:
				frameAvailableInThread();
				break;
			case MSG_FINISH:
				finishInThread();
				break;
			}
        }
	}

	public GLThread() {
		super("GLThread");
	}

	@Override
	public synchronized void start() {
		super.start();
		mHandler = new MainHandler();
	}

	@Override
	public void run() {
		initGL();
		super.run();
	}

	private void initGL() {
        CamcorderManager.Instance.mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
        CamcorderManager.Instance.mDisplaySurface = new WindowSurface(CamcorderManager.Instance.mEglCore,
        		CamcorderManager.Instance.mSurface, false);
        CamcorderManager.Instance.mDisplaySurface.makeCurrent();

        mInternalTexDrawer = new FullFrameRect(new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
        mGeneralTexDrawer = new FullFrameRect(new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D));
        mColorTexDrawer = new FullFrameRect(new Texture2dProgram(Texture2dProgram.ProgramType.COLOR));
        mColorTexDrawer.getProgram().setHasAlpha(true);
        mTextureId = mInternalTexDrawer.createTextureObject();
        mCameraTexture = new SurfaceTexture(mTextureId);
        mCameraTexture.setOnFrameAvailableListener(this);

//    	mTestTextureId = GeneratedTexture.createTestTexture(Image.COARSE);
	}

	private void onStop() {
		mCurrWriteThumbnail = -1;
		mCurrentThumbnailFloat = 0.0f;
		mAllThumbnailAvailable = false;
	}

	public void signalEndOfStream() {
		if (isAlive()) {
			mLastTimestamp = 0;
		}
	}

	@Override
	public void onFrameAvailable(SurfaceTexture surfaceTexture) {
		frameAvailable();
	}

	private void frameAvailable() {
		if (isAlive()) {
			mHandler.sendEmptyMessage(MSG_FRAME_AVAILABLE);
		}
	}

	private void frameAvailableInThread() {
        //Log.d(TAG, "drawFrame");
        if (CamcorderManager.Instance.mEglCore == null) {
            Log.d(TAG, "Skipping drawFrame after shutdown");
            return;
        }

        /********* draw to Window **********/
        // Latch the next frame from the camera.
        CamcorderManager.Instance.mDisplaySurface.makeCurrent();
        mCameraTexture.updateTexImage();
        mCameraTexture.getTransformMatrix(mTmp1Matrix);

        // Fill the SurfaceView with it.
        GLES20.glViewport(0, 0, CamcorderManager.Instance.mPreviewViewWidth, CamcorderManager.Instance.mPreviewViewHeight);

        mInternalTexDrawer.drawFrame(mTextureId, mTmp1Matrix);

		/********* draw thumbnail to Window **********/
        if (!PrefUtils.isDirectRecord() && PrefUtils.getIsShowPreRecord()) {
			if (mAllThumbnailAvailable && !CamcorderManager.Instance.isSaving()) {
				int left = Configs.sThumbnailMarginLeft;
				int top = CamcorderManager.Instance.mPreviewViewHeight - Configs.sThumbnailMarginTop - Configs.THUMBNAIL_SHOW_WIDTH;
				int width = Configs.THUMBNAIL_SHOW_HEIGHT;
				int height = Configs.THUMBNAIL_SHOW_WIDTH;

				int borderSize = Utilities.dpToPx(1);
				GLES20.glViewport(left - borderSize, top - borderSize, width + borderSize * 2, height + borderSize * 2);
				// TODO: 暂时取消，因为在小米NOTE上会导致FC
//				mColorTexDrawer.drawFrame(0, null);

				GLES20.glViewport(left, top, width, height);

				// 减去1秒，因为关键帧（i-frame）是1秒一次
				int currReadThumbnail = mCurrWriteThumbnail + Configs.THUMBNAIL_FPS;
				if (currReadThumbnail >= mThumbnailTextureIds.length) currReadThumbnail -= mThumbnailTextureIds.length;

				mGeneralTexDrawer.drawFrame(mThumbnailTextureIds[currReadThumbnail], GlUtil.IDENTITY_MATRIX);
				GLES20.glViewport(0, 0, CamcorderManager.Instance.mPreviewViewWidth, CamcorderManager.Instance.mPreviewViewHeight);
			}
        }

        CamcorderManager.Instance.mDisplaySurface.swapBuffers();

		/********* draw to thumbnail texture **********/
        if (!PrefUtils.isDirectRecord() && PrefUtils.getIsShowPreRecord()) {
			if (mThumbnailTextureIds == null) {
				mThumbnailTextureIds = mInternalTexDrawer.getProgram().createTextureObjects(
						PrefUtils.getPreRecordRealTime() * Configs.THUMBNAIL_FPS, Configs.THUMBNAIL_WIDTH, Configs.THUMBNAIL_HEIGHT,
						GLES20.GL_TEXTURE_2D);
			}
			mCurrentThumbnailFloat += CamcorderManager.Instance.mCameraThumbnailFpsStep;
			int currWriteThumbnail = Math.round(mCurrentThumbnailFloat) % mThumbnailTextureIds.length;
			if (!CamcorderManager.Instance.isSaving() && currWriteThumbnail != mCurrWriteThumbnail) {
				mCurrWriteThumbnail = currWriteThumbnail;
				if (!mAllThumbnailAvailable && mCurrWriteThumbnail == mThumbnailTextureIds.length - 1) {
					mAllThumbnailAvailable = true;
				}

				Texture2dProgram program = mInternalTexDrawer.getProgram();
				program.beginDrawToTexture(mThumbnailTextureIds[mCurrWriteThumbnail]);
				GLES20.glViewport(0, 0, Configs.THUMBNAIL_WIDTH, Configs.THUMBNAIL_HEIGHT);

				mInternalTexDrawer.drawFrame(mTextureId, mTmp1Matrix);

				program.beginDrawToDefault();
				GLES20.glViewport(0, 0, CamcorderManager.Instance.mPreviewViewWidth, CamcorderManager.Instance.mPreviewViewHeight);
			}
        }

		/********* draw to encoder **********/
		CamcorderManager.Instance.mEncoderSurface.makeCurrent();
        if (!PrefUtils.isDirectRecord() || CamcorderManager.Instance.isSaving()) {
			GLES20.glViewport(0, 0, Configs.VIDEO_WIDTH, Configs.VIDEO_HEIGHT);

			Matrix.multiplyMM(mTmp2Matrix, 0, mTmp1Matrix, 0, mTextureRotateMatrix, 0);

			mInternalTexDrawer.drawFrame(mTextureId, mTmp2Matrix);

			CamcorderManager.Instance.mCircEncoder.frameAvailableSoon();
			CamcorderManager.Instance.mEncoderSurface.setPresentationTime(mCameraTexture.getTimestamp());
			CamcorderManager.Instance.mEncoderSurface.swapBuffers();
		}

        if (CamcorderManager.Instance.isStoping() && mLastTimestamp == 0) {
        	mLastTimestamp = mCameraTexture.getTimestamp();
        	onStop();
        }
	}

    private static void drawColor(float r, float g, float b, float a) {
    	GLES20.glClearColor(r, g, b, a);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

	/**
     * Adds a bit of extra stuff to the display just to give it flavor.
     */
    private static void drawExtra(int frameNum, int width, int height, boolean isSaving) {
        // We "draw" with the scissor rect and clear calls.  Note this uses window coordinates.
        int val = frameNum % 10 / 5;
        if (isSaving) {
	        switch (val) {
            case 0:  GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);   break;
            case 1:  GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);   break;
	        }
        } else {
	        switch (val) {
	            case 0:  GLES20.glClearColor(1.0f, 1.0f, 0.0f, 1.0f);   break;
	            case 1:  GLES20.glClearColor(0.0f, 0.0f, 1.0f, 1.0f);   break;
	        }
        }

        int xpos = (int) (width * ((frameNum % 100) / 100.0f));
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(xpos, 0, width / 32, height / 32);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    public void finish() {
    	mHandler.sendEmptyMessage(MSG_FINISH);
    	try {
			join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
    }

    private void finishInThread() {
    	if (mThumbnailTextureIds != null) {
			GLES20.glDeleteTextures(mThumbnailTextureIds.length, mThumbnailTextureIds, 0);
    	}
        if (mCameraTexture != null) {
            mCameraTexture.release();
            mCameraTexture = null;
        }
    	if (mInternalTexDrawer != null) {
    		mInternalTexDrawer.release(true);
    		mInternalTexDrawer = null;
    	}
    	quit();
    }
}
