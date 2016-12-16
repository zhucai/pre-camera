package com.twinfishlabs.precamera;

import java.io.File;
import java.util.List;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.BitmapFactory.Options;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.util.Log;

import com.twinfishlabs.precamera.ui.MyImageView;

public class GalleryButton extends MyImageView {

	private String mFilePath;
	private Bitmap mThumbnail;
	private Paint mPaint = new Paint();

	public GalleryButton(Context context) {
		super(context);
	}

	public GalleryButton(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public GalleryButton(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);

		if (w > 0 && h > 0) {
			loadThumbnail();
		}
	}

	private int getInnerWidth() {
		return getWidth() - getPaddingLeft() - getPaddingRight();
	}

	private int getInnerHeight() {
		return getHeight() - getPaddingTop() - getPaddingBottom();
	}

	public void loadThumbnail() {
		if (getInnerWidth() <= 0 || getInnerHeight() <= 0) return;

		List<String> list = PrefUtils.getTakedFiles();
		mFilePath = null;
		mThumbnail = null;
		if (list.size() > 0) {
			for (int i = list.size() - 1; i >= 0; i--) {
				mFilePath = list.get(i);
				File file = new File(mFilePath);
				File imgFile = Utilities.getImgFileFromImgOrVideo(file);
				Bitmap bmp = Utilities.decode(imgFile.getAbsolutePath(), getInnerWidth(), getInnerHeight(), new Options());
				if (bmp == null) {
					Log.d("ZC", "bmp == null："+mFilePath);
					mFilePath = null;
					continue;
				} else {
					Log.d("ZC", "bmp != null: "+mFilePath);
					mThumbnail = Bitmap.createBitmap(getInnerWidth(), getInnerHeight(), Config.ARGB_8888);
					mThumbnail = Utilities.buildCenterSquareBitmap(bmp, mThumbnail);
					break;
				}
			}
		}
		invalidate();
	}

	@Override
	protected void drawableStateChanged() {
		super.drawableStateChanged();
		invalidate();
	}

	@Override
	public void draw(Canvas canvas) {
		if (mThumbnail == null) {
			super.draw(canvas);
		} else {
			if (isPressed()) {
				mPaint.setColorFilter(new LightingColorFilter(Color.argb(255, 150, 150, 150), 0));
			} else {
				mPaint.setColorFilter(null);
			}
			canvas.drawBitmap(mThumbnail, getPaddingLeft(), getPaddingTop(), mPaint);
		}
	}

	public void refreshThumbnail() {
		List<String> list = PrefUtils.getTakedFiles();
		String lastFile = list.size() == 0 ? null : list.get(list.size() - 1);
		if (!Utilities.isEquals(lastFile, mFilePath)) {
			loadThumbnail();
		}
	}
}
