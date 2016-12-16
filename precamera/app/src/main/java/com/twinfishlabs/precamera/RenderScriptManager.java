package com.twinfishlabs.precamera;

import java.util.Arrays;

import com.twinfishlabs.precamcorder.ScriptC_rotation_bitmap;
import com.twinfishlabs.precamcorder.ScriptC_rotation_yuv_bitmap;
import com.twinfishlabs.precamcorder.ScriptC_scale_down_yuv_bitmap;

import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptGroup;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;

public class RenderScriptManager {

	static final boolean IS_USE_GROUP = true;

	RenderScript mRS;
	ScriptIntrinsicYuvToRGB mScriptYuvToRgb;
	Allocation mYuvToRgbIn;
	Allocation mYuvToRgbOut;

	ScriptC_rotation_bitmap mScriptRotationBitmap;
	Allocation mAllocFinalBitmap;

	Allocation mAllocRotateYuvIn;
	Allocation mAllocRotateYuvOut;
	ScriptC_rotation_yuv_bitmap mScriptRotateYuv;

	Allocation mAllocScaleDownIn;
	Allocation mAllocScaleDownOut;
	ScriptC_scale_down_yuv_bitmap mScriptScaleDownYuv;

	ScriptGroup mScriptGroup;

	public RenderScriptManager() {
		mRS = RenderScript.create(MyApplication.Instance);
	}

	public void scaleDownYuv(byte[] srcYuvData, int srcImgW, int srcImgH,
			byte[] dstYuvData, int dstImgW, int dstImgH) {
		if (mScriptScaleDownYuv == null) {
			mScriptScaleDownYuv = new ScriptC_scale_down_yuv_bitmap(mRS);
			mAllocScaleDownIn = Allocation.createTyped(mRS,
					new Type.Builder(mRS, Element.U8(mRS)).setX(srcImgW).setY(srcImgH+srcImgH/2).create(),
					Allocation.USAGE_SCRIPT);
			mAllocScaleDownOut = Allocation.createTyped(mRS,
					new Type.Builder(mRS, Element.U8(mRS)).setX(dstImgW).setY(dstImgH+dstImgH/2).create(),
					Allocation.USAGE_SCRIPT);
			mScriptScaleDownYuv.set_in(mAllocScaleDownIn);

			mScriptScaleDownYuv.set_srcImgWidth(srcImgW);
			mScriptScaleDownYuv.set_srcImgHeight(srcImgH);
			mScriptScaleDownYuv.set_dstImgWidth(dstImgW);
			mScriptScaleDownYuv.set_dstImgHeight(dstImgH);
			mScriptScaleDownYuv.set_widthRatio(srcImgW/(float)dstImgW);
			mScriptScaleDownYuv.set_heightRatio(srcImgH/(float)dstImgH);
		}
		mAllocScaleDownIn.copyFrom(srcYuvData);
		mScriptScaleDownYuv.forEach_root(mAllocScaleDownOut);
		mAllocScaleDownOut.copyTo(dstYuvData);
	}

	public void testScaleDownYuv() {
		byte[] yuvData = new byte[] {
				1, 2, 3, 4, 5, 6, 7, 8,
				11, 12, 13, 14, 15, 16, 17, 18,
				21, 22, 23, 24, 25, 26, 27, 28,
				31, 32, 33, 34, 35, 36, 37, 38,
				51, 52, 53, 54, 55, 56, 57, 58,
				61, 62, 63, 64, 65, 66, 67, 68
		};
		byte[] out = new byte[yuvData.length];
		int dstImgW = 4;
		int dstImgH = 2;
		byte[] dstYuvData = new byte[dstImgW * dstImgH * 3 / 2];
		scaleDownYuv(yuvData, 8, 4, dstYuvData, dstImgW, dstImgH);
		Log.d("ZC", "yuvData:"+Arrays.toString(dstYuvData));
	}

	public void yuvRotate(byte[] yuvData, int imgW, int imgH, int angleDegrees) {
		if (angleDegrees == 0)  return;

		if (mScriptRotateYuv == null) {
			mScriptRotateYuv = new ScriptC_rotation_yuv_bitmap(mRS);
		}
		mAllocRotateYuvIn = Allocation.createTyped(mRS,
				new Type.Builder(mRS, Element.U8(mRS)).setX(imgW).setY(imgH+imgH/2).create(),
				Allocation.USAGE_SCRIPT);
		mAllocRotateYuvOut = Allocation.createTyped(mRS,
				new Type.Builder(mRS, Element.U8(mRS)).setX(imgH).setY(imgW+imgW/2).create(),
				Allocation.USAGE_SCRIPT);
		mScriptRotateYuv.set_in(mAllocRotateYuvIn);
		mScriptRotateYuv.set_srcImgWidth(imgW);
		mScriptRotateYuv.set_srcImgHeight(imgH);
		mScriptRotateYuv.set_srcImgHeightHalfMinusOne(imgH/2-1);
		mScriptRotateYuv.set_srcImgHeightMinusOne(imgH-1);

		mAllocRotateYuvIn.copyFrom(yuvData);
		mScriptRotateYuv.forEach_root(mAllocRotateYuvOut);
		mAllocRotateYuvOut.copyTo(yuvData);

		yuvRotate(yuvData, imgH, imgW, angleDegrees - 90);
	}

