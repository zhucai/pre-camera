package com.twinfishlabs.precamera;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.umeng.analytics.MobclickAgent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnCancelListener;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory.Options;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.ViewConfiguration;

public class Utilities {

	static public final String TAG = "AdvanceRecorder";

	static public final boolean ENABLE_SYSTRACE = true;

	static private SimpleDateFormat sDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmssSSS");
	static private ViewConfiguration sViewConfig;
	private static int sScreenWidth;
	private static int sScreenHeight;

	static public Throwable sLastException;

	static public File getFolder() {
		File folder;
		if (PrefUtils.getIsDebugMode()) {
			folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "/AdvanceRecorder/");
		} else {
			folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "/Camera/");
		}
		if (!folder.exists()) folder.mkdirs();
		return folder;
	}

	static public File getVideoThumbnailFolder() {
		File folder = new File(MyApplication.Instance.getFilesDir(), "video_thumbnail");
		if (!folder.exists()) folder.mkdirs();
		return folder;
	}

	static public ViewConfiguration getViewConfig() {
		if (sViewConfig == null) {
			sViewConfig = ViewConfiguration.get(MyApplication.Instance);
		}
		return sViewConfig;
	}

	static public File getImageFile() {
		return getImageFile(new Date());
	}

	static public File getImageFile(Date date) {
		File folder = getFolder();

		File file = new File(folder, "IMG_"+getDateString(date)+".jpg");
		int i = 0;
		while (file.exists()) {
			file = new File(folder, "IMG_"+getDateString(date)+"_"+(++i)+".jpg");
		}
		return file;
	}

	static private String getDateString(Date date) {
		String str = sDateFormat.format(date);
		str = (String) str.subSequence(0, str.length() - 2);
		return str;
	}

	static public File getVideoFile(Date date) {
		return new File(getFolder(), "VID_"+sDateFormat.format(date)+".mp4");
	}

	static public File getVideoThumbnailFile(Date date) {
		return new File(getVideoThumbnailFolder(), "VID_"+sDateFormat.format(date)+".jpg");
	}

	static public File getThumbnailFileByVideo(File videoFile) {
		String videoName = videoFile.getName();
		return new File(getVideoThumbnailFolder(), videoName.substring(0, videoName.length() - 3) + "jpg");
	}

	static public boolean isVideoFile(String file) {
		return file.endsWith(".mp4");
	}

	static public File getImgFileFromImgOrVideo(File file) {
		if (isVideoFile(file.getAbsolutePath())) {
			return getThumbnailFileByVideo(file);
		}
		return file;
	}

	static public int dpToPx(int value) {
		return Math.round(dpToPx((float)value));
	}

	static public float dpToPx(float value) {
		return value * MyApplication.Instance.getResources().getDisplayMetrics().density;
	}

	static public int pxToDp(int value) {
		return Math.round(pxToDp((float)value));
	}

	static public float pxToDp(float value) {
		return value / MyApplication.Instance.getResources().getDisplayMetrics().density;
	}

	static public int getHeightKeepAspectRatio(int dstWidth, int srcHeight, int srcWidth) {
		return dstWidth * srcHeight / srcWidth;
	}

	static public int getViewInnerWidth(View v) {
		return v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
	}

	static public int getViewInnerHeight(View v) {
		return v.getHeight() - v.getPaddingTop() - v.getPaddingBottom();
	}

	static public int calcSampleSize(int dstWidth, int dstHeight, int srcWidth, int srcHeight) {
		// TODO: only use Height now
		float ratio = srcHeight / dstHeight;
		return Math.max((int)(ratio + 0.1f), 1);
	}

	static public Bitmap decode(String file, int dstWidth, int dstHeight, Options opts) {
		opts.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(file, opts);
		opts.inJustDecodeBounds = false;
		opts.inSampleSize = calcSampleSize(dstWidth, dstHeight, opts.outWidth, opts.outHeight);
		return BitmapFactory.decodeFile(file, opts);
	}

	static public void setSmallPictureWidth(int smallWidth) {
		if (sSmallPictureWidth == smallWidth) return;
		sSmallPictureWidth = smallWidth;
		sSmallPictureHeight = 0;
	}

	static private int sSmallPictureHeight;
	static public int getSmallPictureHeight() {
		if (sSmallPictureHeight == 0) {
			if (sSmallPictureWidth == 0) throw new RuntimeException("Not set smallWidth yet.");
			sSmallPictureHeight = Utilities.getHeightKeepAspectRatio(sSmallPictureWidth,
					CamcorderManager.Instance.getPreviewTextureWidth(), CamcorderManager.Instance.getPreviewTextureHeight());
			sSmallPictureHeight = sSmallPictureHeight / 2 * 2;
		}
		return sSmallPictureHeight;
	}

	static private int sSmallPictureWidth;
	static public int getSmallPictureWidth() {
		if (sSmallPictureWidth == 0) {
			if (sSmallPictureWidth == 0) throw new RuntimeException("Not set smallWidth yet.");
		}
		return sSmallPictureWidth;
	}

	static public void invalidateSmallPictureHeight() {
		sSmallPictureHeight = 0;
	}

	public static void setupBitmapCenterSquare(Bitmap bmp, Rect rect) {
		int w = bmp.getWidth();
		int h = bmp.getHeight();
		if (w < h) {
			int top = (h - w) / 2;
			rect.set(0, top, w, top + w);
		} else {
			int left = (w - h) / 2;
			rect.set(left, 0, left + h, h);
		}
	}

	public static Bitmap buildCenterSquareBitmap(Bitmap srcBmp, Bitmap outBmp) {
		Rect srcRect = new Rect();
		setupBitmapCenterSquare(srcBmp, srcRect);
		if (outBmp == null) outBmp = Bitmap.createBitmap(srcRect.width(), srcRect.height(), Config.ARGB_8888);
		Canvas canvas = new Canvas(outBmp);
		Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
		paint.setColor(0xFFFFFFFF);

		canvas.drawCircle(outBmp.getWidth()/2.f, outBmp.getHeight()/2.f, outBmp.getWidth()/2.f, paint);

		paint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
		Rect dstRect = new Rect(0, 0, outBmp.getWidth(), outBmp.getHeight());
		canvas.drawBitmap(srcBmp, srcRect, dstRect, paint);

		return outBmp;
	}

	public static boolean isEquals(Object obj1, Object obj2) {
		if (obj1 == obj2) return true;
		if (obj1 == null || obj2 == null) return false;
		return obj1.equals(obj2);
	}

	public static void sendBroadcastScanFile(File file) {
		Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file));
		MyApplication.Instance.sendBroadcast(intent);
	}

	public static byte[] newYuvBuffer(int imgW, int imgH) {
		return new byte[imgW * imgH * 3 / 2];
	}

	private static void initScreenSize() {
		int w = MyApplication.Instance.getResources().getDisplayMetrics().widthPixels;
		int h = MyApplication.Instance.getResources().getDisplayMetrics().heightPixels;
		sScreenWidth = Math.min(w, h);
		sScreenHeight = Math.max(w, h);
	}

	public static int getScreenWidth() {
		if (sScreenWidth == 0) {
			initScreenSize();
		}
		return sScreenWidth;
	}

	public static int getScreenHeight() {
		if (sScreenHeight == 0) {
			initScreenSize();
		}
		return sScreenHeight;
	}

	public static void showFailDialog(final Activity activity) {
    	new AlertDialog.Builder(activity)
			.setTitle(R.string.fail_dialog_title)
			.setMessage(R.string.fail_dialog_msg)
			.setPositiveButton(R.string.fail_dialog_button_quit , new AlertDialog.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					activity.finish();
				}
			})
			.setNegativeButton(R.string.fail_dialog_button_report, new AlertDialog.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					if (Utilities.sLastException != null) {
						MobclickAgent.reportError(activity, Utilities.sLastException);
					}
					activity.finish();
				}
			})
			.setCancelable(true)
			.setOnCancelListener(new OnCancelListener() {
				public void onCancel(DialogInterface dialog) {
					activity.finish();
				}
			})
			.create()
			.show();
	}
}
