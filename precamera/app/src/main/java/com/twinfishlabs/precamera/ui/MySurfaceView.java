package com.twinfishlabs.precamera.ui;

import android.content.Context;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.SurfaceView;

public class MySurfaceView extends SurfaceView {

	public MySurfaceView(Context context) {
		super(context);
	}

	public MySurfaceView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public MySurfaceView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	public boolean gatherTransparentRegion(Region region) {
		region.setEmpty();
		return true;
	}

	@Override
	public boolean hasOverlappingRendering() {
		return false;
	}
}
