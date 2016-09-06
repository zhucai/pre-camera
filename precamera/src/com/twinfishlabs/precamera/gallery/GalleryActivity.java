package com.twinfishlabs.precamera.gallery;

import java.io.File;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.TextView;

import com.twinfishlabs.precamera.PrefUtils;
import com.twinfishlabs.precamera.R;
import com.twinfishlabs.precamera.Utilities;
import com.umeng.analytics.MobclickAgent;

public class GalleryActivity extends Activity implements OnClickListener {

	TextView mTxtTitle;
	PictureScrollView mPictureScrollView;
	ViewGroup mTopBar;
	TextView mBtnSend;
	TextView mBtnDelete;
	ImageButton mBtnPlay;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.gallery);
		if (Build.VERSION.SDK_INT >= 19) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
		}
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);

		mTxtTitle = (TextView)findViewById(R.id.txtTitle);
		mPictureScrollView = (PictureScrollView)findViewById(R.id.pictureScrollView);
		mTopBar = (ViewGroup)findViewById(R.id.topBar);
		mBtnSend = (TextView)findViewById(R.id.btnSend);
		mBtnDelete = (TextView)findViewById(R.id.btnDelete);
		mBtnPlay = (ImageButton)findViewById(R.id.btnPlay);

		mPictureScrollView.setOnClickListener(this);
		mBtnSend.setOnClickListener(this);
		mBtnDelete.setOnClickListener(this);
		mBtnPlay.setOnClickListener(this);
		mPictureScrollView.setCurrIndexChanged(new Runnable() {
			public void run() {
				refreshUi();
			}
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		PrefUtils.notifyChanged();
        MobclickAgent.onResume(this);
	}

	@Override
	protected void onPause() {
		super.onPause();
        MobclickAgent.onPause(this);
	}

	@Override
	public void onClick(View v) {
		if (v == mPictureScrollView) {
			toggleTopBarVisibility();
		} else if (v == mBtnSend) {
			startSendActivity();
		} else if (v == mBtnDelete) {
			showDeleteDialog();
		} else if (v == mBtnPlay) {
			playVideo();
		}
	}

	private void playVideo() {
		Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(new File(mPictureScrollView.getCurrFile())), "video/mp4");
        startActivity(intent);
	}

	private void startSendActivity() {
		if (!mPictureScrollView.isIndexValid()) return;

		Intent intent = new Intent(Intent.ACTION_SEND);
		Uri uri = Uri.fromFile(new File(mPictureScrollView.getCurrFile()));
		intent.setDataAndType(uri, "image/*");
		intent.putExtra(Intent.EXTRA_STREAM, uri);
		startActivity(Intent.createChooser(intent, null));
	}

	private void showDeleteDialog() {
		if (PrefUtils.getTakedFiles().size() == 0) return;

		new AlertDialog.Builder(this)
			.setTitle(R.string.gallery_delete_title)
			.setMessage(R.string.gallery_delete_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.gallery_delete_button, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					deleteCurrFile();
				}
			})
			.create()
			.show();
	}

	void deleteCurrFile() {
		if (!mPictureScrollView.isIndexValid()) return;

		File file = new File(mPictureScrollView.getCurrFile());
		file.delete();
		PrefUtils.notifyChanged();
		mPictureScrollView.onCurrFileDeleted();
		Utilities.sendBroadcastScanFile(file);
	}

	private void toggleTopBarVisibility() {
		if (mTopBar.getVisibility() != View.VISIBLE) {
			mTopBar.setVisibility(View.VISIBLE);
			refreshUi();
			mTopBar.setTranslationY(-mTopBar.getHeight());
			mTopBar.animate()
				.setDuration(100)
				.withEndAction(null)
				.translationY(0.f)
				.start();
		} else {
			mTopBar.animate()
				.withEndAction(new Runnable() {
					public void run() {
						mTopBar.setVisibility(View.INVISIBLE);
					}
				})
				.translationY(-mTopBar.getHeight())
				.start();
		}
	}

	private void refreshUi() {
		if (mTxtTitle.isShown()) {
			int size = PrefUtils.getTakedFiles().size();
			int currIndex = mPictureScrollView.getCurrIndex() + 1;
			String title = currIndex + "/" + size;
			mTxtTitle.setText(title);
		}
		if (mPictureScrollView.isIndexValid()) {
			boolean isVideo = Utilities.isVideoFile(mPictureScrollView.getCurrFile());
			mBtnPlay.setVisibility(isVideo ? View.VISIBLE : View.INVISIBLE);
		}
	}
}
