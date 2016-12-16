package com.twinfishlabs.precamera;

import android.app.ActionBar;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.widget.Toast;

public class SettingsActivity extends PreferenceActivity implements OnPreferenceChangeListener {
	@SuppressWarnings("deprecation")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		getWindow().setTitle(getText(R.string.settings));
		ActionBar actionBar = getActionBar();
		if (actionBar != null) {
//			actionBar.setDisplayShowHomeEnabled(false);
			actionBar.setTitle(R.string.settings);
		}

		addPreferencesFromResource(R.xml.setting_preference);

//		Preference abcPositionPref = findPreference(PreferenceUtils.PREF_KEY_ABC_POSITION);
//		abcPositionPref.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
//			public boolean onPreferenceChange(Preference pref, Object newValue) {
//				int newAbcPosition = Integer.parseInt(newValue.toString());
//				PreferenceUtils.setAbcPosition(newAbcPosition);
//				return true;
//			}
//		});

		PreferenceScreen screen = getPreferenceScreen();
		for (int i = 0; i < screen.getPreferenceCount(); i++) {
			screen.getPreference(i).setOnPreferenceChangeListener(this);
		}
	}

	@Override
	public boolean onPreferenceChange(Preference preference, Object newValue) {
		PrefUtils.notifyChanged();
		return true;
	}
}
