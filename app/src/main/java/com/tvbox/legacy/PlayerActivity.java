package com.tvbox.legacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

/** HLS/MP4 player compatible with the Android 4.x target. */
public class PlayerActivity extends Activity {
    public static final String EXTRA_URL = "video-url";
    public static final String EXTRA_TITLE = "video-title";

    private SimpleExoPlayer player;
    private ProgressBar progress;
    private String currentUrl;
    private PlayerView playerView;
    private DefaultDataSourceFactory dataSourceFactory;
    private boolean triedHttpFallback;
    private boolean usingLegacyPlayer;
    private boolean errorShown;
    private FrameLayout playerRoot;
    private VideoView legacyVideo;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        currentUrl = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (currentUrl == null || currentUrl.trim().length() == 0) {
            finishWithError("播放地址为空");
            return;
        }

        FrameLayout root = new FrameLayout(this);
        playerRoot = root;
        root.setBackgroundColor(Color.rgb(7, 10, 21));
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

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
            titleView.setBackgroundColor(Color.argb(190, 9, 12, 30));
            FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(46));
            titleParams.gravity = Gravity.TOP;
            root.addView(titleView, titleParams);
        }
        setContentView(root);

        try {
            dataSourceFactory = new DefaultDataSourceFactory(
                    this, Util.getUserAgent(this, "TCL-TVBOX"));
            player = ExoPlayerFactory.newSimpleInstance(this);
            playerView.setPlayer(player);
            player.addListener(new Player.EventListener() {
                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    if (!triedHttpFallback && currentUrl.startsWith("https://")) {
                        // Many public media CDNs still expose HTTP. Android 4.2 cannot
                        // negotiate TLS with some of their modern-only HTTPS endpoints.
                        triedHttpFallback = true;
                        preparePlayback("http://" + currentUrl.substring("https://".length()));
                        return;
                    }
                    if (!usingLegacyPlayer && Build.VERSION.SDK_INT <= 17) {
                        // Older firmware may expose a decoder through MediaPlayer that
                        // ExoPlayer rejects during capability probing.
                        startLegacyPlayer();
                        return;
                    }
                    finishWithError("播放失败: " + error.getMessage());
                }

                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    if (progress == null) {
                        return;
                    }
                    progress.setVisibility(playbackState == Player.STATE_BUFFERING
                            ? View.VISIBLE : View.GONE);
                }
            });
            preparePlayback(currentUrl);
        } catch (RuntimeException error) {
            finishWithError("播放器初始化失败: " + error.getMessage());
        }
    }

    private void preparePlayback(String url) {
        currentUrl = url == null ? "" : url.trim();
        if (player == null || dataSourceFactory == null || currentUrl.length() == 0) {
            return;
        }
        Uri uri = Uri.parse(currentUrl);
        MediaSource source;
        if (currentUrl.toLowerCase(java.util.Locale.US).contains(".m3u8")) {
            source = new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
        } else {
            source = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
        }
        progress.setVisibility(View.VISIBLE);
        player.prepare(source, true, true);
        player.setPlayWhenReady(true);
    }

    private void startLegacyPlayer() {
        usingLegacyPlayer = true;
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) {
            playerView.setVisibility(View.GONE);
        }
        legacyVideo = new VideoView(this);
        legacyVideo.setKeepScreenOn(true);
        legacyVideo.setFocusable(true);
        // Insert below progress/title overlays so legacy playback keeps same shell.
        playerRoot.addView(legacyVideo, 1, new FrameLayout.LayoutParams(-1, -1));
        MediaController controller = new MediaController(this);
        controller.setAnchorView(legacyVideo);
        legacyVideo.setMediaController(controller);
        legacyVideo.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(android.media.MediaPlayer mediaPlayer) {
                progress.setVisibility(View.GONE);
                legacyVideo.start();
            }
        });
        legacyVideo.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(android.media.MediaPlayer mediaPlayer, int what, int extra) {
                finishWithError("播放失败: 原生解码器 " + what + "/" + extra);
                return true;
            }
        });
        try {
            legacyVideo.setVideoURI(Uri.parse(currentUrl));
            legacyVideo.requestFocus();
            legacyVideo.start();
        } catch (RuntimeException error) {
            finishWithError("原生播放器初始化失败: " + error.getMessage());
        }
    }

    @Override
    protected void onPause() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        if (legacyVideo != null && legacyVideo.isPlaying()) {
            legacyVideo.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) {
            player.setPlayWhenReady(true);
        }
        if (legacyVideo != null) {
            legacyVideo.start();
        }
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (legacyVideo != null) {
            legacyVideo.stopPlayback();
            legacyVideo = null;
        }
        super.onDestroy();
    }

    private void finishWithError(String message) {
        if (errorShown || isFinishing()) {
            return;
        }
        errorShown = true;
        new AlertDialog.Builder(this, R.style.AnimeDialogTheme)
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
