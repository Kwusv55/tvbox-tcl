package com.tvbox.legacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tvbox.legacy.model.Episode;
import com.tvbox.legacy.model.SiteRule;
import com.tvbox.legacy.model.VideoSource;
import com.tvbox.legacy.net.HttpClient;
import com.tvbox.legacy.net.RuleCatalog;
import com.tvbox.legacy.net.RuleEngine;
import com.tvbox.legacy.net.RuleUploadServer;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Modern D-pad-first TV shell for the API 17 TCL firmware. */
public class MainActivity extends Activity {
    private static final String PREFS = "tcl-tvbox-ui";
    private static final String PREF_ACTIVE_ID = "active-rule-id";

    private final List<SiteRule> catalog = new ArrayList<SiteRule>();
    private RuleSourceAdapter sourceAdapter;
    private VideoAdapter videoAdapter;
    private ListView sourceList;
    private ListView resultList;
    private EditText searchInput;
    private TextView activeRuleLabel;
    private TextView resultStatus;
    private TextView syncStatus;
    private Button uploadButton;
    private SiteRule activeRule;
    private RuleUploadServer uploadServer;
    private boolean syncRunning;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        buildUi();
        loadCatalog();
    }

    private void buildUi() {
        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(14), pad, dp(10));
        root.setBackgroundColor(Color.rgb(8, 13, 19));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = label("TCL TVBOX  /  API 17", 11, Color.rgb(86, 214, 255));
        brand.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(18)));
        TextView title = label("动漫片库", 28, Color.WHITE);
        brand.addView(title, new LinearLayout.LayoutParams(-1, dp(40)));
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(58), 1));

        syncStatus = label("正在读取规则目录", 13, Color.rgb(164, 180, 194));
        syncStatus.setGravity(Gravity.CENTER);
        syncStatus.setBackgroundResource(R.drawable.modern_chip);
        header.addView(syncStatus, new LinearLayout.LayoutParams(dp(220), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(70)));

        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.FILL);

        LinearLayout sourcePanel = panel();
        TextView sourceTitle = label("动漫来源", 18, Color.WHITE);
        sourcePanel.addView(sourceTitle, new LinearLayout.LayoutParams(-1, dp(36)));
        TextView sourceHint = label("自动更新 · 选择播放源", 12, Color.rgb(145, 163, 178));
        sourcePanel.addView(sourceHint, new LinearLayout.LayoutParams(-1, dp(26)));
        sourceList = new ListView(this);
        sourceList.setDivider(null);
        sourceList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        sourceAdapter = new RuleSourceAdapter();
        sourceList.setAdapter(sourceAdapter);
        sourceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                selectRule(position);
            }
        });
        sourcePanel.addView(sourceList, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView sourceFooter = label("手机上传可替换整个目录", 11, Color.rgb(113, 132, 148));
        sourcePanel.addView(sourceFooter, new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(dp(272), -1);
        sourceParams.setMargins(0, 0, dp(14), 0);
        body.addView(sourcePanel, sourceParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        activeRuleLabel = label("请选择动漫来源", 22, Color.WHITE);
        content.addView(activeRuleLabel, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        searchInput = edit("搜索全部来源");
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        actionRow.addView(searchInput, weightedParams(1));
        Button searchButton = action("搜索");
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                search();
            }
        });
        actionRow.addView(searchButton, actionParams(94));
        Button syncButton = action("更新");
        syncButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                syncCatalog(true);
            }
        });
        actionRow.addView(syncButton, actionParams(86));
        uploadButton = action("手机上传");
        uploadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleUploadServer();
            }
        });
        actionRow.addView(uploadButton, actionParams(112));
        content.addView(actionRow, new LinearLayout.LayoutParams(-1, dp(56)));

        resultStatus = label(getString(R.string.empty_results), 13, Color.rgb(145, 163, 178));
        content.addView(resultStatus, new LinearLayout.LayoutParams(-1, dp(30)));
        resultList = new ListView(this);
        resultList.setDivider(null);
        videoAdapter = new VideoAdapter();
        resultList.setAdapter(videoAdapter);
        resultList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                loadEpisodes(videoAdapter.getItem(position));
            }
        });
        content.addView(resultList, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView footer = label("规则来自公开目录 · 播放能力取决于 TCL 系统解码器", 11,
                Color.rgb(113, 132, 148));
        content.addView(footer, new LinearLayout.LayoutParams(-1, dp(26)));
        body.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        sourceList.requestFocus();
    }

    private void loadCatalog() {
        new AsyncTask<Void, Void, TaskResult<List<SiteRule>>>() {
            @Override
            protected TaskResult<List<SiteRule>> doInBackground(Void... values) {
                try {
                    return TaskResult.success(RuleCatalog.parse(RuleCatalog.load(MainActivity.this)));
                } catch (Exception error) {
                    return TaskResult.failure(error);
                }
            }

            @Override
            protected void onPostExecute(TaskResult<List<SiteRule>> result) {
                if (result.error != null) {
                    showError("规则目录读取失败: " + shortMessage(result.error));
                    return;
                }
                applyCatalog(result.value);
                syncStatus.setText("内置目录  ·  " + result.value.size() + " 个动漫源");
                if (RuleCatalog.shouldRefresh(MainActivity.this)) {
                    syncCatalog(false);
                }
            }
        }.execute();
    }

    private void applyCatalog(List<SiteRule> rules) {
        catalog.clear();
        catalog.addAll(rules);
        sourceAdapter.notifyDataSetChanged();
        if (catalog.isEmpty()) {
            activeRule = null;
            activeRuleLabel.setText("没有可用规则");
            return;
        }
        String savedId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_ACTIVE_ID, "");
        int selected = 0;
        for (int index = 0; index < catalog.size(); index++) {
            if (catalog.get(index).id.equals(savedId)) {
                selected = index;
                break;
            }
        }
        sourceList.setSelection(selected);
        selectRule(selected);
    }

    private void selectRule(int position) {
        if (position < 0 || position >= catalog.size()) {
            return;
        }
        activeRule = catalog.get(position);
        sourceList.setItemChecked(position, true);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_ACTIVE_ID, activeRule.id).apply();
        activeRuleLabel.setText(activeRule.name);
        resultStatus.setText(activeRule.isCms() ? "CMS JSON 源 · 输入片名开始搜索" : "HTML 规则源 · 输入片名开始搜索");
        videoAdapter.clear();
    }

    private void syncCatalog(final boolean manual) {
        if (syncRunning) {
            return;
        }
        syncRunning = true;
        syncStatus.setText(manual ? "正在更新目录…" : "后台检查更新…");
        new AsyncTask<Void, Void, TaskResult<String>>() {
            @Override
            protected TaskResult<String> doInBackground(Void... values) {
                try {
                    return TaskResult.success(RuleCatalog.fetchRemote());
                } catch (Exception error) {
                    return TaskResult.failure(error);
                }
            }

            @Override
            protected void onPostExecute(TaskResult<String> result) {
                syncRunning = false;
                if (result.error != null) {
                    syncStatus.setText("本地目录 · 自动更新失败");
                    if (manual) {
                        showError("目录更新失败: " + shortMessage(result.error));
                    }
                    return;
                }
                try {
                    RuleCatalog.save(MainActivity.this, result.value, "GitHub 自动更新");
                    applyCatalog(RuleCatalog.parse(result.value));
                    syncStatus.setText("已更新 · " + catalog.size() + " 个动漫源");
                } catch (Exception error) {
                    showError("目录校验失败: " + shortMessage(error));
                }
            }
        }.execute();
    }

    private void toggleUploadServer() {
        if (uploadServer != null && uploadServer.isRunning()) {
            uploadServer.stop();
            uploadButton.setText("手机上传");
            syncStatus.setText("手机上传已停止");
            return;
        }
        final String pin = String.valueOf(100000 + new Random().nextInt(900000));
        uploadServer = new RuleUploadServer(RuleUploadServer.DEFAULT_PORT, pin,
                new RuleUploadServer.Callback() {
                    @Override
                    public void onRuleUploaded(final String json) {
                        importCatalog(json, "手机上传");
                    }

                    @Override
                    public void onRuleUploaded(final String json, String fileName) {
                        importCatalog(json, TextUtils.isEmpty(fileName) ? "手机上传" : fileName);
                    }

                    @Override
                    public void onError(final Exception error) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showError("手机上传服务错误: " + shortMessage(error));
                            }
                        });
                    }
                });
        uploadServer.start();
        if (!uploadServer.isRunning()) {
            showError("手机上传端口启动失败");
            return;
        }
        uploadButton.setText("停止上传");
        String url = uploadServer.getUploadUrl(localIp());
        syncStatus.setText("手机上传 · " + url);
        new AlertDialog.Builder(this)
                .setTitle("手机上传规则")
                .setMessage("手机与电视连接同一 Wi-Fi。\n\n打开：" + url
                        + "\nPIN：" + pin + "\n\n上传 JSON 后，电视自动校验并切换目录。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void importCatalog(final String raw, final String source) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                new ImportCatalogTask(source).execute(raw);
            }
        });
    }

    private void search() {
        if (catalog.isEmpty()) {
            showError("没有可用规则");
            return;
        }
        String keyword = searchInput.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            showError("请输入片名");
            return;
        }
        resultStatus.setText("搜索中…");
        new SearchTask().execute(keyword);
    }

    private void loadEpisodes(final VideoSource source) {
        if (source == null || TextUtils.isEmpty(source.detailUrl)) {
            return;
        }
        SiteRule sourceRule = findRule(source.sourceId);
        if (sourceRule != null) {
            activeRule = sourceRule;
            activeRuleLabel.setText(sourceRule.name);
        }
        resultStatus.setText("正在读取剧集…");
        new EpisodeTask().execute(source);
    }

    private SiteRule findRule(String id) {
        if (TextUtils.isEmpty(id)) {
            return activeRule;
        }
        for (SiteRule rule : catalog) {
            if (id.equals(rule.id)) {
                return rule;
            }
        }
        return activeRule;
    }

    private void showEpisodes(final List<Episode> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            showError("没有可播放剧集");
            return;
        }
        if (episodes.size() == 1) {
            playEpisode(episodes.get(0));
            return;
        }
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setAdapter(new ArrayAdapter<Episode>(this, android.R.layout.simple_list_item_1, episodes));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择剧集")
                .setView(list)
                .setNegativeButton("取消", null)
                .create();
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Episode episode = (Episode) parent.getItemAtPosition(position);
                dialog.dismiss();
                playEpisode(episode);
            }
        });
        dialog.show();
    }

    private void playEpisode(final Episode episode) {
        resultStatus.setText("正在解析播放地址…");
        new ResolveTask().execute(episode);
    }

    private void openPlayer(String url, String title) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URL, url);
        intent.putExtra(PlayerActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        if (uploadServer != null) {
            uploadServer.stop();
        }
        super.onDestroy();
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(10), dp(10));
        panel.setBackgroundResource(R.drawable.modern_panel);
        return panel;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private EditText edit(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setHintTextColor(Color.rgb(117, 139, 155));
        view.setTextColor(Color.WHITE);
        view.setTextSize(17);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setBackgroundResource(R.drawable.modern_input);
        view.setFocusable(true);
        return view;
    }

    private Button action(String text) {
        Button view = new Button(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(Color.WHITE);
        view.setAllCaps(false);
        view.setFocusable(true);
        view.setBackgroundResource(R.drawable.modern_button);
        return view;
    }

    private LinearLayout.LayoutParams weightedParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.setMargins(0, 0, dp(8), 0);
        return params;
    }

    private LinearLayout.LayoutParams actionParams(int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), -1);
        params.setMargins(0, 0, dp(7), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String localIp() {
        try {
            WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
            if (wifi != null && wifi.getConnectionInfo() != null
                    && wifi.getConnectionInfo().getIpAddress() != 0) {
                return Formatter.formatIpAddress(wifi.getConnectionInfo().getIpAddress());
            }
        } catch (RuntimeException ignored) {
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "电视IP";
    }

    private void showError(String message) {
        resultStatus.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String shortMessage(Exception error) {
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private final class ImportCatalogTask extends AsyncTask<String, Void, TaskResult<List<SiteRule>>> {
        private final String source;
        private String raw;

        ImportCatalogTask(String source) {
            this.source = source;
        }

        @Override
        protected TaskResult<List<SiteRule>> doInBackground(String... values) {
            raw = values[0];
            try {
                return TaskResult.success(RuleCatalog.parse(raw));
            } catch (Exception error) {
                return TaskResult.failure(error);
            }
        }

        @Override
        protected void onPostExecute(TaskResult<List<SiteRule>> result) {
            if (result.error != null) {
                showError("规则目录无效: " + shortMessage(result.error));
                return;
            }
            try {
                RuleCatalog.save(MainActivity.this, raw, source);
                applyCatalog(result.value);
                syncStatus.setText("已导入 · " + result.value.size() + " 个动漫源");
            } catch (Exception error) {
                showError("目录保存失败: " + shortMessage(error));
            }
        }
    }

    private final class SearchTask extends AsyncTask<String, Void, TaskResult<RuleEngine.SearchResult>> {
        @Override
        protected TaskResult<RuleEngine.SearchResult> doInBackground(String... values) {
            try {
                return TaskResult.success(RuleEngine.searchAll(catalog, values[0]));
            } catch (Exception error) {
                return TaskResult.failure(error);
            }
        }

        @Override
        protected void onPostExecute(TaskResult<RuleEngine.SearchResult> result) {
            if (result.error != null) {
                showError("搜索失败: " + shortMessage(result.error));
                return;
            }
            videoAdapter.setItems(result.value.items);
            resultStatus.setText(String.format(Locale.US, "找到 %d 个结果，%d 个来源失败",
                    result.value.size(), result.value.failedSources));
        }
    }

    private final class EpisodeTask extends AsyncTask<VideoSource, Void, TaskResult<List<Episode>>> {
        @Override
        protected TaskResult<List<Episode>> doInBackground(VideoSource... values) {
            try {
                return TaskResult.success(RuleEngine.episodes(activeRule, values[0].detailUrl));
            } catch (Exception error) {
                return TaskResult.failure(error);
            }
        }

        @Override
        protected void onPostExecute(TaskResult<List<Episode>> result) {
            if (result.error != null) {
                showError("剧集加载失败: " + shortMessage(result.error));
            } else {
                showEpisodes(result.value);
            }
        }
    }

    private final class ResolveTask extends AsyncTask<Episode, Void, TaskResult<EpisodeUrl>> {
        @Override
        protected TaskResult<EpisodeUrl> doInBackground(Episode... values) {
            try {
                return TaskResult.success(new EpisodeUrl(values[0],
                        RuleEngine.resolveVideoUrl(activeRule, values[0].pageUrl)));
            } catch (Exception error) {
                return TaskResult.failure(error);
            }
        }

        @Override
        protected void onPostExecute(TaskResult<EpisodeUrl> result) {
            if (result.error != null) {
                showError("播放地址解析失败: " + shortMessage(result.error));
            } else {
                openPlayer(result.value.url, result.value.episode.title);
                resultStatus.setText("正在播放");
            }
        }
    }

    private final class RuleSourceAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return catalog.size();
        }

        @Override
        public SiteRule getItem(int position) {
            return catalog.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            SiteRule rule = getItem(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(5), dp(8), dp(5));
            row.setBackgroundResource(R.drawable.modern_item);
            TextView name = label(rule.name, 15, Color.WHITE);
            name.setSingleLine(true);
            row.addView(name, new LinearLayout.LayoutParams(-1, dp(28)));
            TextView type = label(rule.isCms() ? "CMS JSON · 动漫" : "HTML · 动漫", 11,
                    Color.rgb(129, 171, 193));
            row.addView(type, new LinearLayout.LayoutParams(-1, dp(20)));
            return row;
        }
    }

    private final class VideoAdapter extends BaseAdapter {
        private final List<VideoSource> items = new ArrayList<VideoSource>();

        void setItems(List<VideoSource> values) {
            items.clear();
            if (values != null) {
                items.addAll(values);
            }
            notifyDataSetChanged();
        }

        void clear() {
            items.clear();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public VideoSource getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            VideoSource item = getItem(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(12), dp(8));
            row.setBackgroundResource(R.drawable.modern_item);
            TextView title = label(item.title, 17, Color.WHITE);
            title.setSingleLine(true);
            row.addView(title, new LinearLayout.LayoutParams(-1, dp(28)));
            SiteRule source = findRule(item.sourceId);
            TextView sourceLabel = label(source == null ? "" : source.name, 11,
                    Color.rgb(86, 214, 255));
            sourceLabel.setSingleLine(true);
            row.addView(sourceLabel, new LinearLayout.LayoutParams(-1, dp(18)));
            TextView hint = label("选择后读取剧集", 11, Color.rgb(129, 151, 166));
            row.addView(hint, new LinearLayout.LayoutParams(-1, dp(20)));
            return row;
        }
    }

    private static final class EpisodeUrl {
        final Episode episode;
        final String url;

        EpisodeUrl(Episode episode, String url) {
            this.episode = episode;
            this.url = url;
        }
    }

    private static final class TaskResult<T> {
        final T value;
        final Exception error;

        private TaskResult(T value, Exception error) {
            this.value = value;
            this.error = error;
        }

        static <T> TaskResult<T> success(T value) {
            return new TaskResult<T>(value, null);
        }

        static <T> TaskResult<T> failure(Exception error) {
            return new TaskResult<T>(null, error);
        }
    }
}
