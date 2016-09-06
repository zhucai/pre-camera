package com.twinfishlabs.precamera;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;

public class PrefUtils {

	static final private String IS_DEBUG_MODE = "IsDebugMode";
	static final private String KEY_PRE_RECORD_TIME = "PreRecordTime";
	static final private String KEY_IS_SHOW_PRE_RECORD = "IsShowPreRecord";
	static final private String KEY_CAMERA_TYPE = "CameraType";
	static final private String KEY_TAKED_FILES = "TakedFiles";

	static private SharedPreferences sPrefs;

	static private Boolean isDebugMode;
	static private int preRecordTime;
	static private Boolean isShowPreRecord;
	static private int cameraType;
	static private List<String> takedFiles;

	static public void init() {
		sPrefs = PreferenceManager.getDefaultSharedPreferences(MyApplication.Instance);
		resetValue();
	}

	static private void resetValue() {
		isDebugMode = null;
		preRecordTime = Integer.MAX_VALUE;
		isShowPreRecord = null;
		cameraType = Integer.MAX_VALUE;
		takedFiles = null;
	}

	static public void notifyChanged() {
		resetValue();
	}

	static public boolean getIsDebugMode() {
		if (isDebugMode == null) {
			isDebugMode = sPrefs.getBoolean(IS_DEBUG_MODE, false);
		}
		return isDebugMode;
	}
	static public void setIsDebugMode(boolean value) {
		isDebugMode = value;
		sPrefs.edit().putBoolean(IS_DEBUG_MODE, value).apply();
	}

	static private int getPreRecordTime() {
		if (preRecordTime == Integer.MAX_VALUE) {
			preRecordTime = Integer.parseInt(sPrefs.getString(KEY_PRE_RECORD_TIME, "5"));
			preRecordTime = Math.max(preRecordTime, 0);
			preRecordTime = Math.min(preRecordTime, 300);
		}
		return preRecordTime;
	}
	static public int getPreRecordRealTime() {
		return getPreRecordTime() + 1;
	}
	static public boolean isDirectRecord() {
		return getPreRecordTime() == 0;
	}

	static public boolean getIsShowPreRecord() {
		if (isShowPreRecord == null) {
			isShowPreRecord = sPrefs.getBoolean(KEY_IS_SHOW_PRE_RECORD, false);
		}
		return isShowPreRecord;
	}

	static public int getCameraType() {
		if (cameraType == Integer.MAX_VALUE) {
			cameraType = Integer.parseInt(sPrefs.getString(KEY_CAMERA_TYPE, "0"));
		}
		return cameraType;
	}

	static public void addTakedFile(String filePath) {
		loadTakedFiles();
		takedFiles.add(filePath);
		saveTakedFiles();
	}

	static public void addTakedFiles(List<String> files) {
		if (files.size() == 0) return;

		loadTakedFiles();
		takedFiles.addAll(files);
		saveTakedFiles();
	}

	static public void removeTakedFile(String filePath) {
		loadTakedFiles();
		takedFiles.remove(filePath);
		saveTakedFiles();
	}

	static private void loadTakedFiles() {
		if (takedFiles == null) {
			String str = sPrefs.getString(KEY_TAKED_FILES, "");
			String[] strs = str.split(";");
			takedFiles = new ArrayList<String>();
			for (String s : strs) {
				if (!TextUtils.isEmpty(s)) {
					File file = new File(s);
					if (file.exists()) {
						takedFiles.add(s);
					}
				}
			}
		}
	}

	static private void saveTakedFiles() {
		StringBuffer sb = new StringBuffer();
		for (String s : takedFiles) {
			sb.append(s).append(';');
		}
		sPrefs.edit().putString(KEY_TAKED_FILES, sb.toString()).apply();
	}

	static public List<String> getTakedFiles() {
		loadTakedFiles();
		return takedFiles;
	}
}
