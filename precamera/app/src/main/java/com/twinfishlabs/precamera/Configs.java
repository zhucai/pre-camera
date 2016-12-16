package com.twinfishlabs.precamera;

import android.media.AudioFormat;

public class Configs {

    // Video
	public static final int VIDEO_WIDTH = 1280;  // dimensions for 720p video
	public static final int VIDEO_HEIGHT = 720;
	public static final int DESIRED_PREVIEW_FPS = 25;
	public static final int VIDEO_BIT_RATE = 6000000;

    // Audio
    public static final int SAMPLE_RATE = 44100;
    public static final int SAMPLES_PER_FRAME = 1024; // AAC
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
	public static final int AUDIO_BIT_RATE = 128000;

	// Image
	public static final int SHOT_COUNT = 3;
	public static final int SHOT_COUNT_PER_SEC = 3;
	public static final int PRE_SHOT_INTERVAL_MS = 1000 / SHOT_COUNT_PER_SEC;

    public static final float THUMBNAIL_SHOW_SCALE = 1.5f;
    public static int THUMBNAIL_WIDTH;
    public static int THUMBNAIL_HEIGHT;
    public static int THUMBNAIL_SHOW_WIDTH;
    public static int THUMBNAIL_SHOW_HEIGHT;
	public static int THUMBNAIL_FPS = 5;

	public static int sThumbnailMarginLeft;
	public static int sThumbnailMarginTop;

	static public void init() {
		sThumbnailMarginLeft = Utilities.dpToPx(15);
		sThumbnailMarginTop = Utilities.dpToPx(15);

		THUMBNAIL_WIDTH = Utilities.dpToPx(100);
		THUMBNAIL_HEIGHT = THUMBNAIL_WIDTH * VIDEO_HEIGHT/VIDEO_WIDTH;
		THUMBNAIL_SHOW_WIDTH = Math.round(THUMBNAIL_WIDTH * THUMBNAIL_SHOW_SCALE);
		THUMBNAIL_SHOW_HEIGHT = Math.round(THUMBNAIL_HEIGHT * THUMBNAIL_SHOW_SCALE);
	}
}
