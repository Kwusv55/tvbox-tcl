package com.tvbox.legacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tvbox.legacy.model.Episode;
import com.tvbox.legacy.model.RuleStore;
import com.tvbox.legacy.model.SiteRule;
import com.tvbox.legacy.model.VideoSource;
import com.tvbox.legacy.net.HttpClient;
import com.tvbox.legacy.net.RuleEngine;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** Small D-pad-first launcher for old TCL Android TV firmware. */
public class MainActivity extends Activity {
    private static final int REQUEST_RULE_FILE = 17;
    private static final String PREFS = "tcl-tvbox";
    private static final String PREF_RULE_JSON = "rule-json";
    private static final int MAX_RULE_BYTES = 2 * 1024 * 1024;

    private EditText ruleInput;
    private EditText searchInput;
    private TextView ruleStatus;
    private TextView status;
    private ListView resultList;
    private ArrayAdapter<VideoSource> resultAdapter;
    private SiteRule activeRule;
    private String activeRuleJson;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        buildUi();
        restoreRule();
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(14), pad, dp(10));
        root.setBackgroundColor(Color.rgb(16, 19, 24));

        TextView title = text("TCL TVBox", 24, Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));

        TextView subtitle = text("Android 4.2.2  ·  遥控器模式  ·  轻量公开规则", 14,
                Color.rgb(170, 181, 192));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(30)));

        LinearLayout ruleRow = new LinearLayout(this);
        ruleRow.setGravity(Gravity.CENTER_VERTICAL);
        ruleInput = edit(getString(com.tvbox.legacy.R.string.rule_input_hint));
        ruleRow.addView(ruleInput, weightParams(1));
        Button importButton = button(getString(com.tvbox.legacy.R.string.import_rule));
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showImportDialog();
            }
        });
        ruleRow.addView(importButton, buttonParams());
        Button fileButton = button(getString(com.tvbox.legacy.R.string.choose_file));
        fileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseRuleFile();
            }
        });
        ruleRow.addView(fileButton, buttonParams());
        root.addView(ruleRow, new LinearLayout.LayoutParams(-1, dp(58)));

        ruleStatus = text(getString(com.tvbox.legacy.R.string.no_rule), 13,
                Color.rgb(170, 181, 192));
        root.addView(ruleStatus, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchInput = edit(getString(com.tvbox.legacy.R.string.search_hint));
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchRow.addView(searchInput, weightParams(1));
        Button searchButton = button(getString(com.tvbox.legacy.R.string.search));
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                search();
            }
        });
        searchRow.addView(searchButton, buttonParams());
        root.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(58)));

        status = text(getString(com.tvbox.legacy.R.string.empty_results), 14,
                Color.rgb(170, 181, 192));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(30)));

        resultList = new ListView(this);
        resultList.setDividerHeight(dp(1));
        resultList.setBackgroundColor(Color.rgb(28, 34, 43));
        resultAdapter = new ArrayAdapter<VideoSource>(this,
                android.R.layout.simple_list_item_1);
        resultList.setAdapter(resultAdapter);
        resultList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                loadEpisodes(resultAdapter.getItem(position));
            }
        });
        root.addView(resultList, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView footer = text("仅解析你提供的公开 HTTP/HTTPS 规则；qist/tvbox 的 sites 配置需原生 TVBox 内核。",
                12, Color.rgb(140, 150, 160));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(26)));

        setContentView(root);
        ruleInput.requestFocus();
    }

    private void restoreRule() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        activeRuleJson = preferences.getString(PREF_RULE_JSON, "");
        if (TextUtils.isEmpty(activeRuleJson)) {
            return;
        }
        try {
            activeRule = RuleStore.parse(activeRuleJson);
            ruleStatus.setText(getString(R.string.source_label) + ": " + activeRule.displayName());
            ruleInput.setText(activeRuleJson);
        } catch (JSONException error) {
            activeRule = null;
            ruleStatus.setText(getString(R.string.rule_format));
        }
    }

    private void showImportDialog() {
        final EditText input = edit(getString(R.string.rule_input_hint));
        input.setGravity(Gravity.TOP | Gravity.LEFT);
        input.setSingleLine(false);
        input.setMinLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setText(ruleInput.getText().toString());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.import_dialog_title))
                .setView(input)
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.confirm), null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                AlertDialog alert = (AlertDialog) dialogInterface;
                alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        importRule(input.getText().toString().trim());
                        ((AlertDialog) view.getTag()).dismiss();
                    }
                });
                alert.getButton(AlertDialog.BUTTON_POSITIVE).setTag(alert);
            }
        });
        dialog.show();
    }

    private void chooseRuleFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_RULE_FILE);
        } catch (RuntimeException error) {
            Toast.makeText(this, "系统不支持文件选择，请粘贴规则 URL/JSON", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_RULE_FILE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        status.setText(getString(R.string.loading));
        new ReadFileTask().execute(uri);
    }

    private void importRule(final String value) {
        if (TextUtils.isEmpty(value)) {
            Toast.makeText(this, "规则内容为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ruleInput.setText(value);
        status.setText(getString(R.string.loading));
        new ImportTask().execute(value);
    }

    private void search() {
        if (activeRule == null) {
            Toast.makeText(this, getString(R.string.no_rule), Toast.LENGTH_SHORT).show();
            return;
        }
        final String keyword = searchInput.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            Toast.makeText(this, "请输入影视名称", Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText(getString(R.string.loading));
        new SearchTask().execute(keyword);
    }

    private void loadEpisodes(final VideoSource source) {
        if (source == null || TextUtils.isEmpty(source.detailUrl)) {
            return;
        }
        status.setText(getString(R.string.loading));
        new EpisodeTask().execute(source);
    }

    private void showEpisodes(final List<Episode> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            Toast.makeText(this, "没有可播放剧集", Toast.LENGTH_SHORT).show();
            return;
        }
        if (episodes.size() == 1) {
            playEpisode(episodes.get(0));
            return;
        }
        final ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<Episode>(this, android.R.layout.simple_list_item_1, episodes));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_episode))
                .setView(list)
                .setNegativeButton(getString(R.string.cancel), null)
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
        status.setText(getString(R.string.loading));
        new ResolveTask().execute(episode);
    }

    private void openPlayer(String url, String title) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URL, url);
        intent.putExtra(PlayerActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setBackgroundResource(R.drawable.focusable_panel);
        return button;
    }

    private EditText edit(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setHintTextColor(Color.rgb(145, 156, 168));
        edit.setTextColor(Color.WHITE);
        edit.setTextSize(16);
        edit.setSingleLine(true);
        edit.setPadding(dp(12), 0, dp(12), 0);
        edit.setBackgroundResource(R.drawable.focusable_panel);
        edit.setFocusable(true);
        return edit;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private LinearLayout.LayoutParams weightParams(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.setMargins(0, 0, dp(8), 0);
        return params;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(112), -1);
        params.setMargins(0, 0, dp(8), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void saveRule(String raw, SiteRule rule) {
        activeRuleJson = raw;
        activeRule = rule;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_RULE_JSON, raw).apply();
        ruleStatus.setText(getString(R.string.source_label) + ": " + rule.displayName());
    }

    private void showTaskError(String message) {
        status.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private final class ImportTask extends AsyncTask<String, Void, TaskResult<SiteRule>> {
        private String raw;

        @Override
        protected TaskResult<SiteRule> doInBackground(String... values) {
            raw = values[0];
            try {
                if (!looksLikeJson(raw)) {
                    raw = HttpClient.get(raw, null).text("UTF-8");
                }
                return TaskResult.success(RuleStore.parse(raw));
            } catch (Exception error) {
                return TaskResult.failure(error);
            }
        }

        @Override
        protected void onPostExecute(TaskResult<SiteRule> result) {
            if (result.error != null) {
                showTaskError("规则导入失败: " + shortMessage(result.error));
                return;
            }
            saveRule(raw, result.value);
            status.setText(getString(R.string.rule_saved));
        }
    }

    private final class ReadFileTask extends AsyncTask<Uri, Void, TaskResult<String>> {
        @Override
        protected TaskResult<String> doInBackground(Uri... values) {
            InputStream input = null;
            try {
                input = getContentResolver().openInputStream(values[0]);
                return TaskResult.success(readLimited(input, MAX_RULE_BYTES));
            } catch (Exception error) {
                return TaskResult.failure(error);
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        @Override
        protected void onPostExecute(TaskResult<String> result) {
            if (result.error != null) {
                showTaskError("读取规则失败: " + shortMessage(result.error));
                return;
            }
            importRule(result.value);
        }
    }

    private final class SearchTask extends AsyncTask<String, Void, TaskResult<List<VideoSource>>> {
        @Override
        protected TaskResult<List<VideoSource>> doInBackground(String... values) {
            try {
                return TaskResult.success(RuleEngine.search(activeRule, values[0]));
            } catch (Exception error) {
                return TaskResult.failure(error);
            }
        }

        @Override
        protected void onPostExecute(TaskResult<List<VideoSource>> result) {
            if (result.error != null) {
                showTaskError("搜索失败: " + shortMessage(result.error));
                return;
            }
            resultAdapter.clear();
            resultAdapter.addAll(result.value);
            resultAdapter.notifyDataSetChanged();
            status.setText(String.format(Locale.US, "找到 %d 个结果", result.value.size()));
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
                showTaskError("剧集加载失败: " + shortMessage(result.error));
                return;
            }
            showEpisodes(result.value);
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
                showTaskError(getString(R.string.play_error) + ": " + shortMessage(result.error));
                return;
            }
            status.setText("");
            openPlayer(result.value.url, result.value.episode.title);
        }
    }

    private static boolean looksLikeJson(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String readLimited(InputStream input, int limit) throws IOException {
        if (input == null) {
            throw new IOException("empty file");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) {
                throw new IOException("rule file is too large");
            }
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }

    private static String shortMessage(Exception error) {
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
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
