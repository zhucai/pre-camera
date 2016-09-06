package com.twinfishlabs.precamera;

import android.app.Application;

public class MyApplication extends Application {

	static public MyApplication Instance;

	public MyApplication() {
		Instance = this;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		Configs.init();
		PrefUtils.init();
		CamcorderManager.init();
	}
}
