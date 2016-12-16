package com.twinfishlabs.precamera;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Paint.Style;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TimingLogger;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.twinfishlabs.precamera.ui.MyLinearLayout;

public class TakedPicturesView extends MyLinearLayout {

	static public final boolean DBG = true;
	static public final int SHOW_ANIMATE_DURATION_SHORT = 100;
	static public final int SHOW_ANIMATE_DURATION_LONG = 1500;

	private Interpolator mDecelerateInterpolator = new DecelerateInterpolator();
	private Interpolator mLinearInterpolator = new LinearInterpolator();
	private boolean mIsShow;
	private float mBgRectRoundRadius;
	private Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private RectF mBgRect;
	private H mHandler;
	private float mImgViewVerticalDistance;
	int[] mTmpLoc = new int[2];

	List<String> mTakedPicFiles = new ArrayList<String>();
	HashSet<String> mUnsavedPicFiles = new HashSet<String>();

	private ViewGroup mUnusedPicContainer;
	private ViewGroup mUsedPicContainer;

	private VelocityTracker mVelocityTracker = VelocityTracker.obtain();
	private float mTouchDownY;
	private long mTouchDownTime;
	ImageView mTouchedDownImgView;
	private ImageView mFullPreviewPicView;

	Runnable mOnCameraParamsChanged = new Runnable() {
		public void run() {
			Utilities.invalidateSmallPictureHeight();
			refreshPictureViewSize();
		}
	};