	public void testYuvRotate() {
		byte[] yuvData = new byte[] {
				1, 2, 3, 4, 5, 6, 7, 8,
				11, 12, 13, 14, 15, 16, 17, 18,
				21, 22, 23, 24, 25, 26, 27, 28,
				31, 32, 33, 34, 35, 36, 37, 38,
				51, 52, 53, 54, 55, 56, 57, 58,
				61, 62, 63, 64, 65, 66, 67, 68
		};
		yuvRotate(yuvData, 8, 4, 90);
		Log.d("ZC", "yuvData:"+Arrays.toString(yuvData));
	}

	public void yuvToRgb(byte[] yuvData, Bitmap finalBitmap) {
		int w = finalBitmap.getWidth();
		int h = finalBitmap.getHeight();

		if (mScriptYuvToRgb == null) {
			mScriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(mRS, Element.RGBA_8888(mRS));
		}

		if (mYuvToRgbIn == null ||
				yuvData.length != mYuvToRgbIn.getType().getCount()) {
			Type.Builder yuvType = new Type.Builder(mRS, Element.U8(mRS)).setX(yuvData.length);
			mYuvToRgbIn = Allocation.createTyped(mRS, yuvType.create(), Allocation.USAGE_SCRIPT);
		    mScriptYuvToRgb.setInput(mYuvToRgbIn);
		}

		if (mYuvToRgbOut == null ||
				mYuvToRgbOut.getType().getX() != w || mYuvToRgbOut.getType().getY() != h) {
		    Type.Builder rgbaType = new Type.Builder(mRS, Element.RGBA_8888(mRS)).setX(w).setY(h);
		    mYuvToRgbOut = Allocation.createTyped(mRS, rgbaType.create(), Allocation.USAGE_SCRIPT);
		}

	    mYuvToRgbIn.copyFrom(yuvData);
	    mScriptYuvToRgb.forEach(mYuvToRgbOut);
	    mYuvToRgbOut.copyTo(finalBitmap);
	}

	public void yuvToRgbAndRotate(byte[] yuvData, Bitmap finalBitmap) {
		int yuvDataW = finalBitmap.getHeight();
		int yuvDataH = finalBitmap.getWidth();

		if (mScriptYuvToRgb == null) {
			mScriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(mRS, Element.RGBA_8888(mRS));
			mScriptRotationBitmap = new ScriptC_rotation_bitmap(mRS);
		}

		if (mYuvToRgbIn == null ||
				yuvData.length != mYuvToRgbIn.getType().getCount()) {
			Type.Builder yuvType = new Type.Builder(mRS, Element.U8(mRS)).setX(yuvData.length);
			mYuvToRgbIn = Allocation.createTyped(mRS, yuvType.create(), Allocation.USAGE_SCRIPT);
		    mScriptYuvToRgb.setInput(mYuvToRgbIn);
		}

		if (mYuvToRgbOut == null ||
				mYuvToRgbOut.getType().getX() != yuvDataW || mYuvToRgbOut.getType().getY() != yuvDataH) {
		    Type.Builder rgbaType = new Type.Builder(mRS, Element.RGBA_8888(mRS)).setX(yuvDataW).setY(yuvDataH);
		    mYuvToRgbOut = Allocation.createTyped(mRS, rgbaType.create(), Allocation.USAGE_SCRIPT);

		    mScriptRotationBitmap.set_in(mYuvToRgbOut);
		}

		if (mAllocFinalBitmap == null) {
			mAllocFinalBitmap = Allocation.createTyped(mRS,
					new Type.Builder(mRS, Element.RGBA_8888(mRS)).setX(yuvDataH).setY(yuvDataW).create(),
					Allocation.USAGE_SCRIPT);

			if (IS_USE_GROUP) {
				mScriptGroup = new ScriptGroup.Builder(mRS)
						.addKernel(mScriptYuvToRgb.getKernelID())
						.addKernel(mScriptRotationBitmap.getKernelID_root())
						.addConnection(mYuvToRgbOut.getType(), mScriptYuvToRgb.getKernelID(), mScriptRotationBitmap.getFieldID_in())
						.create();
				mScriptGroup.setOutput(mScriptRotationBitmap.getKernelID_root(), mAllocFinalBitmap);
			}
		}

	    mYuvToRgbIn.copyFrom(yuvData);
	    mScriptRotationBitmap.set_dstWidthMinusOne(finalBitmap.getWidth() - 1);
	    if (IS_USE_GROUP) {
	    	mScriptGroup.execute();
	    } else {
		    mScriptYuvToRgb.forEach(mYuvToRgbOut);
		    mScriptRotationBitmap.forEach_root(mAllocFinalBitmap);
	    }
	    mAllocFinalBitmap.copyTo(finalBitmap);
	}
}
