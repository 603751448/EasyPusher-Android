package org.easydarwin.easypusher;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import org.easydarwin.audio.AudioStream;
import org.easydarwin.push.EasyPusher;
import org.easydarwin.push.Pusher;
import org.easydarwin.util.Config;

import java.io.IOException;
import java.nio.ByteBuffer;

import static android.media.MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class RecordService extends Service {

    private static final String TAG = "RService";
    private String mVideoPath;
    private MediaProjectionManager mMpmngr;
    private MediaProjection mMpj;
    private VirtualDisplay mVirtualDisplay;
    private int windowWidth;
    private int windowHeight;
    private int screenDensity;

    private Surface mSurface;
    private MediaCodec mMediaCodec;

    private WindowManager wm;
    final AudioStream audioStream = AudioStream.getInstance();

    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    public static Pusher mEasyPusher;
    private Thread mPushThread;
    private byte[] mPpsSps;
    private WindowManager mWindowManager;
    private View mLayout;
    private WindowManager.LayoutParams param;
    private GestureDetector mGD;
    private View.OnTouchListener listener;

    private MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private ByteBuffer[] outputBuffers;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mMpmngr = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);
        createEnvironment();
        configureMedia();
        startPush();


        mWindowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                return;
            }
        }


        showView();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void showView() {
        if (mLayout != null) return;
        mLayout = LayoutInflater.from(this).inflate(R.layout.float_btn, null);

        param = new WindowManager.LayoutParams();


        param.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            param.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        param.format = 1;
        param.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        param.flags = param.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        param.flags = param.flags | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        param.alpha = 1.0f;

        param.gravity = Gravity.LEFT | Gravity.TOP;

        param.width = (int) (getResources().getDisplayMetrics().density * 50);
        param.height = (int) (getResources().getDisplayMetrics().density * 40);

        param.x = getResources().getDisplayMetrics().widthPixels - param.width - getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin);
        param.y = getResources().getDisplayMetrics().heightPixels / 2 - param.height / 2;

        param.x = PreferenceManager.getDefaultSharedPreferences(this).getInt("float_btn_x", param.x);
        param.y = PreferenceManager.getDefaultSharedPreferences(this).getInt("float_btn_y", param.y);

        mWindowManager.addView(mLayout, param);



        mGD = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {


            public boolean mScroll;
            int x;
            int y;

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                Intent intent = new Intent(RecordService.this, StreamActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                startActivity(intent);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (!mScroll && Math.sqrt((e1.getX() - e2.getX()) * (e1.getX() - e2.getX()) + (e1.getY() - e2.getY()) * (e1.getY() - e2.getY())) > ViewConfiguration.get(RecordService.this).getScaledTouchSlop()) {
                    mScroll = true;
                }
                if (!mScroll) {
                    return false;
                } else {

                    WindowManager.LayoutParams p = (WindowManager.LayoutParams) mLayout.getLayoutParams();
                    p.x = (int) (x + e2.getRawX() - e1.getRawX());
                    p.y = (int) (y + e2.getRawY() - e1.getRawY());
                    mWindowManager.updateViewLayout(mLayout, p);
                    return true;
                }
            }

            @Override
            public boolean onDown(MotionEvent e) {
                if (mLayout == null) return true;
                mScroll = false;
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) mLayout.getLayoutParams();
                x = p.x;
                y = p.y;
                return super.onDown(e);
            }
        });
        listener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return mGD.onTouchEvent(event);
            }
        };

        mLayout.setOnTouchListener(listener);
    }

    private void hideView() {
        if (mLayout == null) return;
        mWindowManager.removeView(mLayout);
        mLayout = null;
    }


    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void configureMedia() {

        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", windowWidth, windowHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 1200000);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        mediaFormat.setInteger(MediaFormat.KEY_CAPTURE_RATE, 25);
        mediaFormat.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / 25);
        try {
            mMediaCodec = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            e.printStackTrace();
        }
        mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mMediaCodec.createInputSurface();
        mMediaCodec.start();
    }

    private void createEnvironment() {
        mVideoPath = Environment.getExternalStorageDirectory().getPath() + "/";
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        windowWidth = wm.getDefaultDisplay().getWidth();
        windowHeight = wm.getDefaultDisplay().getHeight();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(displayMetrics);
        screenDensity = displayMetrics.densityDpi;


        int defaultIdx = PreferenceManager.getDefaultSharedPreferences(this).getInt("screen_pushing_res_index", 3);
//        new AlertDialog.Builder(this).setTitle("推送屏幕辨率").setSingleChoiceItems(
//                new CharSequence[]{"1倍屏幕大小","0.75倍屏幕大小","0.5倍屏幕大小","0.3倍屏幕大小","0.25倍屏幕大小","0.2倍屏幕大小"}

        switch (defaultIdx){
            case 0:

                break;
            case 1:
                windowWidth *= 0.75;
                windowHeight *= 0.75;
                break;
            case 2:

                windowWidth *= 0.5;
                windowHeight *= 0.5;
                break;
            case 3:

                windowWidth *= 0.3;
                windowHeight *= 0.3;
                break;
            case 4:

                windowWidth *= 0.25;
                windowHeight *= 0.25;
                break;
            case 5:
                windowWidth *= 0.2;
                windowHeight *= 0.2;
                break;
        }

//        windowWidth /= 16;
//        windowWidth *= 16;
//
//        windowHeight /= 16;
//        windowHeight *= 16;
    }

    private void startPush() {
        if (mPushThread != null)
            return;

        mPushThread = new Thread() {
            @TargetApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void run() {
                mEasyPusher = new EasyPusher();
                String ip = Config.getIp(RecordService.this);
                String port = Config.getPort(RecordService.this);
                String id = Config.getId(RecordService.this);

                mEasyPusher.initPush( getApplicationContext(), null);
                mEasyPusher.setMediaInfo(Pusher.Codec.EASY_SDK_VIDEO_CODEC_H264, 25, Pusher.Codec.EASY_SDK_AUDIO_CODEC_AAC, 1, 8000, 16);
                mEasyPusher.start(ip,port,String.format("%s_s.sdp", id), Pusher.TransType.EASY_RTP_OVER_TCP);

                try {
                    audioStream.addPusher(mEasyPusher);

                    byte[] h264 = new byte[windowWidth * windowHeight];
                    long lastKeyFrameUS = 0, lastRequestKeyFrameUS = 0;

                    while (mPushThread != null) {
                        if (lastKeyFrameUS > 0 && SystemClock.elapsedRealtimeNanos() / 1000 - lastKeyFrameUS >= 3000000) {  // 3s no key frame.
                            if (SystemClock.elapsedRealtimeNanos() / 1000 - lastRequestKeyFrameUS >= 3000000) {
                                Bundle p = new Bundle();
                                p.putInt(PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                                mMediaCodec.setParameters(p);

                                Log.i(TAG, "request key frame");

                                lastRequestKeyFrameUS = SystemClock.elapsedRealtimeNanos() / 1000;
                            }
                        }

                        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                        Log.i(TAG, "dequeue output buffer outputBufferIndex=" + outputBufferIndex);

                        if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // no output available yet
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            // not expected for an encoder
                            outputBuffers = mMediaCodec.getOutputBuffers();
                        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            //
                        } else if (outputBufferIndex < 0) {
                            // let's ignore it
                        } else {
                            ByteBuffer outputBuffer;

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex);
                            } else {
                                outputBuffer = outputBuffers[outputBufferIndex];
                            }

                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                            try {
                                boolean sync = false;

                                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {// sps
                                    sync = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;

                                    if (!sync) {
                                        byte[] temp = new byte[bufferInfo.size];
                                        outputBuffer.get(temp);
                                        mPpsSps = temp;
                                        continue;
                                    } else {
                                        mPpsSps = new byte[0];
                                    }
                                }

                                sync |= (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                                int len = mPpsSps.length + bufferInfo.size;

                                if (len > h264.length) {
                                    h264 = new byte[len];
                                }

                                if (sync) {
                                    System.arraycopy(mPpsSps, 0, h264, 0, mPpsSps.length);
                                    outputBuffer.get(h264, mPpsSps.length, bufferInfo.size);
                                    mEasyPusher.push(h264, 0, mPpsSps.length + bufferInfo.size, bufferInfo.presentationTimeUs / 1000, 2);

                                    if (BuildConfig.DEBUG)
                                        Log.i(TAG, String.format("push i video stamp:%d", bufferInfo.presentationTimeUs / 1000));
                                } else {
                                    outputBuffer.get(h264, 0, bufferInfo.size);
                                    mEasyPusher.push(h264, 0, bufferInfo.size, bufferInfo.presentationTimeUs / 1000, 1);

                                    if (BuildConfig.DEBUG)
                                        Log.i(TAG, String.format("push video stamp:%d", bufferInfo.presentationTimeUs / 1000));
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                // 将缓冲器返回到编解码器
                                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                            }
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    audioStream.removePusher(mEasyPusher);
                }
            }
        };
        mPushThread.start();
        startVirtualDisplay();
    }


    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void stopPush(){
        Thread t = mPushThread;
        if (t != null){
            mPushThread = null;
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mEasyPusher.stop();
        mEasyPusher = null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startVirtualDisplay() {
        if (mMpj == null) {
            mMpj = mMpmngr.getMediaProjection(StreamActivity.mResultCode, StreamActivity.mResultIntent);
            StreamActivity.mResultCode = 0;
            StreamActivity.mResultIntent = null;
        }
        if (mMpj == null) return;
        mVirtualDisplay = mMpj.createVirtualDisplay("record_screen", windowWidth, windowHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR|DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, mSurface, null, null);
    }


    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void encodeToVideoTrack(int index) {
        ByteBuffer encodedData = mMediaCodec.getOutputBuffer(index);

        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {//是编码需要的特定数据，不是媒体数据
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.
            // Ignore it.
            Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
            mBufferInfo.size = 0;
        }
        if (mBufferInfo.size == 0) {
            Log.d(TAG, "info.size == 0, drop it.");
            encodedData = null;
        } else {
            Log.d(TAG, "got buffer, info: size=" + mBufferInfo.size
                    + ", presentationTimeUs=" + mBufferInfo.presentationTimeUs
                    + ", offset=" + mBufferInfo.offset);
        }
        if (encodedData != null) {
            encodedData.position(mBufferInfo.offset);
            encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
//            mMuxer.writeSampleData(mVideoTrackIndex, encodedData, mBufferInfo);//写入
            Log.i(TAG, "sent " + mBufferInfo.size + " bytes to muxer...");
        }
    }



    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void release() {

        Log.i(TAG, " release() ");
        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }
        if (mSurface != null){
            mSurface.release();
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onDestroy() {
        super.onDestroy();
        hideView();
        stopPush();
        release();
        if (mMpj != null) {
            mMpj.stop();
        }
    }
}