	private class H extends Handler {
		static final int MSG_HIDE_TIMER = 1;
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_HIDE_TIMER:
				show(false, SHOW_ANIMATE_DURATION_LONG);
				break;
			}
			super.handleMessage(msg);
		}
	}

	private OnTouchListener mImgTouchListener = new OnTouchListener() {
		public boolean onTouch(View v, MotionEvent event) {
			if (event.getAction() == MotionEvent.ACTION_DOWN) {
				mTouchedDownImgView = (ImageView)v;
			}
			return false;
		}
	};

	public TakedPicturesView(Context context) {
		super(context);
	}

	public TakedPicturesView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public TakedPicturesView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	protected void onFinishInflate() {
		mHandler = new H();
		mBgRectRoundRadius = Utilities.dpToPx(5.0f);
		mPaint.setStrokeWidth(Utilities.dpToPx(1.0f));

		mUnusedPicContainer = (ViewGroup)findViewById(R.id.unusedPicturesContainer);
		mUsedPicContainer = (ViewGroup)findViewById(R.id.usedPicturesContainer);

		for (int i = 0; i < mUnusedPicContainer.getChildCount(); i++) {
			initTakedPictureImageView((ImageView)mUnusedPicContainer.getChildAt(i));
		}
		for (int i = 0; i < mUsedPicContainer.getChildCount(); i++) {
			initTakedPictureImageView((ImageView)mUsedPicContainer.getChildAt(i));
		}

		super.onFinishInflate();
	}

	public void setFullPreviewPicView(ImageView view) {
		mFullPreviewPicView = view;
		mFullPreviewPicView.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				hideFullPreviewPicView();
			}
		});
	}

	public boolean isFullPreviewPicView() {
		return mFullPreviewPicView.getVisibility() == VISIBLE;
	}

	public void showFullPreviewPicView(final ImageView srcPicView) {
		mFullPreviewPicView.setTag(srcPicView);

		// load full screen bitmap
		String fileName = (String)(((View)mFullPreviewPicView.getTag()).getTag());
		Options ops = new Options();
		ops.inPreferredConfig = Config.RGB_565;
		ops.inSampleSize = Utilities.calcSampleSize(mFullPreviewPicView.getWidth(), mFullPreviewPicView.getHeight(),
				CamcorderManager.Instance.getPreviewTextureWidth(), CamcorderManager.Instance.getPreviewTextureHeight());
		ops.inBitmap = Caches.getFullScreenPic(mFullPreviewPicView.getWidth() / ops.inSampleSize, mFullPreviewPicView.getHeight() / ops.inSampleSize);
		Bitmap fullScreenBmp = BitmapFactory.decodeFile(fileName, ops);
		mFullPreviewPicView.setImageBitmap(fullScreenBmp);
		mFullPreviewPicView.setVisibility(VISIBLE);

		mFullPreviewPicView.setScaleX(srcPicView.getWidth()/(float)mFullPreviewPicView.getWidth());
		mFullPreviewPicView.setScaleY(srcPicView.getHeight()/(float)mFullPreviewPicView.getHeight());
		srcPicView.getLocationOnScreen(mTmpLoc);
		int pivotX = srcPicView.getWidth() * mTmpLoc[0] / (mFullPreviewPicView.getWidth() - srcPicView.getWidth()) + mTmpLoc[0];
		int pivotY = srcPicView.getHeight() * mTmpLoc[1] / (mFullPreviewPicView.getHeight() - srcPicView.getHeight()) + mTmpLoc[1];
		mFullPreviewPicView.setPivotX(pivotX);
		mFullPreviewPicView.setPivotY(pivotY);

		post(new Runnable() {
			public void run() {
				// start scale animator
				mFullPreviewPicView.animate()
					.scaleX(1.f)
					.scaleY(1.f)
					.setDuration(150)
					.withEndAction(null)
					.start();
			}
		});
	}

	public void hideFullPreviewPicView() {
		ImageView srcPicView = (ImageView)mFullPreviewPicView.getTag();
		mFullPreviewPicView.animate()
			.scaleX(srcPicView.getWidth()/(float)mFullPreviewPicView.getWidth())
			.scaleY(srcPicView.getHeight()/(float)mFullPreviewPicView.getHeight())
			.withEndAction(new Runnable() {
				public void run() {
					Caches.setFullScreenPic(((BitmapDrawable)mFullPreviewPicView.getDrawable()).getBitmap());
					mFullPreviewPicView.setVisibility(INVISIBLE);
					mFullPreviewPicView.setImageDrawable(null);
				}
			})
			.start();
	}

	private void initTakedPictureImageView(ImageView imgView) {
		imgView.setOnTouchListener(mImgTouchListener);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		mBgRect = new RectF(0, 0, w, h);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		CamcorderManager.Instance.setOnPreviewParamsChanged(mOnCameraParamsChanged);
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		CamcorderManager.Instance.setOnPreviewParamsChanged(null);
	}

	private void refreshPictureViewSize() {
		if (CamcorderManager.Instance.getPreviewTextureWidth() == 0) return;
		if (getWidth() == 0) return;
		View fistView = mUsedPicContainer.getChildAt(0);
		if (CamcorderManager.Instance.getPreviewTextureWidth() == Utilities.getViewInnerWidth(fistView)
				|| CamcorderManager.Instance.getPreviewTextureHeight() == Utilities.getViewInnerHeight(fistView)) return;

		int smallWidth = Utilities.getViewInnerWidth(fistView);
		smallWidth = smallWidth / 2 * 2;
		Utilities.setSmallPictureWidth(smallWidth);
		int smallHeight = Utilities.getSmallPictureHeight();

		for (int i = 0; i < mUnusedPicContainer.getChildCount(); i++) {
			View v = mUnusedPicContainer.getChildAt(i);
			LinearLayout.LayoutParams l = (LinearLayout.LayoutParams)v.getLayoutParams();
			l.width = smallWidth + v.getPaddingLeft() + v.getPaddingRight();
			l.height = smallHeight + v.getPaddingTop() + v.getPaddingBottom();
			l.weight = 0;
		}
		for (int i = 0; i < mUsedPicContainer.getChildCount(); i++) {
			View v = mUsedPicContainer.getChildAt(i);
			LinearLayout.LayoutParams l = (LinearLayout.LayoutParams)v.getLayoutParams();
			l.width = smallWidth + v.getPaddingLeft() + v.getPaddingRight();
			l.height = smallHeight + v.getPaddingTop() + v.getPaddingBottom();
			l.weight = 0;
		}
		requestLayout();
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		super.onLayout(changed, l, t, r, b);

		Utilities.setSmallPictureWidth(Utilities.getViewInnerWidth(mUsedPicContainer.getChildAt(0)));
		refreshPictureViewSize();

		mUsedPicContainer.getChildAt(0).getLocationInWindow(mTmpLoc);
		int usedY = mTmpLoc[1];
		mUnusedPicContainer.getChildAt(0).getLocationInWindow(mTmpLoc);
		mImgViewVerticalDistance = (usedY - mTmpLoc[1]);
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {
		super.dispatchTouchEvent(ev);
		mVelocityTracker.addMovement(ev);

		switch (ev.getAction()) {
		case MotionEvent.ACTION_DOWN: {
			mHandler.removeMessages(H.MSG_HIDE_TIMER);
			if (!mIsShow) {
				animate().cancel();
				show(true, 0);
			}
			mTouchDownY = ev.getY();
			mTouchDownTime = System.currentTimeMillis();
			break;
		}

		case MotionEvent.ACTION_MOVE: {
			if (mTouchedDownImgView != null) {
				float tranY = ev.getY() - mTouchDownY;

				if (isUsed(mTouchedDownImgView)) tranY = Math.min(tranY, 0.f);
				else tranY = Math.max(tranY, 0.f);

				mTouchedDownImgView.setTranslationY(tranY);
			}
			break;
		}

		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_CANCEL: {
			if (mTouchedDownImgView != null) {
				if (System.currentTimeMillis() - mTouchDownTime <= Utilities.getViewConfig().getTapTimeout()
						&& Math.abs(ev.getY() - mTouchDownY) <= Utilities.getViewConfig().getScaledTouchSlop()) {
					mTouchedDownImgView.setTranslationY(0.f);
					showFullPreviewPicView(mTouchedDownImgView);
				} else {
					float dstTranY = 0.f;
					if (ev.getAction() != MotionEvent.ACTION_CANCEL
							&& isScrollOvered(ev.getY())) {
						dstTranY = isUsed(mTouchedDownImgView) ? -mImgViewVerticalDistance : mImgViewVerticalDistance;
					}
					final boolean isToBack = dstTranY == 0.f;
					final ImageView animateView = mTouchedDownImgView;
					mTouchedDownImgView.animate()
						.setInterpolator(mDecelerateInterpolator)
						.translationY(dstTranY)
						.setDuration(80)
						.withEndAction(new Runnable() {
							public void run() {
								if (!isToBack) {
									animateView.setTranslationY(0);
									toggleUseState(animateView);
								}
							}
						})
						.start();
				}
				mTouchedDownImgView = null;
			}
			mVelocityTracker.clear();
			break;
		}
		}

		return true;
	}

	private void toggleUseState(ImageView srcImgView) {
		ViewGroup srcContainer;
		ViewGroup dstContainer;
		if (isUsed(srcImgView)) {
			srcContainer = mUsedPicContainer;
			dstContainer = mUnusedPicContainer;
			mUnsavedPicFiles.add((String)srcImgView.getTag());
		} else {
			srcContainer = mUnusedPicContainer;
			dstContainer = mUsedPicContainer;
			mUnsavedPicFiles.remove(srcImgView.getTag());
		}
		int index = srcContainer.indexOfChild(srcImgView);
		ImageView dstImgView = (ImageView)dstContainer.getChildAt(index);
		dstImgView.setImageDrawable(srcImgView.getDrawable());
		srcImgView.setImageDrawable(null);
		dstImgView.setVisibility(VISIBLE);
		srcImgView.setVisibility(INVISIBLE);
		dstImgView.setTag(srcImgView.getTag());
		srcImgView.setTag(null);
	}

	private boolean isScrollOvered(float y) {
		mVelocityTracker.computeCurrentVelocity(1);
		final float thresholdVelocity = 1.2f;
		float tranY = y - mTouchDownY;
		float velocityY = mVelocityTracker.getYVelocity();

		if (isUsed(mTouchedDownImgView)) {
			if ((tranY < -mImgViewVerticalDistance/2 && velocityY < thresholdVelocity/2)
					|| (velocityY < -thresholdVelocity)) {
				return true;
			}
		} else {
			if ((tranY > mImgViewVerticalDistance/2 && velocityY > -thresholdVelocity/2)
					|| (velocityY > thresholdVelocity)) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected void dispatchDraw(Canvas canvas) {
		/* draw background */
		mPaint.setStyle(Style.FILL);
		mPaint.setColor(0xDD552222);
		canvas.save(Canvas.CLIP_SAVE_FLAG);
		canvas.clipRect(0, 0, getWidth(), getHeight()/2);
		canvas.drawRoundRect(mBgRect, mBgRectRoundRadius, mBgRectRoundRadius, mPaint);
		canvas.restore();

		canvas.save(Canvas.CLIP_SAVE_FLAG);
		canvas.clipRect(0, getHeight()/2, getWidth(), getHeight());
		mPaint.setColor(0xDD222222);
		canvas.drawRoundRect(mBgRect, mBgRectRoundRadius, mBgRectRoundRadius, mPaint);
		canvas.restore();

		/* super draw */
		super.dispatchDraw(canvas);
	}

	public void show(boolean isShow) {
		show(isShow, SHOW_ANIMATE_DURATION_SHORT);
	}

	public void show(boolean isShow, int animatorDuration) {
		if (isShow) {
			setVisibility(VISIBLE);
			if (animatorDuration == 0) {
				setAlpha(1.f);
			} else {
				mHandler.removeMessages(H.MSG_HIDE_TIMER);
				setAlpha(0.f);
				animate()
					.alpha(1.f)
					.setDuration(animatorDuration)
					.setInterpolator(mDecelerateInterpolator)
					.withEndAction(null)
					.start();
			}
		} else {
			mHandler.removeMessages(H.MSG_HIDE_TIMER);
			if (animatorDuration == 0) {
				setVisibility(INVISIBLE);
				reset();
			} else {
				animate()
					.alpha(0.f)
					.setDuration(animatorDuration)
					.setInterpolator(mLinearInterpolator)
					.withEndAction(new Runnable() {
						public void run() {
							post(new Runnable() {
								public void run() {
									setAlpha(1.f);
									setVisibility(INVISIBLE);
									reset();
								}
							});
						}
					})
					.start();
			}
		}

		mIsShow = isShow;
	}

	public boolean isVisible() {
		return getVisibility() == VISIBLE;
	}

	public void persistAllFiles() {
		for (String fileName : mUnsavedPicFiles) {
			fileName.substring(0);
			try {
				File file = new File(fileName);
				file.delete();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		List<String> list = new ArrayList<String>();
		for (int i = 0; i < mUsedPicContainer.getChildCount(); i++) {
			String fileName = (String)mUsedPicContainer.getChildAt(i).getTag();
			if (!TextUtils.isEmpty(fileName)) {
				list.add(fileName);
				Utilities.sendBroadcastScanFile(new File(fileName));
			}
		}
		PrefUtils.addTakedFiles(list);
		mUnsavedPicFiles.clear();
	}

	public void reset() {
		persistAllFiles();

		MainActivity.Instance.onTakedPictureViewHide();

		final int N = mTakedPicFiles.size();
		mTakedPicFiles.clear();
		for (int i = 0; i < N; i++) {
			ImageView v = (ImageView)mUnusedPicContainer.getChildAt(i);
			v.setImageDrawable(null);
			v.setVisibility(INVISIBLE);
			v = (ImageView)mUsedPicContainer.getChildAt(i);
			v.setVisibility(INVISIBLE);
			recycleOld(v);
		}
	}

	private boolean isUsed(ImageView imgView) {
		return imgView.getParent() == mUsedPicContainer;
	}

	public void addTakedPicture(Bitmap bitmap, String fileName, boolean isUsed) {
		mTakedPicFiles.add(fileName);
		int thisIndex = mTakedPicFiles.size() - 1;
		ViewGroup container = isUsed ? mUsedPicContainer : mUnusedPicContainer;
		ImageView imgView = (ImageView)container.getChildAt(thisIndex);
		recycleOld(imgView);
		imgView.setTag(fileName);
		imgView.setImageBitmap(bitmap);
		imgView.setVisibility(VISIBLE);

		imgView.setAlpha(0.f);
		imgView.animate()
			.alpha(1.f)
			.translationY(0.f)
			.setDuration(100)
			.setInterpolator(mLinearInterpolator)
			.start();

		if (!isUsed) {
			mUnsavedPicFiles.add(fileName);
		}
		if (isUsed) {
			mHandler.sendEmptyMessageDelayed(H.MSG_HIDE_TIMER, 2000);
		}
	}

	private void recycleOld(ImageView imgView) {
		Drawable d = imgView.getDrawable();
		if (d instanceof BitmapDrawable) {
			imgView.setImageDrawable(null);
			Caches.recycleSmallBitmap(((BitmapDrawable)d).getBitmap());
		}
		imgView.setTag(null);
	}

	public void onActivityPause() {
		mHandler.removeMessages(H.MSG_HIDE_TIMER);
	}
}
