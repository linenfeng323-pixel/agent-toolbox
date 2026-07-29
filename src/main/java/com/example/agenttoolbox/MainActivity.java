package com.example.agenttoolbox;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.CheckBox;
import android.app.AlertDialog;
import android.text.InputType;

import com.example.agenttoolbox.AppLogger;
import com.example.agenttoolbox.mcp.McpServer;

import java.io.File;
import java.util.Deque;
import java.util.LinkedList;

/**
 * 主Activity - MCP服务端控制界面
 */
public class MainActivity extends Activity {

    private TextView tvStatus;
    private TextView tvAddress;
    private TextView tvLog;
    private Button btnStart;
    private Button btnStop;
    private Button btnDeepSeek;
    private Button btnAiPlatform;
    private Button btnGithub;
    private Button btnMyRepos;
    private Button btnCompile;
    private Button btnCheckPort;
    private EditText etPort;
    private Spinner spinnerBind;
    private TextView statusChip;

    
    private McpServer mcpServer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler uiLogHandler = new Handler(Looper.getMainLooper());
    private Deque<String> logDeque = new LinkedList<>();
    private int logTotalChars = 0; // 当前 deque 中总字符数
    private static final int MAX_LOGS = 500;       // 最多保存 500 条（原 1000）
    private static final int MAX_LOG_CHARS = 200 * 1024;         // 总字符上限 200KB
    private static final int MAX_DISPLAY_MSG_LEN = 2 * 1024;     // 单条显示截断 2KB
    
    private static final int DEFAULT_PORT = 8080;
    private int currentPort = DEFAULT_PORT;
    private String currentBindAddress = "0.0.0.0";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化视图
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvAddress = (TextView) findViewById(R.id.tvAddress);
        tvLog = (TextView) findViewById(R.id.tvLog);
        btnStart = (Button) findViewById(R.id.btnStart);
        btnStop = (Button) findViewById(R.id.btnStop);
        btnDeepSeek = (Button) findViewById(R.id.btnDeepSeek);
        btnAiPlatform = (Button) findViewById(R.id.btnAiPlatform);
        btnGithub = (Button) findViewById(R.id.btnGithub);
        btnMyRepos = (Button) findViewById(R.id.btnMyRepos);
        btnCompile = (Button) findViewById(R.id.btnCompile);
        statusChip = (TextView) findViewById(R.id.statusChip);
        etPort = (EditText) findViewById(R.id.etPort);
        spinnerBind = (Spinner) findViewById(R.id.spinnerBind);
        btnCheckPort = (Button) findViewById(R.id.btnCheckPort);

