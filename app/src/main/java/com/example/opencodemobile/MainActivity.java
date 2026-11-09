package com.example.opencodemobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.text.DateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_PROJECT = 10;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ConfigStore configStore;
    private ProjectFiles projectFiles;
    private SessionCacheStore sessionCacheStore;
    private OpenCodeClient openCode;
    private LinearLayout root;
    private LinearLayout messages;
    private LinearLayout fileList;
    private ScrollView chatScroll;
    private EditText input;
    private TextView projectLabel;
    private TextView statusLabel;
    private TextView runtimeLabel;
    private Button modelPickerButton;
    private Button sessionPickerButton;
    private Runnable modelManagerRefresh;
    private List<OpenCodeClient.SessionInfo> sessionCache = new ArrayList<>();
    private String sessionCacheKey;
    private boolean sessionCacheLoaded;
    private LinearLayout sessionRows;
    private AlertDialog sessionDialog;
    private String currentDirectory = "";
    private String progressMessage = "";
    private long progressStartedAt;
    private long progressUpdatedAt;
    private final Runnable zenLimitsRefresh = this::refreshActiveZenLimits;
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            if (progressMessage.isEmpty() || statusLabel == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            long elapsed = (now - progressStartedAt) / 1000;
            long quietSeconds = (now - progressUpdatedAt) / 1000;
            String message = progressMessage;
            if (quietSeconds >= 30
                    && (message.contains("模型") || message.contains("工具") || message.contains("Core"))) {
                message = "当前阶段仍在进行，暂未收到新进度事件…";
            }
            statusLabel.setText(message + "（已用时 " + elapsed + " 秒）");
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configStore = new ConfigStore(this);
        projectFiles = new ProjectFiles(this);
        sessionCacheStore = new SessionCacheStore(this);
        openCode = new OpenCodeClient(this, projectFiles);
        render();
        mainHandler.postDelayed(zenLimitsRefresh, 2_000);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(progressTicker);
        mainHandler.removeCallbacks(zenLimitsRefresh);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(0xFFF6F7F9);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("OpenCode Mobile", 22, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        sessionPickerButton = button("会话 ▾");
        sessionPickerButton.setOnClickListener(v -> showSessions());
        header.addView(sessionPickerButton);
        Button newSession = button("＋");
        newSession.setContentDescription("新建会话");
        newSession.setOnClickListener(v -> newSession());
        header.addView(newSession);
        Button settings = button("模型设置");
        settings.setOnClickListener(v -> showModelManager());
        header.addView(settings);
        root.addView(header);

        runtimeLabel = text("Bun 1.4.2 内置运行时 · OpenCode Core 按需启动", 12, false);
        root.addView(runtimeLabel);

        LinearLayout projectRow = new LinearLayout(this);
        projectRow.setGravity(Gravity.CENTER_VERTICAL);
        projectLabel = text("未授权项目目录", 14, false);
        projectRow.addView(projectLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Button choose = button("选择目录");
        choose.setOnClickListener(v -> chooseProject());
        projectRow.addView(choose);
        root.addView(projectRow);

        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        fileList.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(fileList);

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(0, dp(8), 0, dp(8));
        chatScroll.addView(messages);
        root.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        statusLabel = text("配置模型并选择项目目录后开始", 12, false);
        root.addView(statusLabel);
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelPickerButton = button(modelButtonLabel());
        modelPickerButton.setOnClickListener(v -> showModelPicker());
        modelRow.addView(modelPickerButton);
        Button manageModels = button("管理模型");
        manageModels.setOnClickListener(v -> showModelManager());
        modelRow.addView(manageModels);
        root.addView(modelRow);
        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.BOTTOM);
        input = new EditText(this);
        input.setHint("描述你要完成的任务…");
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = button("发送");
        send.setOnClickListener(v -> sendMessage());
        composer.addView(send);
        root.addView(composer);

        Uri tree = projectFiles.loadTree();
        if (tree != null) {
            loadProject(tree);
            restoreHistory();
        }
        if (!configStore.load().isReady()) statusLabel.setText(R.string.model_setup_prompt);
    }

    private void chooseProject() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_PROJECT);
    }

    private void refreshActiveZenLimits() {
        ModelConfig active = configStore.load();
        if (!OpenCodeZenCatalog.BASE_URL.equals(active.baseUrl) || active.model.isEmpty()) return;
        Thread refresh = new Thread(() -> {
            try {
                for (OpenCodeZenCatalog.Model model : OpenCodeZenCatalog.fetch()) {
                    if (!active.model.equals(model.id)) continue;
                    configStore.updateModelLimits(active.id, model.contextLimit, model.outputLimit);
                    runOnUiThread(() -> {
                        if (progressMessage.isEmpty() && statusLabel != null) {
                            statusLabel.setText("已同步 " + model.name + " 的上下文限制");
                        }
                    });
                    return;
                }
                runOnUiThread(() -> {
                    if (progressMessage.isEmpty() && statusLabel != null) {
                        statusLabel.setText("模型目录中未找到当前模型，继续使用已保存的限制");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (progressMessage.isEmpty() && statusLabel != null) {
                        statusLabel.setText("模型限制暂未刷新，继续使用已保存的限制：" + messageOf(e));
                    }
                });
            }
        }, "zen-model-limits");
        refresh.setDaemon(true);
        refresh.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PROJECT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0
                    || (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                throw new IllegalStateException("OpenCode 需要同时读取和写入权限，请重新选择并授权目录");
            }
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            projectFiles.saveTree(tree);
            currentDirectory = "";
            messages.removeAllViews();
            loadProject(tree);
            restoreHistory();
        } catch (Exception e) {
            showError("无法授权项目目录：" + messageOf(e));
        }
    }

    private void loadProject(Uri tree) {
        worker.execute(() -> {
            try {
                String name = projectFiles.treeName(tree);
                JSONArray entries = projectFiles.list(currentDirectory);
                runOnUiThread(() -> {
                    String suffix = currentDirectory.isEmpty()
                            ? "" : getString(R.string.project_path_suffix, currentDirectory);
                    projectLabel.setText(getString(R.string.project_label, name, suffix));
                    renderFiles(entries);
                    statusLabel.setText(R.string.project_authorized);
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("读取项目目录失败：" + messageOf(e)));
            }
        });
    }

    private void renderFiles(JSONArray entries) {
        fileList.removeAllViews();
        if (!currentDirectory.isEmpty()) {
            addFileRow("⬆  返回上级", true, () -> {
                int slash = currentDirectory.lastIndexOf('/');
                currentDirectory = slash < 0 ? "" : currentDirectory.substring(0, slash);
                Uri tree = projectFiles.loadTree();
                if (tree != null) loadProject(tree);
            });
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name");
            boolean directory = item.optBoolean("directory");
            String label = (directory ? "📁  " : "📄  ") + name;
            addFileRow(label, directory, () -> {
                if (directory) {
                    currentDirectory = currentDirectory.isEmpty() ? name : currentDirectory + "/" + name;
                    Uri tree = projectFiles.loadTree();
                    if (tree != null) loadProject(tree);
                } else {
                    String path = currentDirectory.isEmpty() ? name : currentDirectory + "/" + name;
                    openFile(path);
                }
            });
        }
        if (entries.length() == 0) {
            TextView empty = text("目录为空", 13, false);
            fileList.addView(empty);
        }
    }

    private void addFileRow(String label, boolean directory, Runnable action) {
        TextView row = text(label, 14, false);
        row.setPadding(dp(8), dp(5), dp(8), dp(5));
        row.setOnClickListener(v -> action.run());
        fileList.addView(row);
    }

    private void openFile(String path) {
        worker.execute(() -> {
            try {
                String contents = projectFiles.read(path);
                runOnUiThread(() -> editFile(path, contents));
            } catch (Exception e) {
                runOnUiThread(() -> showError("打开文件失败：" + messageOf(e)));
            }
        });
    }

    private void editFile(String path, String contents) {
        EditText editor = new EditText(this);
        editor.setText(contents);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(8);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this).setTitle(path).setView(editor)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> worker.execute(() -> {
                    try {
                        projectFiles.write(path, editor.getText().toString());
                        runOnUiThread(() -> {
                            statusLabel.setText(getString(R.string.file_saved, path));
                            Uri tree = projectFiles.loadTree();
                            if (tree != null) loadProject(tree);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> showError("保存文件失败：" + messageOf(e)));
                    }
                })).show();
    }

    private String modelButtonLabel() {
        ModelConfig config = configStore.load();
        return config.isReady() ? "模型：" + config.displayName() + " ▾" : "选择模型 ▾";
    }

    private void updateModelPicker() {
        if (modelPickerButton != null) modelPickerButton.setText(modelButtonLabel());
    }

    private void showModelPicker() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), 0, dp(16), 0);
        AlertDialog[] dialogReference = new AlertDialog[1];
        EditText search = field("搜索模型", "", false);
        panel.addView(search);
        Button zen = button("OpenCode Zen · 浏览官方模型");
        zen.setOnClickListener(v -> {
            dialogReference[0].dismiss();
            connectOpenCodeZen();
        });
        panel.addView(zen);
        Button manage = button("⚙ 管理模型");
        panel.addView(manage);
        ScrollView scroll = new ScrollView(this);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(choices);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(320)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择模型")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .create();
        dialogReference[0] = dialog;
        Runnable renderChoices = () -> {
            choices.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            String activeID = configStore.load().id;
            for (ModelConfig profile : configStore.profiles()) {
                String searchable = (profile.displayName() + " " + profile.model + " " + profile.baseUrl)
                        .toLowerCase(java.util.Locale.ROOT);
                if (!query.isEmpty() && !searchable.contains(query)) continue;
                Button choice = button((profile.id.equals(activeID) ? "✓  " : "")
                        + profile.displayName() + "  ·  " + profile.model);
                choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                choice.setOnClickListener(v -> {
                    try {
                        configStore.select(profile.id);
                        updateModelPicker();
                        dialog.dismiss();
                        messages.removeAllViews();
                        restoreHistory();
                        statusLabel.setText("已选择模型：" + profile.displayName());
                    } catch (Exception e) {
                        showError("切换模型失败：" + messageOf(e));
                    }
                });
                choices.addView(choice);
            }
            if (choices.getChildCount() == 0) {
                choices.addView(text("没有匹配的模型配置", 14, false));
            }
        };
        manage.setOnClickListener(v -> {
            dialog.dismiss();
            showModelManager();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderChoices.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        renderChoices.run();
        dialog.show();
    }

    private void connectOpenCodeZen() {
        String savedKey = configStore.zenApiKey();
        if (!savedKey.isEmpty()) {
            loadOpenCodeZenModels(savedKey);
            return;
        }
        showZenApiKeyDialog();
    }

    private void showZenApiKeyDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        TextView instructions = text(
                "输入你的 OpenCode Zen API Key。获取入口：https://opencode.ai/zen\n"
                        + "Zen 是否提供免费额度及具体限额，以账户和服务商当前规则为准。",
                14, false);
        form.addView(instructions);
        EditText key = field("输入新的 OpenCode Zen API Key", "", true);
        form.addView(key);
        new AlertDialog.Builder(this).setTitle("连接 OpenCode Zen")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("连接并浏览模型", (dialog, which) -> {
                    String value = key.getText().toString().trim();
                    if (value.isEmpty()) {
                        showError("请输入 OpenCode Zen API Key");
                        return;
                    }
                    try {
                        configStore.saveZenApiKey(value);
                        loadOpenCodeZenModels(value);
                    } catch (Exception e) {
                        showError("保存 Zen API Key 失败：" + messageOf(e));
                    }
                }).show();
    }

    private void loadOpenCodeZenModels(String apiKey) {
        statusLabel.setText("正在获取 OpenCode Zen 模型列表…");
        worker.execute(() -> {
            try {
                List<OpenCodeZenCatalog.Model> models = OpenCodeZenCatalog.fetch();
                runOnUiThread(() -> showOpenCodeZenModels(models, apiKey));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusLabel.setText("OpenCode Zen 模型列表获取失败");
                    showError("获取 OpenCode Zen 模型列表失败：" + messageOf(e));
                });
            }
        });
    }

    private void showOpenCodeZenModels(List<OpenCodeZenCatalog.Model> models, String apiKey) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), 0, dp(16), 0);
        EditText search = field("搜索 Zen 模型", "", false);
        panel.addView(search);
        TextView notice = text("免费标记来自 Zen 模型目录；实际可用性和限额由 Zen 账户决定。",
                12, false);
        panel.addView(notice);
        ScrollView scroll = new ScrollView(this);
        LinearLayout entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(380)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("OpenCode Zen 模型")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .create();
        Runnable renderEntries = () -> {
            entries.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            for (OpenCodeZenCatalog.Model model : models) {
                if (!query.isEmpty() && !(model.name + " " + model.id)
                        .toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                Button choice = button(model.name + (model.free ? "   免费" : "")
                        + "\n" + model.id);
                choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                choice.setOnClickListener(v -> {
                    try {
                        configStore.saveZenModel(model, apiKey);
                        updateModelPicker();
                        dialog.dismiss();
                        messages.removeAllViews();
                        restoreHistory();
                        statusLabel.setText("已选择 OpenCode Zen：" + model.name);
                    } catch (Exception e) {
                        showError("选择 Zen 模型失败：" + messageOf(e));
                    }
                });
                entries.addView(choice);
            }
            if (entries.getChildCount() == 0) {
                entries.addView(text("没有匹配的 Zen 模型", 14, false));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderEntries.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        renderEntries.run();
        dialog.show();
    }

    private void showModelManager() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), 0, dp(16), 0);
        Button zenSettings = button("OpenCode Zen · 连接 / 更换 API Key");
        zenSettings.setOnClickListener(v -> showZenApiKeyDialog());
        panel.addView(zenSettings);
        ScrollView scroll = new ScrollView(this);
        LinearLayout entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(360)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("管理模型")
                .setView(panel)
                .setNegativeButton("完成", null)
                .setPositiveButton("添加模型", null)
                .create();
        Runnable[] renderProfiles = new Runnable[1];
        renderProfiles[0] = () -> {
            entries.removeAllViews();
            List<ModelConfig> profiles = configStore.profiles();
            String activeID = configStore.load().id;
            for (ModelConfig profile : profiles) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                Button name = button((profile.id.equals(activeID) ? "✓ " : "")
                        + profile.displayName() + "\n" + profile.model);
                name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                name.setOnClickListener(v -> showModelEditor(profile));
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                Button edit = button("编辑");
                edit.setOnClickListener(v -> showModelEditor(profile));
                row.addView(edit);
                Button delete = button("删除");
                delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("删除模型配置")
                        .setMessage("确定删除“" + profile.displayName() + "”吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (confirm, which) -> {
                            configStore.delete(profile.id);
                            updateModelPicker();
                            renderProfiles[0].run();
                        }).show());
                row.addView(delete);
                entries.addView(row);
            }
            if (profiles.isEmpty()) {
                entries.addView(text("尚未添加模型。添加 HTTPS 兼容 OpenAI 的模型 API 后即可开始。", 14, false));
            }
        };
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> showModelEditor(null)));
        modelManagerRefresh = renderProfiles[0];
        dialog.setOnDismissListener(ignored -> {
            if (modelManagerRefresh == renderProfiles[0]) modelManagerRefresh = null;
        });
        renderProfiles[0].run();
        dialog.show();
    }

    private void showModelEditor(ModelConfig profile) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText name = field("显示名称，例如 OpenAI", profile == null ? "" : profile.displayName(), false);
        EditText baseUrl = field("API 地址，例如 https://api.openai.com/v1",
                profile == null ? "https://api.openai.com/v1" : profile.baseUrl, false);
        EditText model = field("模型 ID，例如 gpt-4o-mini", profile == null ? "" : profile.model, false);
        EditText apiKey = field(profile == null ? "API Key" : "API Key（留空保留已保存密钥）", "", true);
        form.addView(name);
        form.addView(baseUrl);
        form.addView(model);
        form.addView(apiKey);
        new AlertDialog.Builder(this).setTitle(profile == null ? "添加模型" : "编辑模型")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        configStore.save(profile == null ? null : profile.id,
                                name.getText().toString(), baseUrl.getText().toString(),
                                apiKey.getText().toString(), model.getText().toString());
                        updateModelPicker();
                        if (modelManagerRefresh != null) modelManagerRefresh.run();
                        statusLabel.setText(R.string.model_config_saved);
                    } catch (Exception e) {
                        showError("保存配置失败：" + messageOf(e));
                    }
                }).show();
    }

    private void showSessions() {
        ModelConfig config = configStore.load();
        if (!config.isReady()) {
            showModelManager();
            return;
        }
        Uri tree = projectFiles.loadTree();
        if (tree == null) {
            showError("请先选择并授权项目目录");
            return;
        }
        String cacheKey = tree.toString();
        if (!cacheKey.equals(sessionCacheKey)) {
            sessionCache = new ArrayList<>();
            sessionCacheKey = cacheKey;
            sessionCacheLoaded = false;
        }
        AlertDialog dialog = renderSessions(sessionCache, !sessionCacheLoaded);
        boolean needsDiskCache = !sessionCacheLoaded;
        statusLabel.setText("正在读取 OpenCode 会话…");
        worker.execute(() -> {
            try {
                if (needsDiskCache) {
                    List<OpenCodeClient.SessionInfo> cached = sessionCacheStore.load(tree);
                    runOnUiThread(() -> {
                        if (!cacheKey.equals(sessionCacheKey)) return;
                        sessionCacheLoaded = true;
                        if (cached != null) {
                            sessionCache = new ArrayList<>(cached);
                            if (sessionDialog == dialog && dialog.isShowing()) {
                                populateSessionRows(cached, false, dialog);
                            }
                        }
                    });
                }
                List<OpenCodeClient.SessionInfo> sessions = openCode.sessions(config, message ->
                        runOnUiThread(() -> {
                            if (sessionDialog == dialog && dialog.isShowing() && statusLabel != null) {
                                statusLabel.setText(message);
                            }
                        }));
                sessionCacheStore.save(tree, sessions);
                runOnUiThread(() -> {
                    if (!cacheKey.equals(sessionCacheKey)) return;
                    sessionCache = new ArrayList<>(sessions);
                    sessionCacheLoaded = true;
                    if (sessionDialog == dialog && dialog.isShowing()) {
                        populateSessionRows(sessions, false, dialog);
                    }
                    if (statusLabel != null) statusLabel.setText("会话列表已更新");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    sessionCacheLoaded = true;
                    if (sessionDialog == dialog && dialog.isShowing() && sessionCache.isEmpty()) {
                        populateSessionRows(sessionCache, false, dialog);
                        addSessionMessage("读取会话失败：" + messageOf(e));
                    } else {
                        statusLabel.setText("会话刷新失败：" + messageOf(e));
                    }
                });
            }
        });
        if (sessionCache.isEmpty()) {
            // The dialog is already visible while the local Core starts in the background.
            statusLabel.setText("会话窗口已打开，正在加载列表…");
        }
    }

    private AlertDialog renderSessions(List<OpenCodeClient.SessionInfo> sessions, boolean loading) {
        String activeID = openCode.activeSession();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), 0, dp(16), 0);
        AlertDialog[] dialogReference = new AlertDialog[1];
        Button create = button("＋ 新建会话");
        create.setOnClickListener(v -> newSession(dialogReference[0]));
        panel.addView(create);
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        sessionRows = rows;
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(360)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("会话")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .create();
        dialogReference[0] = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (sessionDialog == dialog) {
                sessionDialog = null;
                sessionRows = null;
            }
        });
        sessionDialog = dialog;
        populateSessionRows(sessions, loading, dialog);
        dialog.show();
        return dialog;
    }

    private void renderSessions(List<OpenCodeClient.SessionInfo> sessions) {
        sessionCache = new ArrayList<>(sessions);
        sessionCacheLoaded = true;
        renderSessions(sessions, false);
    }

    private void populateSessionRows(List<OpenCodeClient.SessionInfo> sessions, boolean loading,
                                    AlertDialog dialog) {
        if (sessionDialog != dialog || sessionRows == null) return;
        LinearLayout rows = sessionRows;
        rows.removeAllViews();
        if (loading) {
            rows.addView(text("正在加载会话…", 14, false));
            return;
        }
        String activeID = openCode.activeSession();
        for (OpenCodeClient.SessionInfo session : sessions) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            String updated = session.updated == 0 ? "" : DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(new Date(session.updated));
            Button select = button((session.id.equals(activeID) ? "✓ " : "")
                    + session.title + (updated.isEmpty() ? "" : "\n" + updated));
            select.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            select.setOnClickListener(v -> {
                try {
                    openCode.activateSession(session.id);
                    dialog.dismiss();
                    messages.removeAllViews();
                    sessionPickerButton.setText("会话 ▾");
                    restoreHistory();
                    statusLabel.setText("已切换会话：" + session.title);
                } catch (Exception e) {
                    showError("切换会话失败：" + messageOf(e));
                }
            });
            row.addView(select, new LinearLayout.LayoutParams(0, -2, 1));
            Button delete = button("删除");
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("删除会话")
                    .setMessage("确定删除“" + session.title + "”及其 OpenCode 历史吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (confirm, which) -> deleteSession(session, dialog))
                    .show());
            row.addView(delete);
            rows.addView(row);
        }
        if (sessions.isEmpty()) rows.addView(text("还没有会话，点击“新建会话”开始。", 14, false));
    }

    private void addSessionMessage(String message) {
        if (sessionRows == null) return;
        TextView error = text(message, 14, false);
        error.setTextColor(0xFFB3261E);
        sessionRows.addView(error);
    }

    private void newSession() {
        newSession(null);
    }

    private void newSession(AlertDialog sessionDialog) {
        ModelConfig config = configStore.load();
        if (!config.isReady()) {
            showModelManager();
            return;
        }
        Uri tree = projectFiles.loadTree();
        if (tree == null) {
            showError("请先选择并授权项目目录");
            return;
        }
        statusLabel.setText("正在创建 OpenCode 会话…");
        worker.execute(() -> {
            try {
                openCode.createSession(config, "新会话");
                sessionCacheStore.clear(tree);
                runOnUiThread(() -> {
                    sessionCacheKey = null;
                    sessionCacheLoaded = false;
                    sessionCache.clear();
                    if (sessionDialog != null) sessionDialog.dismiss();
                    messages.removeAllViews();
                    sessionPickerButton.setText("新会话 ▾");
                    statusLabel.setText("已创建新会话");
                    input.requestFocus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("创建会话失败：" + messageOf(e)));
            }
        });
    }

    private void deleteSession(OpenCodeClient.SessionInfo session, AlertDialog sessionDialog) {
        ModelConfig config = configStore.load();
        Uri tree = projectFiles.loadTree();
        worker.execute(() -> {
            try {
                openCode.deleteSession(config, session.id);
                List<OpenCodeClient.SessionInfo> remaining = openCode.sessions(config);
                if (tree != null) sessionCacheStore.save(tree, remaining);
                String active = openCode.activeSession();
                if ((active == null || active.isEmpty()) && !remaining.isEmpty()) {
                    openCode.activateSession(remaining.get(0).id);
                }
                runOnUiThread(() -> {
                    sessionDialog.dismiss();
                    messages.removeAllViews();
                    renderSessions(remaining);
                    restoreHistory();
                    statusLabel.setText("会话已删除");
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("删除会话失败：" + messageOf(e)));
            }
        });
    }

    private void sendMessage() {
        String prompt = input.getText().toString().trim();
        if (prompt.isEmpty()) return;
        ModelConfig config = configStore.load();
        if (!config.isReady()) {
            showModelManager();
            return;
        }
        if (projectFiles.loadTree() == null) {
            showError("请先选择项目目录并授予访问权限");
            return;
        }
        input.setText("");
        appendMessage("你", prompt);
        startProgress("准备同步手机项目…");
        input.setEnabled(false);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            try {
                String answer = openCode.respond(config, prompt, message ->
                        runOnUiThread(() -> updateProgress(message)));
                runOnUiThread(() -> {
                    stopProgress();
                    appendMessage("OpenCode 2.0.32",
                            answer.isEmpty() ? "（模型未返回文本内容）" : answer);
                    statusLabel.setText("完成");
                    input.setEnabled(true);
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    input.requestFocus();
                    refreshCurrentProject();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    stopProgress();
                    appendMessage("错误", messageOf(e));
                    statusLabel.setText("请求失败");
                    input.setEnabled(true);
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
            }
        });
    }

    private void startProgress(String message) {
        progressStartedAt = android.os.SystemClock.elapsedRealtime();
        progressUpdatedAt = progressStartedAt;
        progressMessage = message;
        mainHandler.removeCallbacks(progressTicker);
        progressTicker.run();
    }

    private void updateProgress(String message) {
        progressMessage = message;
        progressUpdatedAt = android.os.SystemClock.elapsedRealtime();
        if (statusLabel != null) {
            mainHandler.removeCallbacks(progressTicker);
            progressTicker.run();
        }
    }

    private void stopProgress() {
        progressMessage = "";
        mainHandler.removeCallbacks(progressTicker);
    }

    private void refreshCurrentProject() {
        Uri tree = projectFiles.loadTree();
        if (tree != null) loadProject(tree);
    }

    private void restoreHistory() {
        ModelConfig config = configStore.load();
        if (!config.isReady()) return;
        worker.execute(() -> {
            try {
                List<OpenCodeClient.ChatMessage> history = openCode.history(config);
                runOnUiThread(() -> {
                    if (messages.getChildCount() != 0) return;
                    for (OpenCodeClient.ChatMessage item : history) appendMessage(item.speaker, item.text);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (messages.getChildCount() == 0) {
                        appendMessage("历史恢复失败", messageOf(e));
                    }
                });
            }
        });
    }

    private void appendMessage(String speaker, String content) {
        SpannableStringBuilder formatted = new SpannableStringBuilder(speaker);
        formatted.setSpan(new StyleSpan(Typeface.BOLD), 0, formatted.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        formatted.setSpan(new ForegroundColorSpan(0xFF475467), 0, formatted.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        formatted.append("\n\n");
        if (speaker.startsWith("OpenCode ")) {
            formatted.append(MarkdownRenderer.render(content));
        } else {
            formatted.append(content);
        }
        TextView bubble = text("", 14, false);
        bubble.setText(formatted);
        bubble.setTextIsSelectable(true);
        bubble.setLineSpacing(dp(3), 1.0f);
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        bubble.setBackgroundColor(speaker.equals("你") ? 0xFFE2ECFF : 0xFFFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(8));
        messages.addView(bubble, params);
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setText(value);
        if (secret) field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(0xFF1B1B1F);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        return button;
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle("提示").setMessage(message)
                .setPositiveButton("确定", null).show();
    }

    private static String messageOf(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
