package com.twinfishlabs.precamera;

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;

public class Caches {

	static Deque<SoftReference<Bitmap>> mSmallBitmapQueue = new ArrayDeque<SoftReference<Bitmap>>();
	static Deque<SoftReference<byte[]>> mSmallYuvBufferQueue = new ArrayDeque<SoftReference<byte[]>>();

	static WeakReference<Bitmap> mFullScreenPicCache;

	static public Bitmap getSmallBitmap(int w, int h, Config c) {
		Bitmap b = null;
		if (mSmallBitmapQueue.size() > 0) {
			b = mSmallBitmapQueue.pollLast().get();
		}
		if (b == null) b = Bitmap.createBitmap(w, h, c);
		return b;
	}

	static public void recycleSmallBitmap(Bitmap b) {
		mSmallBitmapQueue.add(new SoftReference<Bitmap>(b));
	}

	static public byte[] getSmallYuvBuffer(int imgW, int imgH) {
		byte[] b = null;
		if (mSmallYuvBufferQueue.size() > 0) {
			b = mSmallYuvBufferQueue.pollLast().get();
		}
		if (b == null) b = Utilities.newYuvBuffer(imgW, imgH);
		return b;
	}

	static public void recycleSmallBitmap(byte[] b) {
		mSmallYuvBufferQueue.add(new SoftReference<byte[]>(b));
	}

	static public Bitmap getFullScreenPic(int width, int height) {
		Bitmap bmp = mFullScreenPicCache == null ? null : mFullScreenPicCache.get();
		if (bmp != null && bmp.getWidth() == width && bmp.getHeight() == height) {
			return bmp;
		}
		return null;
	}

	static public void setFullScreenPic(Bitmap bmp) {
		if (mFullScreenPicCache == null || mFullScreenPicCache.get() != bmp) {
			mFullScreenPicCache = new WeakReference<Bitmap>(bmp);
		}
	}
}