        // 绑定地址下拉选项
        String[] bindOptions = {"0.0.0.0 (所有网卡)", "127.0.0.1 (仅本机)"};
        ArrayAdapter<String> bindAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, bindOptions);
        bindAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBind.setAdapter(bindAdapter);

        // 读取上次保存的端口和绑定地址
        SharedPreferences prefs = getSharedPreferences("mcp_config", MODE_PRIVATE);
        int savedPort = prefs.getInt("port", DEFAULT_PORT);
        String savedBind = prefs.getString("bind_address", "0.0.0.0");
        etPort.setText(String.valueOf(savedPort));
        spinnerBind.setSelection("127.0.0.1".equals(savedBind) ? 1 : 0);

        // 端口检查按钮
        btnCheckPort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPort();
            }
        });

        // 初始化文件目录
        initFileDir();

        // 设置按钮点击事件
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServer();
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopServer();
            }
        });

        btnDeepSeek.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDeepSeek();
            }
        });

        btnAiPlatform.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAiPlatformPicker();
            }
        });

        btnGithub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGithubConfigDialog();
            }
        });

        btnMyRepos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMyReposDialog();
            }
        });

        btnCompile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCompileDialog();
            }
        });

        // 点击监听地址复制到剪贴板
        tvAddress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String addr = tvAddress.getText().toString();
                if (addr == null || addr.isEmpty() || addr.equals("--")) return;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("MCP 地址", addr));
                Toast.makeText(MainActivity.this, "地址已复制", Toast.LENGTH_SHORT).show();
            }
        });

        // 技能安装目录
        final TextView tvSkillsPath = (TextView) findViewById(R.id.tvSkillsPath);
        com.example.agenttoolbox.skills.SkillManager.getInstance().init(this);
        final String skillsPath = com.example.agenttoolbox.skills.SkillManager.getInstance().getRuntimeSkillsPath();
        if (skillsPath != null) {
            tvSkillsPath.setText("技能目录: " + skillsPath);
            tvSkillsPath.setVisibility(View.VISIBLE);
            tvSkillsPath.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("技能目录", skillsPath));
                    Toast.makeText(MainActivity.this, "技能目录路径已复制", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 申请存储权限
        checkAndRequestPermissions();

        appendLog("Agent工具箱 MCP服务端已就绪");
        appendLog("点击\"启动MCP服务\"按钮开始服务");

        // 初始化 AI 按钮文字（显示当前选中的平台）
        updateAiButtonText();

        // 检查 APP 更新（异步，不影响启动）
        UpdateChecker.check(this);
    }

    /** 更新 AI 助手按钮文字，显示当前选中的平台 */
    private void updateAiButtonText() {
        if (btnDeepSeek == null) return;
        String name = AiPlatformManager.getCurrentName(this);
        String icon = AiPlatformManager.getCurrentIcon(this);
        btnDeepSeek.setText(icon + " " + name);
        if (btnAiPlatform != null) {
            btnAiPlatform.setText("🔄 当前: " + name + " (点击切换)");
        }
    }
    
    /**
     * 初始化文件目录
     */
    private void initFileDir() {
        File filesDir = getFilesDir();
        if (!filesDir.exists()) {
            filesDir.mkdirs();
        }
        // 同时创建应用专属外部存储目录（不需要权限即可访问）
        try {
            File externalDir = getExternalFilesDir(null);
            if (externalDir != null && !externalDir.exists()) {
                externalDir.mkdirs();
            }
        } catch (Exception e) {
            // 忽略外部存储不可用的情况
        }
    }

    /**
     * 检查并申请存储权限
     */
    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：需要 MANAGE_EXTERNAL_STORAGE 权限
            if (Environment.isExternalStorageManager()) {
                appendLog("存储权限：已授权（所有文件访问）");
            } else {
                appendLog("正在请求存储权限...");
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                } catch (Exception e) {
                    // 如果上面的 Intent 不可用，回退到通用的设置页
                    try {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                    } catch (Exception e2) {
                        appendLog("无法打开权限设置页，请手动授予权限");
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6.0 - 10：需要运行时申请读写权限
            boolean hasRead = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean hasWrite = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (hasRead && hasWrite) {
                appendLog("存储权限：已授权");
            } else {
                appendLog("正在请求存储权限...");
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, PERMISSION_REQUEST_CODE);
            }
        } else {
            // Android 5 及以下：安装时自动获得权限
            appendLog("存储权限：已授权（低版本系统）");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                appendLog("存储权限：已授权");
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                appendLog("存储权限：被拒绝，外部文件工具可能受限");
                Toast.makeText(this, "未获得存储权限，部分功能受限", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == 1003) {
            // 通知权限结果，继续启动服务
            appendLog("通知权限请求完成，继续启动服务");
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                appendLog("通知权限已授予");
            } else {
                appendLog("通知权限被拒绝，但继续启动服务");
            }
            // 延迟一点再启动，让权限对话框完全关闭
            final Handler h2 = handler;
            h2.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startServer();
                }
            }, 500);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MANAGE_STORAGE_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    appendLog("存储权限：已授权（所有文件访问）");
                    Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("存储权限：未授予，外部文件工具可能受限");
                    Toast.makeText(this, "未获得完整存储权限，部分功能受限", Toast.LENGTH_LONG).show();
                }
            }
        }
    }
    
    /**
     * 启动服务 - 直接在Activity中启动
     */
    private void startServer() {
        try {
            appendLog("正在启动MCP服务...");

            // 先启动前台服务（WakeLock + 通知栏保活）
            Intent serviceIntent = new Intent(this, McpForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            // 如果已有服务器在运行，先停掉（防止 EADDRINUSE）
            if (mcpServer != null && mcpServer.isRunning()) {
                mcpServer.stop();
                mcpServer = null;
            }

            // 读取用户输入的端口和绑定地址
            try {
                currentPort = Integer.parseInt(etPort.getText().toString().trim());
            } catch (NumberFormatException e) {
                currentPort = DEFAULT_PORT;
                appendLog("端口格式错误，使用默认端口 " + DEFAULT_PORT);
            }
            currentBindAddress = spinnerBind.getSelectedItemPosition() == 0 ? "0.0.0.0" : "127.0.0.1";

            // 先检查端口可用性
            String portError = McpServer.checkPortAvailable(currentPort, currentBindAddress);
            if (portError != null) {
                appendLog("❌ " + portError);
                appendLog("请更换端口后重试");
                return;
            }

            // 保存配置到 SharedPreferences
            SharedPreferences prefs = getSharedPreferences("mcp_config", MODE_PRIVATE);
            prefs.edit()
                .putInt("port", currentPort)
                .putString("bind_address", currentBindAddress)
                .apply();

            mcpServer = new McpServer(currentPort, currentBindAddress, MainActivity.this);
            // 初始化统一日志门面（同时输出到 UI 和 logcat）
            final Handler h = handler;
            AppLogger.init(new AppLogger.OnLogListener() {
                @Override
                public void onLog(final String message) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog(message);
                        }
                    });
                }
            }, MainActivity.this);
            mcpServer.setOnLogListener(new McpServer.OnLogListener() {
                @Override
                public void onLog(final String message) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog(message);
                        }
                    });
                }
            });
            mcpServer.start();

            tvStatus.setText("运行中");
            tvStatus.setTextColor(getResources().getColor(R.color.success));
            statusChip.setText("运行中");
            statusChip.setBackgroundResource(R.drawable.chip_on);
            statusChip.setTextColor(getResources().getColor(R.color.success));
            String displayAddr = currentBindAddress.equals("127.0.0.1")
                ? "127.0.0.1" : mcpServer.getLocalIpAddress();
            tvAddress.setText("http://" + displayAddr + ":" + currentPort);
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            appendLog("MCP服务启动成功");

        } catch (Exception e) {
            String error = "启动服务异常: " + e.getClass().getName() + "\n" + e.getMessage() + "\n\n堆栈:\n" + android.util.Log.getStackTraceString(e);
            appendLog(error);
            copyToClipboard(error);
        }
    }

    /**
     * 停止服务
     */
    private void stopServer() {
        if (mcpServer != null) {
            mcpServer.stop();
            mcpServer = null;
        }

        tvStatus.setText("已停止");
        tvStatus.setTextColor(getResources().getColor(R.color.text_muted));
        statusChip.setText("未启动");
        statusChip.setBackgroundResource(R.drawable.chip_off);
        statusChip.setTextColor(getResources().getColor(R.color.text_muted));
        tvAddress.setText("--");
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    /**
     * 检查端口可用性
     */
    private void checkPort() {
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            appendLog("❌ 端口格式错误: " + etPort.getText());
            return;
        }
        String bindAddr = spinnerBind.getSelectedItemPosition() == 0 ? "0.0.0.0" : "127.0.0.1";
        String result = McpServer.checkPortAvailable(port, bindAddr);
        if (result == null) {
            appendLog("✅ 端口 " + port + " 可用 (绑定: " + bindAddr + ")");
        } else {
            appendLog("❌ " + result);
        }
    }

    /**
     * 复制文本到剪贴板
     */
    private void copyToClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("MCP Error", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "错误信息已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 打开 DeepSeek 助手页面
     */
    private void openDeepSeek() {
        // 如果 MCP 服务没启动，先提示用户启动
        if (mcpServer == null || !mcpServer.isRunning()) {
            appendLog("提示：请先启动 MCP 服务，以便 " + AiPlatformManager.getCurrentName(this) + " 使用工具能力");
        }

        Intent intent = new Intent(MainActivity.this, DeepSeekActivity.class);
        startActivity(intent);
    }

    // ============================================================
    // ========== AI 平台切换 ==========
    // ============================================================

    /** 选择 AI 平台对话框（单选列表，点击直接切换） */
    private void showAiPlatformPicker() {
        String currentId = AiPlatformManager.getCurrentPlatformId(this);
        AiPlatformManager.Platform[] platforms = AiPlatformManager.getBuiltinPlatforms();

        String[] items = new String[platforms.length];
        int checkedItem = 0;
        for (int i = 0; i < platforms.length; i++) {
            AiPlatformManager.Platform p = platforms[i];
            items[i] = p.icon + "  " + p.name + "\n     " + p.desc;
            if (p.id.equals(currentId)) checkedItem = i;
        }

        final int[] selectedIdx = {checkedItem};
        new AlertDialog.Builder(this)
            .setTitle("选择 AI 助手平台")
            .setMessage("所有平台 MCP 工具调用能力完全相同，只是对话网页不同。\n点击平台直接切换，下次打开生效。")
            .setSingleChoiceItems(items, checkedItem, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    selectedIdx[0] = which;
                }
            })
            .setPositiveButton("切换并打开", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    AiPlatformManager.Platform p = platforms[selectedIdx[0]];
                    if ("custom".equals(p.id)) {
                        d.dismiss();
                        showCustomPlatformDialog();
                        return;
                    }
                    switchAiPlatform(p);
                }
            })
            .setNegativeButton("仅切换不打开", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    AiPlatformManager.Platform p = platforms[selectedIdx[0]];
                    if ("custom".equals(p.id)) {
                        d.dismiss();
                        showCustomPlatformDialog();
                        return;
                    }
                    switchAiPlatformOnly(p);
                }
            })
            .setNeutralButton("取消", null)
            .show();
    }

    /** 仅切换平台（不打开 Activity），销毁旧 WebView 强制下次重新加载 */
    private void switchAiPlatformOnly(AiPlatformManager.Platform p) {
        String oldId = AiPlatformManager.getCurrentPlatformId(this);
        AiPlatformManager.setCurrentPlatformId(this, p.id);
        try {
            DeepSeekChatBridge.getInstance().destroyBoundWebView();
        } catch (Exception e) {
            AppLogger.w("MainActivity", "销毁旧 WebView 失败: " + e.getMessage());
        }
        updateAiButtonText();
        if (!oldId.equals(p.id)) {
            appendLog("✅ 已切换 AI 平台: " + p.name + " (URL: " + p.url + ")");
            Toast.makeText(this, "已切换到 " + p.name + "\n下次打开将加载新平台", Toast.LENGTH_SHORT).show();
        }
    }

    /** 切换平台并打开 AI 助手 */
    private void switchAiPlatform(AiPlatformManager.Platform p) {
        switchAiPlatformOnly(p);
        final Handler h = handler;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                openDeepSeek();
            }
        }, 300);
    }

    /** 自定义平台输入对话框 */
    private void showCustomPlatformDialog() {
        final EditText etName = new EditText(this);
        etName.setHint("平台名称（如：我的AI）");
        etName.setText(AiPlatformManager.getCustomName(this));
        final EditText etUrl = new EditText(this);
        etUrl.setHint("平台网址（如：https://xxx.com）");
        etUrl.setText(AiPlatformManager.getCustomUrl(this));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density + 0.5f);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(etName);
        layout.addView(etUrl);
        new AlertDialog.Builder(this)
            .setTitle("自定义 AI 平台")
            .setView(layout)
            .setPositiveButton("保存并切换", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    String name = etName.getText().toString().trim();
                    String url = etUrl.getText().toString().trim();
                    if (name.isEmpty() || url.isEmpty()) {
                        Toast.makeText(MainActivity.this, "名称和网址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    AiPlatformManager.setCustomName(MainActivity.this, name);
                    AiPlatformManager.setCustomUrl(MainActivity.this, url);
                    switchAiPlatformOnly(AiPlatformManager.getPlatform("custom"));
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ============================================================
    // ========== GitHub 配置（Token） ==========
    // ============================================================

    /** GitHub 配置对话框：Token 输入、测试、教程、API base */
    private void showGithubConfigDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density + 0.5f);
        content.setPadding(pad, pad, pad, pad);

        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("GitHub 配置（第一步:申请 Token）");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(0xFF1E40AF);
        content.addView(tvTitle);

        TextView tvIntro = new TextView(this);
        tvIntro.setText("【这是什么？】Token 是 GitHub 的密码，填了才能自动编译、查进度、下载 APK。\n"
            + "【怎么申请？】点下方「📚教程」按钮，有完整图文步骤。\n"
            + "【填什么？】把申请到的 ghp_ 开头的字符串粘贴到下面输入框。\n"
            + "【要勾什么权限？】教程里有详细说明（必须勾 repo + workflow）。\n"
            + "【填完做什么？】点「🧪测试」验证是否可用。");
        tvIntro.setTextSize(13);
        tvIntro.setLineSpacing(0, 1.4f);
        tvIntro.setTextColor(0xFF374151);
        content.addView(tvIntro);

        content.addView(mkSpacer(12));

        // Token 输入
        TextView tvTokenLabel = new TextView(this);
        tvTokenLabel.setText("Token（把申请到的 ghp_ 开头字符串粘贴到这里）");
        tvTokenLabel.setTextSize(13);
        tvTokenLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(tvTokenLabel);
        final EditText etToken = new EditText(this);
        etToken.setHint("ghp_xxxxxxxxxxxxxxxxxxxx");
        etToken.setText(GithubConfigManager.getToken(this));
        etToken.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etToken.setTypeface(android.graphics.Typeface.MONOSPACE);
        etToken.setSingleLine(false);
        content.addView(etToken);

        // Token 操作按钮行
        LinearLayout tokenBtnRow = new LinearLayout(this);
        tokenBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnShow = new Button(this);
        btnShow.setText("👁 显示");
        Button btnOpenApply = new Button(this);
        btnOpenApply.setText("🔗 申请页");
        Button btnClear = new Button(this);
        btnClear.setText("🗑 清空");
        Button btnTutorial = new Button(this);
        btnTutorial.setText("📚 教程");
        Button btnTest = new Button(this);
        btnTest.setText("🧪 测试");
        for (Button b : new Button[]{btnShow, btnOpenApply, btnClear, btnTutorial, btnTest}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            tokenBtnRow.addView(b, lp);
        }
        content.addView(tokenBtnRow);

        // 显示/隐藏 Token
        btnShow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (etToken.getInputType() == (InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_CLASS_TEXT)) {
                    etToken.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    btnShow.setText("🙈 隐藏");
                } else {
                    etToken.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_CLASS_TEXT);
                    btnShow.setText("👁 显示");
                }
                etToken.setSelection(etToken.getText().length());
            }
        });
        // 打开申请页
        btnOpenApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/settings/tokens/new?scopes=repo,workflow&description=agent-toolbox")));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { etToken.setText(""); }
        });
        btnTutorial.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showTokenTutorial(); }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String token = etToken.getText().toString().trim();
                if (token.isEmpty()) { Toast.makeText(MainActivity.this, "请先填入 Token", Toast.LENGTH_SHORT).show(); return; }
                GithubConfigManager.setToken(MainActivity.this, token);
                Toast.makeText(MainActivity.this, "正在测试...", Toast.LENGTH_SHORT).show();
                new GithubApiClient(MainActivity.this).testToken(new GithubApiClient.Callback() {
                    @Override public void onSuccess(final String result) {
                        handler.post(new Runnable() { @Override public void run() {
                            Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show();
                            appendLog(result);
                        }});
                    }
                    @Override public void onError(final String error) {
                        handler.post(new Runnable() { @Override public void run() {
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                            appendLog(error);
                        }});
                    }
                });
            }
        });

        content.addView(mkSpacer(12));

        // API base
        TextView tvApiLabel = new TextView(this);
        tvApiLabel.setText("API Base（普通用户不用改，保持默认即可）");
        tvApiLabel.setTextSize(13);
        tvApiLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(tvApiLabel);
        final EditText etApiBase = new EditText(this);
        etApiBase.setHint(GithubConfigManager.DEFAULT_API_BASE);
        etApiBase.setText(GithubConfigManager.getApiBase(this));
        etApiBase.setSingleLine();
        etApiBase.setTypeface(android.graphics.Typeface.MONOSPACE);
        content.addView(etApiBase);

        content.addView(mkSpacer(8));

        // 当前仓库配置显示
        final TextView tvRepoInfo = new TextView(this);
        tvRepoInfo.setText("当前仓库: " + (GithubConfigManager.getFullRepo(this).isEmpty() ? "(未配置，点「我的仓库」选择)" : GithubConfigManager.getFullRepo(this)));
        tvRepoInfo.setTextSize(12);
        tvRepoInfo.setTextColor(0xFF6B7280);
        content.addView(tvRepoInfo);

        scroll.addView(content);
        new AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    String token = etToken.getText().toString().trim();
                    String apiBase = etApiBase.getText().toString().trim();
                    GithubConfigManager.setToken(MainActivity.this, token);
                    GithubConfigManager.setApiBase(MainActivity.this, apiBase);
                    appendLog("✅ GitHub 配置已保存 (Token: " + (token.isEmpty() ? "空" : "已设置(" + token.length() + "字符)") + ")");
                    Toast.makeText(MainActivity.this, "配置已保存", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** Token 申请教程对话框 */
    private void showTokenTutorial() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density + 0.5f);
        content.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("GitHub Token 申请教程（3 分钟搞定）");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(0xFF1E40AF);
        content.addView(tvTitle);
        content.addView(mkSpacer(12));

        content.addView(mkTutorialStep("①", "打开 GitHub Token 申请页",
            "点下方「🔗 打开申请页」按钮，会跳转到 GitHub 官网。\n需先登录 GitHub 账号（没有就注册一个，免费）。\n网址：github.com/settings/tokens/new"));
        content.addView(mkTutorialStep("②", "填写 Token 信息",
            "Note（备注）：随便填，如 agent-toolbox\nExpiration（有效期）：建议选 90 days 或 No expiration\n（过期了重新申请一个就行）"));

        TextView tvStep3Title = new TextView(this);
        tvStep3Title.setText("③ 选择权限（最重要的一步！）");
        tvStep3Title.setTextSize(15);
        tvStep3Title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvStep3Title.setTextColor(0xFFDC2626);
        content.addView(tvStep3Title);
        TextView tvStep3Body = new TextView(this);
        tvStep3Body.setText("在 Select scopes 列表中，必须勾选以下两项：\n\n"
            + "✅ repo（整个 repo 大项全勾上）\n   作用：读写仓库代码、触发编译、下载 APK\n   勾法：点 repo 前面的方框，子项会自动全选\n\n"
            + "✅ workflow\n   作用：触发 GitHub Actions 自动编译\n   位置：在 repo 下方，单独一个选项\n\n"
            + "❌ 其他权限不用勾，保持空白即可\n\n"
            + "提示：如果只勾了 repo 没勾 workflow，\n能读写代码但不能触发编译！");
        tvStep3Body.setTextSize(13);
        tvStep3Body.setTextColor(0xFF374151);
        tvStep3Body.setLineSpacing(0, 1.4f);
        tvStep3Body.setBackgroundColor(0xFFFEF3C7);
        tvStep3Body.setPadding(pad, pad, pad, pad);
        content.addView(tvStep3Body);
        content.addView(mkSpacer(8));

        content.addView(mkTutorialStep("④", "生成 Token",
            "拉到页面最底部，点绿色按钮「Generate token」\n生成后会显示一串 ghp_ 开头的字符\n\n⚠️ 注意：这串字符只显示一次！\n请立刻复制保存，关掉页面就看不到了。\n（忘了就重新申请一个）"));
        content.addView(mkTutorialStep("⑤", "粘贴到本应用",
            "复制 ghp_ 开头的字符串\n回到本应用，粘贴到 Token 输入框\n点「🧪 测试」验证是否可用\n点「保存」按钮保存配置"));

        content.addView(mkSpacer(16));
        Button btnOpen = new Button(this);
        btnOpen.setText("🔗 打开 GitHub Token 申请页");
        btnOpen.setTextColor(0xFFFFFFFF);
        try { btnOpen.setBackgroundResource(R.drawable.btn_primary); } catch (Exception ignore) {}
        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/settings/tokens/new?scopes=repo,workflow&description=agent-toolbox")));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                }
            }
        });
        content.addView(btnOpen);

        scroll.addView(content);
        new AlertDialog.Builder(this).setView(scroll).setPositiveButton("我知道了", null).show();
    }

    private LinearLayout mkTutorialStep(String num, String title, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int dp = (int)(getResources().getDisplayMetrics().density + 0.5f);
        TextView tvNum = new TextView(this);
        tvNum.setText(num);
        tvNum.setTextSize(20);
        tvNum.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvNum.setTextColor(0xFF3B82F6);
        tvNum.setPadding(0, 0, 12 * dp, 0);
        row.addView(tvNum);
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(0xFF111827);
        textCol.addView(tvTitle);
        TextView tvBody = new TextView(this);
        tvBody.setText(body);
        tvBody.setTextSize(13);
        tvBody.setTextColor(0xFF4B5563);
        tvBody.setLineSpacing(0, 1.4f);
        textCol.addView(tvBody);
        row.addView(textCol, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setPadding(0, 8 * dp, 0, 8 * dp);
        return row;
    }

    private View mkSpacer(int dp) {
        View v = new View(this);
        int px = (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px));
        return v;
    }

    // ============================================================
    // ========== 我的仓库（拉取列表 + 选择自动配置） ==========
    // ============================================================

    /** 我的仓库对话框：拉取仓库列表，点击自动配置 owner/repo */
    private void showMyReposDialog() {
        if (GithubConfigManager.getToken(this).isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("未配置 Token")
                .setMessage("请先点「GitHub 配置」填入 Token，才能拉取您的仓库列表。")
                .setPositiveButton("去配置", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) { showGithubConfigDialog(); }
                })
                .setNegativeButton("取消", null)
                .show();
            return;
        }
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在拉取您的仓库列表...");
        pd.setCancelable(false);
        pd.show();
        final String[] repoListHolder = new String[1];
        new GithubApiClient(this).listMyRepos(new GithubApiClient.Callback() {
            @Override public void onSuccess(final String result) {
                handler.post(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        repoListHolder[0] = result;
                        // 解析仓库列表，构建可点击的对话框
                        showRepoSelectionDialog(result);
                    }
                });
            }
            @Override public void onError(final String error) {
                handler.post(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        appendLog(error);
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("拉取失败")
                            .setMessage(error + "\n\n请检查 Token 是否正确（含 repo 权限）。")
                            .setPositiveButton("去配置", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) { showGithubConfigDialog(); }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    }
                });
            }
        });
    }

    /** 解析仓库列表文本，弹出选择对话框 */
    private void showRepoSelectionDialog(String reposText) {
        // reposText 格式：【1】owner/repo\n     desc\n     更新: xxx\n
        java.util.List<String[]> repoItems = new java.util.ArrayList<>();
        String[] lines = reposText.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("【") && line.indexOf("】") > 0) {
                int end = line.indexOf("】");
                String fullName = line.substring(end + 1).trim();
                if (fullName.contains("/")) {
                    String[] parts = fullName.split("/");
                    if (parts.length >= 2) repoItems.add(new String[]{fullName, parts[0], parts[1]});
                }
            }
        }
        if (repoItems.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("仓库列表")
                .setMessage(reposText + "\n\n（未解析到可点击的仓库，请手动在编译对话框输入 owner/repo）")
                .setPositiveButton("确定", null)
                .show();
            return;
        }
        String[] items = new String[repoItems.size()];
        for (int i = 0; i < repoItems.size(); i++) {
            items[i] = repoItems.get(i)[0];
        }
        new AlertDialog.Builder(this)
            .setTitle("选择仓库（自动配置 owner/repo）")
            .setMessage("共 " + items.length + " 个仓库，点击选择：")
            .setItems(items, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    String[] sel = repoItems.get(which);
                    GithubConfigManager.setOwner(MainActivity.this, sel[1]);
                    GithubConfigManager.setRepo(MainActivity.this, sel[2]);
                    appendLog("✅ 已自动配置仓库: " + sel[0]);
                    Toast.makeText(MainActivity.this, "已配置: " + sel[0], Toast.LENGTH_SHORT).show();
                    // 检查 workflow
                    checkRepoWorkflow(sel[1], sel[2]);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 检查选中仓库是否有 workflow */
    private void checkRepoWorkflow(String owner, String repo) {
        new GithubApiClient(this).checkWorkflow(owner, repo, new GithubApiClient.Callback() {
            @Override public void onSuccess(final String result) {
                handler.post(new Runnable() { @Override public void run() {
                    appendLog("📁 " + result);
                    Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show();
                }});
            }
            @Override public void onError(final String error) {
                handler.post(new Runnable() { @Override public void run() {
                    appendLog("⚠️ " + error);
                    Toast.makeText(MainActivity.this, "⚠️ 该仓库可能未配置 .github/workflows/build.yml", Toast.LENGTH_LONG).show();
                }});
            }
        });
    }

    // ============================================================
    // ========== 一键编译 APK ==========
    // ============================================================

    /** 一键编译对话框：触发编译 / 查进度 / 拿 APK / 看日志 */
    private void showCompileDialog() {
        if (!GithubConfigManager.isFullyConfigured(this)) {
            new AlertDialog.Builder(this)
                .setTitle("未完整配置")
                .setMessage("请先配置 GitHub Token 和仓库（owner/repo）。\n点「我的仓库」可自动选择仓库。")
                .setPositiveButton("去配置", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) { showGithubConfigDialog(); }
                })
                .setNeutralButton("我的仓库", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) { showMyReposDialog(); }
                })
                .setNegativeButton("取消", null)
                .show();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density + 0.5f);
        content.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🚀 一键编译 APK");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(0xFF1E40AF);
        content.addView(tvTitle);

        TextView tvInfo = new TextView(this);
        tvInfo.setText("仓库: " + GithubConfigManager.getFullRepo(this)
            + "\nWorkflow: " + GithubConfigManager.getWorkflowId(this)
            + "\n\n步骤：填 owner/repo（或点「我的仓库」选择）→ 点「触发编译」→ 等 1-2 分钟 → 点「查进度」→ 成功后点「拿 APK」");
        tvInfo.setTextSize(13);
        tvInfo.setLineSpacing(0, 1.4f);
        content.addView(tvInfo);
        content.addView(mkSpacer(8));

        // owner/repo 输入
        TextView tvRepoLabel = new TextView(this);
        tvRepoLabel.setText("owner/repo（如 myname/myrepo）");
        tvRepoLabel.setTextSize(13);
        content.addView(tvRepoLabel);
        final EditText etRepo = new EditText(this);
        etRepo.setHint("owner/repo");
        etRepo.setText(GithubConfigManager.getFullRepo(this));
        content.addView(etRepo);

        // 分支输入
        TextView tvBranchLabel = new TextView(this);
        tvBranchLabel.setText("分支（默认 main）");
        tvBranchLabel.setTextSize(13);
        content.addView(tvBranchLabel);
        final EditText etBranch = new EditText(this);
        etBranch.setHint("main");
        etBranch.setText("main");
        content.addView(etBranch);
        content.addView(mkSpacer(8));

        // 结果显示区
        final TextView tvResult = new TextView(this);
        tvResult.setText("（点击下方按钮开始）");
        tvResult.setTextSize(13);
        tvResult.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvResult.setTextColor(0xFF374151);
        tvResult.setBackgroundColor(0xFFF3F4F6);
        tvResult.setPadding(pad, pad, pad, pad);
        tvResult.setMinHeight(200);
        ScrollView resultScroll = new ScrollView(this);
        resultScroll.addView(tvResult);
        content.addView(resultScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        content.addView(mkSpacer(8));

        // 按钮区
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnTrigger = new Button(this); btnTrigger.setText("🚀 触发编译");
        Button btnStatus = new Button(this); btnStatus.setText("📊 查进度");
        Button btnApk = new Button(this); btnApk.setText("📦 拿 APK");
        Button btnLogs = new Button(this); btnLogs.setText("📋 看日志");
        for (Button b : new Button[]{btnTrigger, btnStatus, btnApk, btnLogs}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            btnRow.addView(b, lp);
        }
        content.addView(btnRow);

        final GithubApiClient client = new GithubApiClient(this);

        btnTrigger.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String repo = etRepo.getText().toString().trim();
                if (repo.contains("/")) GithubConfigManager.setFullRepo(MainActivity.this, repo);
                String branch = etBranch.getText().toString().trim();
                if (branch.isEmpty()) branch = "main";
                tvResult.setText("正在触发编译...");
                client.triggerWorkflow(branch, "", new GithubApiClient.Callback() {
                    @Override public void onSuccess(final String r) {
                        handler.post(new Runnable() { @Override public void run() {
                            tvResult.setText(r);
                            appendLog(r);
                        }});
                    }
                    @Override public void onError(final String e) {
                        handler.post(new Runnable() { @Override public void run() {
                            tvResult.setText(e);
                            appendLog(e);
                        }});
                    }
                });
            }
        });
        btnStatus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tvResult.setText("正在查询进度...");
                client.getLatestRunStatus(new GithubApiClient.Callback() {
                    @Override public void onSuccess(final String r) {
                        handler.post(new Runnable() { @Override public void run() {
                            tvResult.setText(r);
                            appendLog(r);
                        }});
                    }
                    @Override public void onError(final String e) {
                        handler.post(new Runnable() { @Override public void run() {
                            tvResult.setText(e);
                            appendLog(e);
                        }});
                    }
                });
            }
        });
        btnApk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tvResult.setText("正在获取 APK 下载链接...");
                client.getLatestArtifact(new GithubApiClient.Callback() {
                    @Override public void onSuccess(final String r) {
                        handler.post(new Runnable() { @Override public void run() {
                            tvResult.setText(r);
                            appendLog(r);
                            // 如果含下载链接，提供复制
                            if (r.contains("http")) {
                                copyFirstUrl(r);
                            }
                        }});
                    }
                    @Override public void onError(final String e) {
                        handler.post(new Runnable() { @Override public void run() {
                            tvResult.setText(e);
                            appendLog(e);
                        }});
                    }
                });
            }
        });
        btnLogs.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tvResult.setText("正在获取编译日志...");
                client.getLatestRunLogs(new GithubApiClient.Callback() {
                    @Override public void onSuccess(final String r) {
                        handler.post(new Runnable() { @Override public void run() {
                            tvResult.setText(r);
                            appendLog("📋 编译日志已获取");
                        }});
                    }
                    @Override public void onError(final String e) {
                        handler.post(new Runnable() { @Override public void run() {
                            tvResult.setText(e);
                            appendLog(e);
                        }});
                    }
                });
            }
        });

        scroll.addView(content);
        new AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show();
    }

    /** 从文本中提取第一个 URL 并复制到剪贴板 */
    private void copyFirstUrl(String text) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://\\S+");
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find()) {
                String url = m.group();
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("APK 下载链接", url));
                Toast.makeText(this, "APK 下载链接已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignore) {}
    }
    
    /**
     * 添加日志（新日志显示在最上面）
     * 内存安全：截断长消息、限制总字符数、批量更新 UI。
     */
    private void appendLog(String message) {
        // 截断超长消息（防止 JSBridge 传入 500KB+ JSON 撑爆内存）
        String displayMsg = message;
        if (displayMsg != null && displayMsg.length() > MAX_DISPLAY_MSG_LEN) {
            displayMsg = displayMsg.substring(0, MAX_DISPLAY_MSG_LEN)
                + "...[截断 " + (displayMsg.length() - MAX_DISPLAY_MSG_LEN) + " 字符]";
        }
        String newLog = "[" + getCurrentTime() + "] " + (displayMsg != null ? displayMsg : "");
        int newLen = newLog.length();

        // 将新日志插入到队列前面
        logDeque.addFirst(newLog);
        logTotalChars += newLen;

        // 限制总字符数（优先于条数限制）：从尾部移除直到低于上限
        while (logTotalChars > MAX_LOG_CHARS && logDeque.size() > 1) {
            String removed = logDeque.removeLast();
            logTotalChars -= removed.length();
        }
        // 兜底条数限制
        while (logDeque.size() > MAX_LOGS) {
            String removed = logDeque.removeLast();
            logTotalChars -= removed.length();
        }

        // 使用 Handler 节流 UI 更新：200ms 内的多次 appendLog 只刷新一次
        uiLogHandler.removeCallbacks(uiRefreshRunnable);
        uiLogHandler.postDelayed(uiRefreshRunnable, 200);
    }

    /** 批量刷新 UI 日志显示 */
    private final Runnable uiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            StringBuilder displayText = new StringBuilder(logTotalChars + logDeque.size());
            for (String log : logDeque) {
                displayText.append(log).append('\n');
            }
            tvLog.setText(displayText.toString());
            // 自动滚动到顶部
            ScrollView scrollView = (ScrollView) tvLog.getParent();
            scrollView.scrollTo(0, 0);
        }
    };
    
    /**
     * 获取当前时间字符串
     */
    private String getCurrentTime() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
        return sdf.format(new java.util.Date());
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }
    
}
