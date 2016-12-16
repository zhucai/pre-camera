package com.twinfishlabs.precamera.gallery;

import java.io.File;
import java.util.List;

import com.twinfishlabs.precamera.PrefUtils;
import com.twinfishlabs.precamera.Utilities;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.BitmapFactory.Options;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.util.Property;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class PictureScrollView extends View {

	private int mGap;
	private Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
	private TimeInterpolator mInterpolator = new DecelerateInterpolator();
	private Rect mSrcRect = new Rect();
	private Rect mDstRect = new Rect();
	private Rect mPlaceholderRect = new Rect();

	private int mCurrIndex = -1;
	private Runnable mCurrIndexChanged;
	private Bitmap mPrevBitmap;
	private Bitmap mCurrBitmap;
	private Bitmap mNextBitmap;
	private PreloadPictureAsyncTask mPreloadTask;

	private VelocityTracker mVelocityTracker = VelocityTracker.obtain();
	private long mTouchDownTime;
	private float mTouchDownX;
	private float mTouchDownY;
	float mTransX;

	public static final Property<PictureScrollView, Float> TRANS_X = new Property<PictureScrollView, Float>(Float.class, "trans_x") {
		@Override
		public Float get(PictureScrollView object) {
			return object.mTransX;
		}
		@Override
		public void set(PictureScrollView object, Float value) {
			object.mTransX = value;
			object.invalidate();
		}
	};

	class PreloadPictureAsyncTask extends AsyncTask<Object, Void, Bitmap> {
		private String mFileName;
		private int mIndex;
		@Override
		protected Bitmap doInBackground(Object... params) {
			mFileName = (String)params[0];
			mIndex = (Integer)params[1];
			return loadBitmap(mFileName);
		}
		@Override
		protected void onPostExecute(Bitmap result) {
			if (mIndex == mCurrIndex + 1) {
				mNextBitmap = result;
			} else if (mIndex == mCurrIndex) {
				mCurrBitmap = result;
			} else if (mIndex == mCurrIndex - 1) {
				mPrevBitmap = result;
			}
			invalidate();
			mPreloadTask = null;
		}
	}

	public PictureScrollView(Context context) {
		super(context);
	}

	public PictureScrollView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public PictureScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();

		mGap = Utilities.dpToPx(20);
		mPaint.setColor(0xFF222222);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		if (oldw == 0 && oldh == 0) {
			mPlaceholderRect.set(0, 0, getWidth(), getHeight());
			List<String> list = PrefUtils.getTakedFiles();
			if (list.size() > 0) {
				mCurrIndex = 0;
				String file = getCurrFile();
				mCurrBitmap = loadBitmap(file);
				startLoadNext();
				if (mCurrIndexChanged != null) mCurrIndexChanged.run();
			}
		}
	}

	public String getFileByIndex(int index) {
		List<String> list = PrefUtils.getTakedFiles();
		return list.get(list.size() - mCurrIndex - 1);
	}

	public void setCurrIndexChanged(Runnable r) {
		mCurrIndexChanged = r;
	}

	Bitmap loadBitmap(String file) {
		File imgFile = Utilities.getImgFileFromImgOrVideo(new File(file));
		return Utilities.decode(imgFile.getAbsolutePath(), getWidth(), getHeight(), new Options());
	}

	private void startLoadNext() {
		if (mPreloadTask == null && mNextBitmap == null && !isRightMost()) {
			int nextIndex = mCurrIndex + 1;
			mPreloadTask = new PreloadPictureAsyncTask();
			List<String> list = PrefUtils.getTakedFiles();
			mPreloadTask.execute(list.get(list.size() - nextIndex - 1), nextIndex);
		}
	}

	private void startLoadPrev() {
		if (mPreloadTask == null && mPrevBitmap == null && !isLeftMost()) {
			int prevIndex = mCurrIndex - 1;
			mPreloadTask = new PreloadPictureAsyncTask();
			List<String> list = PrefUtils.getTakedFiles();
			mPreloadTask.execute(list.get(list.size() - prevIndex - 1), prevIndex);
		}
	}

	@Override
	public void draw(Canvas canvas) {
		canvas.translate(mTransX, 0);
		if (mCurrBitmap != null) {
			mSrcRect.set(0, 0, mCurrBitmap.getWidth(), mCurrBitmap.getHeight());
			calcAspectRect(mSrcRect, mPlaceholderRect, mDstRect);
			canvas.drawBitmap(mCurrBitmap, mSrcRect, mDstRect, mPaint);
		} else {
			canvas.drawRect(mPlaceholderRect, mPaint);
		}
		if (mTransX < 0) {
			canvas.translate(mPlaceholderRect.width() + mGap, 0);
			if (mNextBitmap != null) {
				mSrcRect.set(0, 0, mNextBitmap.getWidth(), mNextBitmap.getHeight());
				calcAspectRect(mSrcRect, mPlaceholderRect, mDstRect);
				canvas.drawBitmap(mNextBitmap, mSrcRect, mDstRect, mPaint);
			} else if (!isRightMost()) {
				canvas.drawRect(mPlaceholderRect, mPaint);
			}
		} else if (mTransX > 0) {
			canvas.translate(-mPlaceholderRect.width() - mGap, 0);
			if (mPrevBitmap != null) {
				mSrcRect.set(0, 0, mPrevBitmap.getWidth(), mPrevBitmap.getHeight());
				calcAspectRect(mSrcRect, mPlaceholderRect, mDstRect);
				canvas.drawBitmap(mPrevBitmap, mSrcRect, mDstRect, mPaint);
			} else if (!isLeftMost()) {
				canvas.drawRect(mPlaceholderRect, mPaint);
			}
		}
	}

	static void calcAspectRect(Rect srcRect, Rect maxRect, Rect dstRect) {
		if ((float)maxRect.width() / srcRect.width() < (float)maxRect.height() / srcRect.height()) {
			int dstHeight = srcRect.height() * maxRect.width() / srcRect.width();
			int dstTop = maxRect.top + (maxRect.height() - dstHeight) / 2;
			dstRect.set(maxRect.left, dstTop, maxRect.right, dstTop + dstHeight);
		} else {
			int dstWidth = srcRect.width() * maxRect.height() / srcRect.height();
			int dstLeft = maxRect.left + (maxRect.width() - dstWidth) / 2;
			dstRect.set(dstLeft, maxRect.top, dstLeft + dstWidth, maxRect.bottom);
		}
	}

	private boolean isLeftMost() {
		return mCurrIndex <= 0;
	}

	private boolean isRightMost() {
		return mCurrIndex >= PrefUtils.getTakedFiles().size() - 1;
	}

	public boolean isIndexValid() {
		return mCurrIndex >= 0 && mCurrIndex <= PrefUtils.getTakedFiles().size() - 1;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		mVelocityTracker.addMovement(event);

		switch (event.getAction() & MotionEvent.ACTION_MASK) {
		case MotionEvent.ACTION_DOWN:
			mTouchDownX = event.getX();
			mTouchDownY = event.getY();
			mTouchDownTime = System.currentTimeMillis();
			mTransX = 0;
			break;

		case MotionEvent.ACTION_MOVE:
			mTransX = event.getX() - mTouchDownX;
			invalidate();
			break;

		case MotionEvent.ACTION_CANCEL:
		case MotionEvent.ACTION_UP:
			if (System.currentTimeMillis() - mTouchDownTime <= Utilities.getViewConfig().getTapTimeout()
					&& Math.abs(event.getX() - mTouchDownX) <= Utilities.getViewConfig().getScaledTouchSlop()
					&& Math.abs(event.getY() - mTouchDownY) <= Utilities.getViewConfig().getScaledTouchSlop()) {
				// trigger click
				mTransX = 0;
				invalidate();
				performClick();
			} else {
				mVelocityTracker.computeCurrentVelocity(1);
				final float thresholdVelocity = 1.2f;
				float movedX = event.getX() - mTouchDownX;
				float velocityX = mVelocityTracker.getXVelocity();

				if (((movedX < -getWidth()/2 && velocityX < thresholdVelocity/2) || (velocityX < -thresholdVelocity))
						&& !isRightMost()) {
					ObjectAnimator animator = ObjectAnimator.ofFloat(this, TRANS_X, -getWidth() - mGap);
					animator.setInterpolator(mInterpolator);
					animator.addListener(new AnimatorListenerAdapter() {
						public void onAnimationEnd(Animator animation) {
							mCurrIndex++;
							mPrevBitmap = mCurrBitmap;
							mCurrBitmap = mNextBitmap;
							mNextBitmap = null;
							mTransX = 0;
							invalidate();
							startLoadNext();
							if (mCurrIndexChanged != null) mCurrIndexChanged.run();
						}
					});
					animator.start();
				} else if (((movedX > getWidth()/2 && velocityX > -thresholdVelocity/2) || (velocityX > thresholdVelocity))
						&& !isLeftMost()) {
					ObjectAnimator animator = ObjectAnimator.ofFloat(this, TRANS_X, getWidth() + mGap);
					animator.setInterpolator(mInterpolator);
					animator.addListener(new AnimatorListenerAdapter() {
						public void onAnimationEnd(Animator animation) {
							mCurrIndex--;
							mNextBitmap = mCurrBitmap;
							mCurrBitmap = mPrevBitmap;
							mPrevBitmap = null;
							mTransX = 0;
							invalidate();
							startLoadPrev();
							if (mCurrIndexChanged != null) mCurrIndexChanged.run();
						}
					});
					animator.start();
				} else {
					ObjectAnimator animator = ObjectAnimator.ofFloat(this, TRANS_X, 0);
					animator.setInterpolator(mInterpolator);
					animator.start();
				}
			}
			mVelocityTracker.clear();
			break;
		}
		return true;
	}

	public int getCurrIndex() {
		return mCurrIndex;
	}

	public String getCurrFile() {
		if (isIndexValid()) {
			return getFileByIndex(mCurrIndex);
		}
		return null;
	}

	public void onCurrFileDeleted() {
		if (!isIndexValid()) {
			mCurrIndex--;
			mCurrBitmap = mPrevBitmap;
			mPrevBitmap = null;
			startLoadPrev();
		} else {
			mCurrBitmap = mNextBitmap;
			mNextBitmap = null;
			startLoadNext();
		}
		invalidate();
		if (mCurrIndexChanged != null) mCurrIndexChanged.run();
	}
}
