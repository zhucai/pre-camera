/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twinfishlabs.precamera;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;

/**
 * Encodes video in a fixed-size circular buffer.
 * <p>
 * The obvious way to do this would be to store each packet in its own buffer and hook it
 * into a linked list.  The trouble with this approach is that it requires constant
 * allocation, which means we'll be driving the GC to distraction as the frame rate and
 * bit rate increase.  Instead we create fixed-size pools for video data and metadata,
 * which requires a bit more work for us but avoids allocations in the steady state.
 * <p>
 * Video must always start with a sync frame (a/k/a key frame, a/k/a I-frame).  When the
 * circular buffer wraps around, we either need to delete all of the data between the frame at
 * the head of the list and the next sync frame, or have the file save function know that
 * it needs to scan forward for a sync frame before it can start saving data.
 * <p>
 * When we're told to save a snapshot, we create a MediaMuxer, write all the frames out,
 * and then go back to what we were doing.
 */
public class CircularEncoder {
    private static final String TAG = Utilities.TAG;
    private static final boolean VERBOSE = false;

    private static final String VIDEO_MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";    // H.264 Advanced Video Coding
    private static final int IFRAME_INTERVAL = 1;           // sync frame every second

    private CircularEncoder.Callback mCallback;

    private VideoEncoderThread mVideoEncoderThread;
    private Surface mInputSurface;
    private MediaCodec mVideoEncoder;
    private MediaMuxer mMediaMuxer;
    final private Object mMediaMuxerLock = new Object();
    private int mVideoWidth, mVideoHeight;
    private int mFrameRate;

    private AudioEncoderThread mAudioEncoderThread;
    private MediaCodec mAudioEncoder;
    AudioRecord mAudioRecord;

    private long mStartUsec = -1;

    static public final int STATE_CACHE_CIRCULAR = 0;
    static public final int STATE_SAVE_AND_CACHE = 1;
    static public final int STATE_SAVE_DIRECT = 2;
    static public final int STATE_IDLE = 3;
    volatile private int mState;
    private Object mStateLock = new Object();

    /**
     * Callback function definitions.  CircularEncoder caller must provide one.
     */
    public interface Callback {
        /**
         * Called some time after saveVideo(), when all data has been written to the
         * output file.
         *
         * @param status Zero means success, nonzero indicates failure.
         */
        void fileSaveComplete(int status);

        /**
         * Called occasionally.
         *
         * @param totalTimeMsec Total length, in milliseconds, of buffered video.
         */
        void bufferStatus(long totalTimeMsec);
    }

    /**
     * Configures encoder, and prepares the input Surface.
     *
     * @param width Width of encoded video, in pixels.  Should be a multiple of 16.
     * @param height Height of encoded video, in pixels.  Usually a multiple of 16 (1080 is ok).
     * @param videoBitRate Target bit rate, in bits.
     * @param frameRate Expected frame rate.
     * @param preRecordSec How many seconds of video we want to have in our buffer at any time.
     */
    public CircularEncoder(int width, int height, int frameRate, AudioRecord audioRecord, Callback cb)
    		throws IOException {
        // The goal is to size the buffer so that we can accumulate N seconds worth of video,
        // where N is passed in as "desiredSpanSec".  If the codec generates data at roughly
        // the requested bit rate, we can compute it as time * bitRate / bitsPerByte.
        //
        // Sync frames will appear every (frameRate * IFRAME_INTERVAL) frames.  If the frame
        // rate is higher or lower than expected, various calculations may not work out right.
        //
        // Since we have to start muxing from a sync frame, we want to ensure that there's
        // room for at least one full GOP in the buffer, preferrably two.
//        if (preRecordSec < IFRAME_INTERVAL * 2) {
//            throw new RuntimeException("Requested time span is too short: " + preRecordSec + " vs. " + (IFRAME_INTERVAL * 2));
//        }
        mVideoWidth = width;
        mVideoHeight = height;
        mAudioRecord = audioRecord;
        mCallback = cb;
        mFrameRate = frameRate;
        mState = PrefUtils.isDirectRecord() ? STATE_IDLE : STATE_CACHE_CIRCULAR;

        CircularEncoderBuffer videoEncBuffer = PrefUtils.isDirectRecord() ? null :
        		new CircularEncoderBuffer(Configs.VIDEO_BIT_RATE, mFrameRate, PrefUtils.getPreRecordRealTime(), true);
        CircularEncoderBuffer audioEncBuffer = PrefUtils.isDirectRecord() ? null :
        	new CircularEncoderBuffer(Configs.AUDIO_BIT_RATE, mFrameRate, PrefUtils.getPreRecordRealTime(), false);

        mVideoEncoder = createVideoEncoder();
        mInputSurface = mVideoEncoder.createInputSurface();
        mVideoEncoder.start();

        // Start the encoder thread last. That way we're sure it can see all of the state we've initialized.
        mVideoEncoderThread = new VideoEncoderThread(mVideoEncoder, videoEncBuffer);
        mVideoEncoderThread.start();


        MediaFormat audioFormat = new MediaFormat();
        audioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, Configs.AUDIO_BIT_RATE);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();

