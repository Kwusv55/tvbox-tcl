package com.tvbox.legacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

/** Platform player keeps the APK small and works with API 17 HLS/MP4 support. */
public class PlayerActivity extends Activity {
    public static final String EXTRA_URL = "video-url";
    public static final String EXTRA_TITLE = "video-title";

    private VideoView videoView;
    private ProgressBar progress;
    private boolean prepared;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (url == null || url.trim().length() == 0) {
            finishWithError("播放地址为空");
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        videoView = new VideoView(this);
        videoView.setFocusable(true);
        videoView.setKeepScreenOn(true);
        root.addView(videoView, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(56), dp(56));
        progressParams.gravity = Gravity.CENTER;
        root.addView(progress, progressParams);

        if (title != null && title.length() > 0) {
            TextView titleView = new TextView(this);
            titleView.setText(title);
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(16);
            titleView.setGravity(Gravity.CENTER_VERTICAL);
            titleView.setPadding(dp(18), 0, dp(18), 0);
            titleView.setBackgroundColor(Color.argb(180, 0, 0, 0));
            FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(44));
            titleParams.gravity = Gravity.TOP;
            root.addView(titleView, titleParams);
        }
        setContentView(root);

        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(android.media.MediaPlayer mediaPlayer) {
                prepared = true;
                progress.setVisibility(View.GONE);
                videoView.start();
            }
        });
        videoView.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(android.media.MediaPlayer mediaPlayer) {
                finish();
            }
        });
        videoView.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(android.media.MediaPlayer mediaPlayer, int what, int extra) {
                finishWithError("播放器错误 " + what + "/" + extra);
                return true;
            }
        });
        videoView.setVideoURI(Uri.parse(url));
        videoView.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prepared && videoView != null) {
            videoView.start();
        }
    }

    @Override
    protected void onPause() {
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
        super.onPause();
    }

    private void finishWithError(String message) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.play_error))
                .setMessage(message)
                .setPositiveButton(getString(R.string.confirm), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        finish();
                    }
                })
                .show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
