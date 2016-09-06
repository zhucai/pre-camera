package com.twinfishlabs.precamera;

import java.lang.ref.WeakReference;

import com.twinfishlabs.precamera.gallery.GalleryActivity;
import com.umeng.analytics.MobclickAgent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.util.Log;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements SurfaceHolder.Callback, OnClickListener {
    private static final String TAG = Utilities.TAG + ".Activity";

    static final int recColor = 0xFFFF0000;
    static final int darkColor = 0x22FF9900;

    static final int bottomBgColor = 0x44000000;

    static public MainActivity Instance;

    private MainHandler mHandler;
    private float mSecondsOfVideo;
    public int mOrientation;
    MyOrientationEventListener mMyOrientationEventListener;

    private ImageButton mRecordingButton;
    private ImageButton mShootButton;
    private GalleryButton mGalleryButton;
    private SurfaceView mContinuousCaptureSurfaceView;
    private TextView mTxtCapturedTime;
    private TextView mRecordingText;
    private ImageButton mBtnLight;
    private ImageButton mBtnSettings;
    private TextView mTxtDebugMode;
    private ViewGroup mBottomBar;
    private TakedPicturesView mTakedPicturesView;
    private ImageView mFullPreviewPicView;

    private class MyOrientationEventListener extends OrientationEventListener {

		public MyOrientationEventListener(Context context) {
			super(context, SensorManager.SENSOR_DELAY_NORMAL);
		}

		@Override
		public void onOrientationChanged(int orientation) {
			changeOrientation(orientation);
		}
    }

    private static class MainHandler extends Handler implements CircularEncoder.Callback {
        public static final int MSG_BLINK_TEXT = 1;
        public static final int MSG_FILE_SAVE_COMPLETE = 2;
        public static final int MSG_BUFFER_STATUS = 3;

        private WeakReference<MainActivity> mWeakActivity;

        public MainHandler(MainActivity activity) {
            mWeakActivity = new WeakReference<MainActivity>(activity);
        }

        // CircularEncoder.Callback, called on encoder thread
        @Override
        public void fileSaveComplete(int status) {
            sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null));
        }

        // CircularEncoder.Callback, called on encoder thread
        @Override
        public void bufferStatus(long totalTimeMsec) {
            sendMessage(obtainMessage(MSG_BUFFER_STATUS, (int) (totalTimeMsec >> 32), (int) totalTimeMsec));
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity activity = mWeakActivity.get();
            if (activity == null || activity.isDestroyed()) {
                Log.d(TAG, "Got message for dead activity");
                return;
            }

            switch (msg.what) {
                case MSG_BLINK_TEXT: {
                    // Attempting to make it blink by using setEnabled() doesn't work --
                    // it just changes the color.  We want to change the visibility.

                    int color = activity.mRecordingText.getTextColors().getDefaultColor();
                    if (color == darkColor) {
                    	if (CamcorderManager.Instance.mCircEncoder != null
                    			&& CamcorderManager.Instance.mCircEncoder.getState() != CircularEncoder.STATE_CACHE_CIRCULAR) {
                    		color = recColor;
                    	}
                    } else {
                        color = darkColor;
                    }
                    activity.mRecordingText.setTextColor(color);

                    int delay = (color != darkColor) ? 1000 : 200;
                    sendEmptyMessageDelayed(MSG_BLINK_TEXT, delay);
                    break;
                }
                case MSG_FILE_SAVE_COMPLETE: {
                    activity.fileSaveComplete(msg.arg1);
                    break;
                }
                case MSG_BUFFER_STATUS: {
                    long duration = (((long) msg.arg1) << 32) |
                                    (((long) msg.arg2) & 0xffffffffL);
                    activity.updateBufferStatus(duration);
                    break;
                }
                default:
                    throw new RuntimeException("Unknown message " + msg.what);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Instance = this;
        setContentView(R.layout.activity_continuous_capture);

        final Window win = getWindow();
        win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mContinuousCaptureSurfaceView = (SurfaceView) findViewById(R.id.continuousCapture_surfaceView);
        mRecordingButton = (ImageButton) findViewById(R.id.recording_button);
        mShootButton = (ImageButton) findViewById(R.id.shoot_button);
        mGalleryButton = (GalleryButton) findViewById(R.id.gallery_button);
        mTxtCapturedTime = (TextView) findViewById(R.id.txtCapturedTime);
        mRecordingText = (TextView) findViewById(R.id.recording_text);
        mBtnLight = (ImageButton)findViewById(R.id.btnLight);
        mBtnSettings = (ImageButton)findViewById(R.id.btnSettings);
        mTxtDebugMode = (TextView)findViewById(R.id.txtDebugMode);
        mBottomBar = (ViewGroup)findViewById(R.id.bottomBar);
        mTakedPicturesView = (TakedPicturesView)findViewById(R.id.takedPicturesView);
        mFullPreviewPicView = (ImageView)findViewById(R.id.fullPreviewPicView);
        mTakedPicturesView.setFullPreviewPicView(mFullPreviewPicView);

        mBottomBar.setBackgroundColor(bottomBgColor);
        mContinuousCaptureSurfaceView.setZOrderOnTop(false);
        mBtnLight.setOnClickListener(this);
        mBtnSettings.setOnClickListener(this);
        mRecordingButton.setOnClickListener(this);
        mShootButton.setOnClickListener(this);
        mGalleryButton.setOnClickListener(this);

        if (getIntent().hasExtra("DebugMode")) {
        	PrefUtils.setIsDebugMode(getIntent().getBooleanExtra("DebugMode", false));
        }
    	mTxtDebugMode.setVisibility(PrefUtils.getIsDebugMode() ? View.VISIBLE : View.GONE);

        mContinuousCaptureSurfaceView.getHolder().addCallback(this);

        mHandler = new MainHandler(this);
        setupBlinkText(true);

        updateControls();

        mMyOrientationEventListener = new MyOrientationEventListener(this.getApplicationContext());
    }

    private void setupBlinkText(boolean enable) {
    	mHandler.removeMessages(MainHandler.MSG_BLINK_TEXT);
    	if (enable) {
    		mHandler.sendEmptyMessageDelayed(MainHandler.MSG_BLINK_TEXT, 1500);
    	}
    }

    @Override
    protected void onDestroy() {
    	super.onDestroy();
    	if (Instance == this) Instance = null;
    	Process.killProcess(Process.myPid());
    }

    void changeOrientation(int orientationAngle) {
    	if (orientationAngle != OrientationEventListener.ORIENTATION_UNKNOWN) {
    		int orientation = angleNormalize(orientationAngle);
    		if (orientation != mOrientation) {
	        	mOrientation = orientation;

	        	int viewNewRotation = mOrientation == 0 ? 0 : 360 - mOrientation;
	        	int viewOldRotation = Math.round(mTxtCapturedTime.getRotation());
	        	if (viewOldRotation != viewNewRotation) {
	        		if (viewOldRotation == 0 && viewNewRotation == 270) {
	        			mTxtCapturedTime.setRotation(360);
	        			mRecordingText.setRotation(360);
	        		} else if (viewOldRotation == 270 && viewNewRotation == 0) {
	        			mTxtCapturedTime.setRotation(-90);
	        			mRecordingText.setRotation(-90);
	        		}

	        		mTxtCapturedTime.animate().rotation(viewNewRotation);
	            	mRecordingText.animate().rotation(viewNewRotation);
	        	}
    		}
    	}
    }

    private int angleNormalize(int angle) {
    	while (angle < 0) { angle += 360; }
    	while (angle > 360) { angle -= 360; }

    	if (angle <= 45) {
    		return 0;
    	} else if (angle <= 90 + 45) {
    		return 90;
    	} else if (angle <= 180 + 45) {
    		return 180;
    	} else if (angle <= 270 + 45) {
    		return 270;
    	}
    	return 0;
    }

    @Override
	public void onClick(View v) {
		if (v == mRecordingButton) {
	        if (CamcorderManager.Instance.isSaving()) {
	        	if (!CamcorderManager.Instance.isStoping()) {
	        		stopSaving();
	        	}
	        } else {
	        	startSaving();
	        }
		} else if (v == mShootButton) {
			shoot();
		} else if (v == mBtnLight) {
			if (!mTakedPicturesView.isVisible()) {
				mBtnLight.setSelected(!mBtnLight.isSelected());
				CamcorderManager.Instance.toggleTorch(mBtnLight.isSelected());
			}
		} else if (v == mBtnSettings) {
			gotoSettingsActivity();
		} else if (v == mGalleryButton) {
			gotoGalleryActivity();
		}
	}

    private void gotoGalleryActivity() {
    	if (!CamcorderManager.Instance.isSaving()) {
			Intent intent = new Intent(this, GalleryActivity.class);
			startActivity(intent);
    	}
    }

    private void gotoSettingsActivity() {
    	if (!CamcorderManager.Instance.isSaving() && !mTakedPicturesView.isVisible()) {
			Intent intent = new Intent(this, SettingsActivity.class);
			startActivity(intent);
    	}
    }

    private void shoot() {
    	mTakedPicturesView.reset();
    	mTakedPicturesView.show(true);
    	CamcorderManager.Instance.shoot();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
    	if (keyCode == KeyEvent.KEYCODE_MENU) {
    		gotoSettingsActivity();
    	}
    	return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
    	if (mTakedPicturesView.isVisible()) {
    		if (mTakedPicturesView.isFullPreviewPicView()) {
    			mTakedPicturesView.hideFullPreviewPicView();
    			return;
    		}
    		mTakedPicturesView.show(false);
    		return;
    	}
    	super.onBackPressed();
    }

    private void stopSaving() {
		mMyOrientationEventListener.enable();
		CamcorderManager.Instance.stopSaving();
		setupBlinkText(true);
    }

    public void onRecordStoped(String videoFile) {
    	mBtnLight.setVisibility(View.VISIBLE);
    	mBtnSettings.setVisibility(View.VISIBLE);
    	mShootButton.setVisibility(View.VISIBLE);
    	mGalleryButton.setVisibility(View.VISIBLE);
    	findViewById(R.id.separatorView2).setVisibility(View.VISIBLE);
    	findViewById(R.id.separatorView3).setVisibility(View.VISIBLE);
    	mBottomBar.setBackgroundColor(bottomBgColor);

        updateControls();

        PrefUtils.addTakedFile(videoFile);
    	mGalleryButton.refreshThumbnail();
    }

    private void startSaving() {
    	mBtnLight.setVisibility(View.INVISIBLE);
    	mBtnSettings.setVisibility(View.INVISIBLE);
    	mShootButton.setVisibility(View.GONE);
    	mGalleryButton.setVisibility(View.GONE);
    	findViewById(R.id.separatorView2).setVisibility(View.GONE);
    	findViewById(R.id.separatorView3).setVisibility(View.GONE);
    	mBottomBar.setBackground(null);

		mRecordingButton.setSelected(true);

		CamcorderManager.Instance.startSaving();

		setupBlinkText(true);
		mMyOrientationEventListener.disable();
    }

    @Override
    protected void onResume() {
        super.onResume();

        PrefUtils.notifyChanged();
    	mGalleryButton.refreshThumbnail();
        MobclickAgent.onResume(this);
        if (!initForResume()) {
        	Utilities.showFailDialog(this);
        }
    }

    private boolean initForResume() {
    	if (!CamcorderManager.Instance.openCamera()) return false;

        CamcorderManager.Instance.setupAudioRecord();
		mBtnLight.setEnabled(CamcorderManager.Instance.isSupportFlash());
		mMyOrientationEventListener.enable();

		if (mContinuousCaptureSurfaceView.getHolder().getSurface().isValid()) {
	        CamcorderManager.Instance.setupEncode(mContinuousCaptureSurfaceView.getHolder().getSurface(), mHandler,
	        		mContinuousCaptureSurfaceView.getWidth(), mContinuousCaptureSurfaceView.getHeight());
		}
		return true;
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mTakedPicturesView.isVisible()) {
        	mTakedPicturesView.onActivityPause();
        }
        MobclickAgent.onPause(this);
        CamcorderManager.Instance.releaseAllResource();
        mMyOrientationEventListener.disable();
    }

    /**
     * Updates the current state of the controls.
     */
    private void updateControls() {
    	if (!CamcorderManager.Instance.isSaving()) {
			mTxtCapturedTime.setVisibility(View.INVISIBLE);
			mRecordingText.setVisibility(View.INVISIBLE);
    	} else {
			mTxtCapturedTime.setVisibility(View.VISIBLE);
			mRecordingText.setVisibility(View.VISIBLE);

	    	int totalMinutes = Math.round(mSecondsOfVideo);
	    	int hour = totalMinutes / 60;
	    	int minutes = totalMinutes % 60;
	        String str = getString(R.string.secondsOfVideo, hour, minutes);
	        mTxtCapturedTime.setText(str);

            mRecordingText.setTextColor(recColor);
    	}
    }

    /**
     * The file save has completed.  We can resume recording.
     */
    private void fileSaveComplete(int status) {
        Log.d(TAG, "fileSaveComplete " + status);
		mRecordingButton.setSelected(false);
        updateControls();

        String str;
        if (status == 0) {
            str = getString(R.string.recordingSucceeded);
        } else {
            str = getString(R.string.recordingFailed, status);
        }
        Toast toast = Toast.makeText(this, str, Toast.LENGTH_SHORT);
        toast.show();
    }

    /**
     * Updates the buffer status UI.
     */
    private void updateBufferStatus(long durationUsec) {
        mSecondsOfVideo = durationUsec / 1000000.0f;
        updateControls();
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated holder=" + holder);
        CamcorderManager.Instance.setupEncode(holder.getSurface(), mHandler,
        		mContinuousCaptureSurfaceView.getWidth(), mContinuousCaptureSurfaceView.getHeight());
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height + " holder=" + holder);
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed holder=" + holder);
    }

    public void addTakedPictureThumbnail(Bitmap thumbnail, String fileName, boolean isUsed) {
    	mTakedPicturesView.addTakedPicture(thumbnail, fileName, isUsed);
    }

    public void onTakedPictureViewHide() {
    	mGalleryButton.refreshThumbnail();
    }

    public void onPictureSaveFinished() {
    	mGalleryButton.refreshThumbnail();
    }
}