        mAudioEncoderThread = new AudioEncoderThread(audioEncBuffer);
        mAudioEncoderThread.start();

        mVideoEncoderThread.waitUntilReady();
    }

	private MediaCodec createVideoEncoder() {
		MediaFormat videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, mVideoWidth, mVideoHeight);

        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, Configs.VIDEO_BIT_RATE);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mFrameRate);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        MediaCodec videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        return videoEncoder;
	}

	private void changeState(int state) {
		Log.d(TAG, "changeState: "+state);
		mState = state;
		synchronized (mStateLock) {
			mStateLock.notifyAll();
		}
	}

	public int getState() {
		return mState;
	}

	public void waitState(int state) {
    	while (mState != state) {
        	synchronized (mStateLock) {
        		try {
					mStateLock.wait();
				} catch (InterruptedException e) { }
        	}
    	}
	}

    /**
     * Returns the encoder's input surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    public MediaCodec getVideoEncoder() {
    	return mVideoEncoder;
    }

    /**
     * Shuts down the encoder thread, and releases encoder resources.
     * <p>
     * Does not return until the encoder thread has stopped.
     */
    public void shutdown() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");

        mAudioEncoderThread.mShutdown = true;

        if (mVideoEncoderThread.getId() != Thread.currentThread().getId()) {
	        Handler handler = mVideoEncoderThread.getHandler();
	        handler.sendEmptyMessage(VideoEncoderHandler.MSG_SHUTDOWN);
	        try {
	        	mVideoEncoderThread.join();
	        } catch (Exception ex) { }
        } else {
        	mVideoEncoderThread.shutdown();
        }

        try {
        	mAudioEncoderThread.join();
        } catch (InterruptedException ie) {
            Log.w(TAG, "Encoder thread join() was interrupted", ie);
        }

        if (mInputSurface != null) {
        	mInputSurface.release();
        	mInputSurface = null;
        }

        if (mVideoEncoder != null) {
            mVideoEncoder.stop();
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
    }

    /**
     * Notifies the encoder thread that a new frame will shortly be provided to the encoder.
     * <p>
     * There may or may not yet be data available from the encoder output.  The encoder
     * has a fair mount of latency due to processing, and it may want to accumulate a
     * few additional buffers before producing output.  We just need to drain it regularly
     * to avoid a situation where the producer gets wedged up because there's no room for
     * additional frames.
     * <p>
     * If the caller sends the frame and then notifies us, it could get wedged up.  If it
     * notifies us first and then sends the frame, we guarantee that the output buffers
     * were emptied, and it will be impossible for a single additional frame to block
     * indefinitely.
     */
    public void frameAvailableSoon() {
        Handler handler = mVideoEncoderThread.getHandler();
        handler.sendMessage(handler.obtainMessage(VideoEncoderHandler.MSG_FRAME_AVAILABLE_SOON));
    }

    /**
     * Initiates saving the currently-buffered frames to the specified output file.  The
     * data will be written as a .mp4 file.  The call returns immediately.  When the file
     * save completes, the callback will be notified.
     * <p>
     * The file generation is performed on the encoder thread, which means we won't be
     * draining the output buffers while this runs.  It would be wise to stop submitting
     * frames during this time.
     */
    public void startSaveVideo() {
		mStartUsec = System.nanoTime()/1000;
    	if (!PrefUtils.isDirectRecord()) {
    		mStartUsec -= mVideoEncoderThread.mEncBuffer.computeTimeSpanUsec();
    	}

    	new Thread("SaveAllBuffer") {
    		@Override
    		public void run() {
				tryStartMuxer();
    		}
    	}.start();

//        Handler handler = mVideoEncoderThread.getHandler();
//        handler.sendMessage(handler.obtainMessage(VideoEncoderHandler.MSG_SAVE_BUFFER_TO_FILE, outputFile));
    }

    void tryStartMuxer() {
    	if (mStartUsec == -1) return;
    	if (mMediaMuxer != null) return;
		if (mVideoEncoderThread.mEncodedFormat == null || mAudioEncoderThread.mEncodedFormat == null) return;

		synchronized (CircularEncoder.class) {
			if (mMediaMuxer != null) return;

	    	try {
				mMediaMuxer = new MediaMuxer(CamcorderManager.Instance.mOutputFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		}

    	int orientation = 360 - MainActivity.Instance.mOrientation - 90;
    	if (orientation < 0) orientation += 360;
    	if (orientation == 90) orientation = 270;
    	else if (orientation == 270) orientation = 90;

    	mMediaMuxer.setOrientationHint(orientation);

		mVideoEncoderThread.mVideoTrack = mMediaMuxer.addTrack(mVideoEncoderThread.mEncodedFormat);
		mAudioEncoderThread.mAudioTrack = mMediaMuxer.addTrack(mAudioEncoderThread.mEncodedFormat);

		mMediaMuxer.start();

		if (PrefUtils.isDirectRecord()) {
			changeState(STATE_SAVE_DIRECT);
		} else {
			changeState(STATE_SAVE_AND_CACHE);
			saveAllBufferToFile();
		}
	}

    public void saveAllBufferToFile() {
    	BufferInfo info = new BufferInfo();
    	boolean first = true;
    	long presentationTime = 0;
    	do {
    		synchronized (mMediaMuxerLock) {
        		presentationTime = mVideoEncoderThread.saveOneFrameToFileLocked(info, first);
        		mAudioEncoderThread.saveOneFrameToFileLocked(info, presentationTime);
        		mVideoEncoderThread.mEncBuffer.removeTail();
        		first = false;
        		if (presentationTime <= 0) {
        			changeState(STATE_SAVE_DIRECT);
        		}
			}
		} while (presentationTime > 0);
    }

    public void stopSaving() {
		changeState(PrefUtils.isDirectRecord() ? STATE_IDLE : STATE_CACHE_CIRCULAR);
        mStartUsec = -1;
    	synchronized (mMediaMuxerLock) {
	        if (mMediaMuxer != null) {
	        	try {
	        		mMediaMuxer.stop();
		        	mMediaMuxer.release();
	        	} catch (IllegalStateException ex) {
	        		ex.printStackTrace();
	        	}
	        	mMediaMuxer = null;

	            mCallback.fileSaveComplete(0);
	        } else {
	            mCallback.fileSaveComplete(3);
	        }
    	}
    	CamcorderManager.Instance.onStoped();
    }

	/**
     * Object that encapsulates the encoder thread.
     * <p>
     * We want to sleep until there's work to do.  We don't actually know when a new frame
     * arrives at the encoder, because the other thread is sending frames directly to the
     * input surface.  We will see data appear at the decoder output, so we can either use
     * an infinite timeout on dequeueOutputBuffer() or wait() on an object and require the
     * calling app wake us.  It's very useful to have all of the buffer management local to
     * this thread -- avoids synchronization -- so we want to do the file muxing in here.
     * So, it's best to sleep on an object and do something appropriate when awakened.
     * <p>
     * This class does not manage the MediaCodec encoder startup/shutdown.  The encoder
     * should be fully started before the thread is created, and not shut down until this
     * thread has been joined.
     */
    private class VideoEncoderThread extends Thread {
        MediaFormat mEncodedFormat;
        private MediaCodec.BufferInfo mBufferInfo;
        private int mVideoTrack;

        private VideoEncoderHandler mHandler;
        private CircularEncoderBuffer mEncBuffer;
        private int mFrameNum;

        private final Object mLock = new Object();
        private volatile boolean mReady = false;

        public VideoEncoderThread(MediaCodec mediaCodec, CircularEncoderBuffer encBuffer) {
            mVideoEncoder = mediaCodec;
            mEncBuffer = encBuffer;
            mBufferInfo = new MediaCodec.BufferInfo();
            setName("VideoEncoderThread");
        }

        /**
         * Thread entry point.
         * Prepares the Looper, Handler, and signals anybody watching that we're ready to go.
         */
        @Override
        public void run() {
            Looper.prepare();
            mHandler = new VideoEncoderHandler(this);    // must create on encoder thread
            Log.d(TAG, "video encoder thread ready");
            synchronized (mLock) {
                mReady = true;
                mLock.notify();    // signal waitUntilReady()
            }

            Looper.loop();

            synchronized (mLock) {
                mReady = false;
                mHandler = null;
            }
            Log.d(TAG, "video looper quit");
        }

        public long saveOneFrameToFileLocked(MediaCodec.BufferInfo info, boolean firstFrame) {
            int index = firstFrame ? mEncBuffer.getFirstSyncIndex() : mEncBuffer.getCurrentIndex();
            if (index < 0) {
            	return -1;
            }
            mEncBuffer.setTail(index);
            ByteBuffer buf = mEncBuffer.getChunk(index, info);
//        	Log.d(TAG, "Video.writeSampleData.saveOneFrameToFileLocked:"+info.presentationTimeUs+", index:"+index);
            mMediaMuxer.writeSampleData(mVideoTrack, buf, info);

            return info.presentationTimeUs;
        }

        /**
         * Waits until the encoder thread is ready to receive messages.
         * <p>
         * Call from non-encoder thread.
         */
        public void waitUntilReady() {
            synchronized (mLock) {
                while (!mReady) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Returns the Handler used to send messages to the encoder thread.
         */
        public VideoEncoderHandler getHandler() {
            synchronized (mLock) {
                // Confirm ready state.
                if (!mReady) {
                    throw new RuntimeException("not ready");
                }
            }
            return mHandler;
        }

        /**
         * Drains all pending output from the decoder, and adds it to the circular buffer.
         */
        public void drainVideoEncoder() {
            final int TIMEOUT_USEC = 0;     // no timeout -- check for buffers, bail if none

            ByteBuffer[] encoderOutputBuffers = mVideoEncoder.getOutputBuffers();
            while (true) {
                int encoderStatus = mVideoEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    encoderOutputBuffers = mVideoEncoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Should happen before receiving buffers, and should only happen once.
                    // The MediaFormat contains the csd-0 and csd-1 keys, which we'll need
                    // for MediaMuxer.  It's unclear what else MediaMuxer might want, so
                    // rather than extract the codec-specific data and reconstruct a new
                    // MediaFormat later, we just grab it here and keep it around.
                    mEncodedFormat = mVideoEncoder.getOutputFormat();
                    tryStartMuxer();
                    Log.d(TAG, "encoder output format changed: " + mEncodedFormat);
                } else if (encoderStatus < 0) {
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                    // let's ignore it
                } else {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                    }

                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out when we got the
                        // INFO_OUTPUT_FORMAT_CHANGED status.  The MediaMuxer won't accept
                        // a single big blob -- it wants separate csd-0/csd-1 chunks --
                        // so simply saving this off won't work.
                        if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                        mBufferInfo.size = 0;
                    }
					if (mBufferInfo.size != 0) {
						// adjust the ByteBuffer values to match BufferInfo (not needed?)
						encodedData.position(mBufferInfo.offset);
						encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
				    	synchronized (mMediaMuxerLock) {
				    		switch (mState) {
							case STATE_CACHE_CIRCULAR:
							case STATE_SAVE_AND_CACHE:
								mEncBuffer.add(encodedData, mBufferInfo.flags, mBufferInfo.presentationTimeUs, mState);
								break;

							case STATE_IDLE:
								break;

							case STATE_SAVE_DIRECT:
								mMediaMuxer.writeSampleData(mVideoTrack, encodedData, mBufferInfo);
								break;
							}
						}
					}

                    mVideoEncoder.releaseOutputBuffer(encoderStatus, false);

//                	Log.d(TAG, "no END_OF_STREAM:"+mBufferInfo.presentationTimeUs+", "+CamcorderManager.Instance.mGlThread.mLastTimestamp);

//                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//                    	Log.d(TAG, "video encoder received END_OF_STREAM !!!!!!!!!!!!!!!!!!");
//                    	mVideoEncoder.flush();
//                    	stopSaving();
//                    	return;
//                    }
                    if (CamcorderManager.Instance.isStoping()
                    		&& CamcorderManager.Instance.mGlThread.mLastTimestamp != 0
                    		&& mBufferInfo.presentationTimeUs >= CamcorderManager.Instance.mGlThread.mLastTimestamp/1000) {
                    	Log.d(TAG, "video encoder received END_OF_STREAM");
                    	stopSaving();
                        return;
                    }
                }
            }
        }

        /**
         * Drains the encoder output.
         * <p>
         * See notes for {@link CircularEncoder#frameAvailableSoon()}.
         */
        void frameAvailableSoon() {
            if (VERBOSE) Log.d(TAG, "frameAvailableSoon");
            drainVideoEncoder();

            mFrameNum++;
            if ((mFrameNum % 10) == 0) {    // TODO: should base off frame rate or clock?
            	long timeSpan = 0;
            	if (mStartUsec == -1) {
            		timeSpan = mEncBuffer == null ? 0 : mEncBuffer.computeTimeSpanUsec();
            	} else {
            		timeSpan = System.nanoTime()/1000 - mStartUsec;
            	}
                mCallback.bufferStatus(timeSpan);
            }
        }

        /**
         * Saves the encoder output to a .mp4 file.
         * <p>
         * We'll drain the encoder to get any lingering data, but we're not going to shut
         * the encoder down or use other tricks to try to "flush" the encoder.  This may
         * mean we miss the last couple of submitted frames if they're still working their
         * way through.
         * <p>
         * We may want to reset the buffer after this -- if they hit "capture" again right
         * away they'll end up saving video with a gap where we paused to write the file.
         */
//        void saveBufferToFile() {
//            int index = mEncBuffer.getFirstIndex();
//            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
//
//            if (index >= 0) {
//	            mEncBuffer.getChunk(index, info);
//	            mStartUsec = info.presentationTimeUs;
//            } else {
//            	mStartUsec = System.nanoTime()/1000;
//            }
//
//			while (index >= 0) {
//				synchronized (mMediaMuxerLock) {
//					ByteBuffer buf = mEncBuffer.getChunk(index, info);
//					if (VERBOSE) Log.d(TAG, "SAVE " + index + " flags=0x" + Integer.toHexString(info.flags));
//
//					mMediaMuxer.writeSampleData(mVideoTrack, buf, info);
//					index = mEncBuffer.getNextIndex(index);
//				}
//			}
//            mEncBuffer.clear();
//        }

        /**
         * Tells the Looper to quit.
         */
        void shutdown() {
            if (VERBOSE) Log.d(TAG, "shutdown");
            Looper.myLooper().quit();
        }

    }

    /**
     * Handler for EncoderThread.  Used for messages sent from the UI thread (or whatever
     * is driving the encoder) to the encoder thread.
     * <p>
     * The object is created on the encoder thread.
     */
    private static class VideoEncoderHandler extends Handler {
        public static final int MSG_FRAME_AVAILABLE_SOON = 1;
//        public static final int MSG_SAVE_BUFFER_TO_FILE = 2;
        public static final int MSG_SHUTDOWN = 3;

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        private WeakReference<VideoEncoderThread> mWeakEncoderThread;

        /**
         * Constructor.  Instantiate object from encoder thread.
         */
        public VideoEncoderHandler(VideoEncoderThread et) {
            mWeakEncoderThread = new WeakReference<VideoEncoderThread>(et);
        }

        @Override  // runs on encoder thread
        public void handleMessage(Message msg) {
            int what = msg.what;
            if (VERBOSE) {
                Log.v(TAG, "EncoderHandler: what=" + what);
            }

            VideoEncoderThread encoderThread = mWeakEncoderThread.get();
            if (encoderThread == null) {
                Log.w(TAG, "EncoderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_FRAME_AVAILABLE_SOON:
                    encoderThread.frameAvailableSoon();
                    break;
//                case MSG_SAVE_BUFFER_TO_FILE:
//                    encoderThread.saveBufferToFile();
//                    break;
                case MSG_SHUTDOWN:
                    encoderThread.shutdown();
                    break;
                default:
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }

    private class AudioEncoderThread extends Thread {

        private MediaFormat mEncodedFormat;
        private MediaCodec.BufferInfo mBufferInfo;
        private int mAudioTrack;

        private CircularEncoderBuffer mEncBuffer;

    	public AudioEncoderThread(CircularEncoderBuffer encBuffer) {
            mEncBuffer = encBuffer;
            mBufferInfo = new MediaCodec.BufferInfo();
            setName("AudioEncoderThread");
		}

        volatile boolean mShutdown = false;

    	@Override
    	public void run() {
            mAudioRecord.startRecording();
            while(!mShutdown){
                sendAudioToEncoder();
                drainAudioEncoder();
            }
    	}

        // send current frame data to encoder
        public void sendAudioToEncoder() {
            try {
                ByteBuffer[] inputBuffers = mAudioEncoder.getInputBuffers();
                int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(100000/*us*/);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                    inputBuffer.clear();
                    long presentationTimeNs = System.nanoTime();
                    int inputLength =  mAudioRecord.read(inputBuffer, Configs.SAMPLES_PER_FRAME );
                    if (inputLength > 0) {
	                    presentationTimeNs -= (inputLength / Configs.SAMPLE_RATE ) / 1000000000;
	                    //long presentationTimeUs = (presentationTimeNs - startWhen) / 1000;
	                    long presentationTimeUs = presentationTimeNs / 1000;
	                    int flags = 0;
	                    mAudioEncoder.queueInputBuffer(inputBufferIndex, 0, inputLength, presentationTimeUs, flags);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "_offerAudioEncoder exception", t);
            }
        }

        /**
         * Extracts all pending data from the encoder and forwards it to the muxer.
         * <p/>
         * If endOfStream is not set, this returns when there is no more data to drain.  If it
         * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
         * Calling this with endOfStream set should be done once, right before stopping the muxer.
         * <p/>
         * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
         * not recording audio.
         */
        private void drainAudioEncoder() {
            //testing
            ByteBuffer[] encoderOutputBuffers = mAudioEncoder.getOutputBuffers();

            while (true) {
                int encoderStatus = mAudioEncoder.dequeueOutputBuffer(mBufferInfo, 1000/*us*/);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                	break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = mAudioEncoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // should happen before receiving buffers, and should only happen once
                	mEncodedFormat = mAudioEncoder.getOutputFormat();
                	tryStartMuxer();
                } else if (encoderStatus < 0) {
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                    // let's ignore it
                } else {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if (encodedData == null) throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");

                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    	mBufferInfo.size = 0;
                    }

                    if (mBufferInfo.size != 0) {
                        // adjust the ByteBuffer values to match BufferInfo (not needed?)
                        encodedData.position(mBufferInfo.offset);
                        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
				    	synchronized (mMediaMuxerLock) {
				    		switch (mState) {
							case STATE_CACHE_CIRCULAR:
							case STATE_SAVE_AND_CACHE:
		                    	mEncBuffer.add(encodedData, mBufferInfo.flags, mBufferInfo.presentationTimeUs, mState);
								break;

							case STATE_IDLE:
								break;

							case STATE_SAVE_DIRECT:
	                    		mMediaMuxer.writeSampleData(mAudioTrack, encodedData, mBufferInfo);
								break;
				    		}
				    	}
                    }

                    mAudioEncoder.releaseOutputBuffer(encoderStatus, false);
                }
            }
        }

//        void saveBufferToFile() {
//            int index = mEncBuffer.getFirstIndex();
//            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
//
//            if (index >= 0) {
//	            mEncBuffer.getChunk(index, info);
//	            mStartUsec = info.presentationTimeUs;
//            } else {
//            	mStartUsec = System.nanoTime()/1000;
//            }
//
//            while (index >= 0) {
//            	synchronized (mMediaMuxerLock) {
//	                ByteBuffer buf = mEncBuffer.getChunk(index, info);
//	                if (VERBOSE) Log.d(TAG, "SAVE " + index + " flags=0x" + Integer.toHexString(info.flags));
//
//	                mMediaMuxer.writeSampleData(mAudioTrack, buf, info);
//	                index = mEncBuffer.getNextIndex(index);
//	            }
//	    	}
//            mEncBuffer.clear();
//        }

        public void saveOneFrameToFileLocked(MediaCodec.BufferInfo info, long toPresentationTime) {
            int index = mEncBuffer.getFirstSyncIndex();
            while (true) {
                ByteBuffer buf = mEncBuffer.getChunk(index, info);
                if (index >= 0 && (toPresentationTime < 0 || toPresentationTime >= info.presentationTimeUs)) {
                	if (VERBOSE) Log.d(TAG, "Audio.writeSampleData.saveOneFrameToFileLocked:"+info.presentationTimeUs+", index:"+index);
                	mMediaMuxer.writeSampleData(mAudioTrack, buf, info);
                } else {
                	break;
                }
                mEncBuffer.removeTail();
                index = mEncBuffer.getFirstSyncIndex();
            }
        }
    }
}
