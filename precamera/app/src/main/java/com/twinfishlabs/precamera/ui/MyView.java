package com.twinfishlabs.precamera.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

public class MyView extends View {

	public MyView(Context context) {
		super(context);
	}

	public MyView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public MyView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	public boolean hasOverlappingRendering() {
		return false;
	}
}
