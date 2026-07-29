package com.example.agenttoolbox.tools;

import android.content.Context;

import com.example.agenttoolbox.AppLogger;
import com.example.agenttoolbox.GithubApiClient;
import com.example.agenttoolbox.GithubConfigManager;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GitHub 编译工具 - 让 AI 能在对话中触发 GitHub Actions 云端编译
 * <p>
 * 这是「循环修复 bug 直到编译成功」的核心能力：
 * AI 调用本工具触发编译 → 等待 → 查日志 → 解析编译错误 → 用 file_write 改代码 → git push → 再触发编译
 * <p>
 * 支持 action：
 *   - trigger_build  : 触发 workflow_dispatch（默认 build.yml，分支 main）
 *   - check_status   : 查询最近一次运行状态（queued/in_progress/success/failure）
 *   - get_logs       : 获取最近一次运行的编译日志（用于解析错误）
 *   - get_apk_url    : 编译成功后获取 APK 下载链接
 *   - wait_complete   : 阻塞等待最近一次运行结束（最多 10 分钟），返回最终状态
 *   - full_pipeline   : 一键完整流程：触发→等待→若失败返回日志，若成功返回 APK 链接
 */
public class GithubCompileTool implements Tool {

    private final Context context;

    public GithubCompileTool(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    @Override
    public String getName() {
        return "github_compile";
    }

    @Override
    public String getDescription() {
        return "GitHub Actions 云端编译工具。能在对话中触发 GitHub Actions 编译 APK、"
            + "查询编译进度、获取编译日志、获取 APK 下载链接。"
            + "用于实现「自动编译→查日志→修 bug→再编译」的循环，直到编译成功。"
            + "前提：已配置 GitHub Token（含 repo+workflow 权限）和仓库 owner/repo。";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            JSONObject properties = new JSONObject();

            JSONObject action = new JSONObject();
            action.put("type", "string");
            action.put("description", "操作类型：trigger_build(触发编译) / check_status(查进度) / "
                + "get_logs(拿编译日志) / get_apk_url(拿APK下载链接) / wait_complete(等待编译结束) / "
                + "full_pipeline(一键完整流程)");
            action.put("enum", new String[]{
                "trigger_build", "check_status", "get_logs", "get_apk_url",
                "wait_complete", "full_pipeline"
            });
            properties.put("action", action);

            JSONObject ref = new JSONObject();
            ref.put("type", "string");
            ref.put("description", "触发编译的分支，默认 main（仅 trigger_build/full_pipeline 用）");
            properties.put("ref", ref);

            JSONObject workflowId = new JSONObject();
            workflowId.put("type", "string");
            workflowId.put("description", "workflow 文件名，默认 build.yml（仅 trigger_build/full_pipeline 用）");
            properties.put("workflow_id", workflowId);

            JSONObject waitSec = new JSONObject();
            waitSec.put("type", "integer");
            waitSec.put("description", "wait_complete/full_pipeline 等待超时秒数，默认 600（10分钟）");
            properties.put("wait_seconds", waitSec);

            schema.put("properties", properties);
            schema.put("required", new String[]{"action"});
        } catch (Exception e) {
            AppLogger.e("GithubCompileTool", "build schema failed", e);
        }
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String action = arguments.optString("action", "");
        String ref = arguments.optString("ref", "main");
        String workflowId = arguments.optString("workflow_id", "");
        int waitSec = arguments.optInt("wait_seconds", 600);

        if (!GithubConfigManager.isFullyConfigured(context)) {
            return "❌ 未完整配置 GitHub Token / 仓库 owner/repo，请先在主页「GitHub 配置」里填写。\n"
                + "当前 owner=" + GithubConfigManager.getOwner(context)
                + " repo=" + GithubConfigManager.getRepo(context)
                + " token=" + (GithubConfigManager.getToken(context).isEmpty() ? "空" : "已设置");
        }

        GithubApiClient client = new GithubApiClient(context);

        if ("trigger_build".equals(action)) {
            return blockingCall(client, new TriggerAction(client, ref, workflowId));
        } else if ("check_status".equals(action)) {
            return blockingCall(client, new StatusAction(client));
        } else if ("get_logs".equals(action)) {
            return blockingCall(client, new LogsAction(client));
        } else if ("get_apk_url".equals(action)) {
            return blockingCall(client, new ApkAction(client));
        } else if ("wait_complete".equals(action)) {
            return blockingWaitComplete(client, waitSec);
        } else if ("full_pipeline".equals(action)) {
            return blockingFullPipeline(client, ref, workflowId, waitSec);
        }
        return "❌ 未知 action: " + action + "\n支持: trigger_build / check_status / get_logs / get_apk_url / wait_complete / full_pipeline";
    }

    // ====== 同步包装：把异步回调转成阻塞返回 ======
    private interface ActionCall {
        void execute(GithubApiClient client, GithubApiClient.Callback cb);
    }

    private String blockingCall(GithubApiClient client, ActionCall action) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<>();
        final AtomicReference<String> errRef = new AtomicReference<>();
        action.execute(client, new GithubApiClient.Callback() {
            @Override
            public void onSuccess(String result) { resultRef.set(result); latch.countDown(); }
            @Override
            public void onError(String error) { errRef.set(error); latch.countDown(); }
        });
        try {
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                return "❌ 操作超时（60秒）";
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "❌ 操作被中断";
        }
        return errRef.get() != null ? errRef.get() : resultRef.get();
    }

    // ====== 各 action 实现 ======
    private static class TriggerAction implements ActionCall {
        final GithubApiClient client; final String ref; final String wf;
        TriggerAction(GithubApiClient c, String r, String w) { client=c; ref=r; wf=w; }
        @Override public void execute(GithubApiClient c, GithubApiClient.Callback cb) {
            client.triggerWorkflow(ref, wf, cb);
        }
    }
    private static class StatusAction implements ActionCall {
        final GithubApiClient client;
        StatusAction(GithubApiClient c) { client=c; }
        @Override public void execute(GithubApiClient c, GithubApiClient.Callback cb) {
            client.getLatestRunStatus(cb);
        }
    }
    private static class LogsAction implements ActionCall {
        final GithubApiClient client;
        LogsAction(GithubApiClient c) { client=c; }
        @Override public void execute(GithubApiClient c, GithubApiClient.Callback cb) {
            client.getLatestRunLogs(cb);
        }
    }
    private static class ApkAction implements ActionCall {
        final GithubApiClient client;
        ApkAction(GithubApiClient c) { client=c; }
        @Override public void execute(GithubApiClient c, GithubApiClient.Callback cb) {
            client.getLatestArtifact(cb);
        }
    }

    /**
     * 阻塞等待最近一次运行结束（轮询，每 15 秒一次，最多 waitSeconds 秒）
     */
    private String blockingWaitComplete(GithubApiClient client, int waitSeconds) {
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        int poll = 0;
        while (System.currentTimeMillis() < deadline) {
            poll++;
            String status = blockingCall(client, new StatusAction(client));
            if (status == null) status = "";
            // 完成的状态会带 ✅编译成功 / ❌编译失败 / 🚫已取消
            if (status.contains("✅ 编译成功") || status.contains("❌ 编译失败")
                || status.contains("🚫 已取消") || status.contains("✓ 已完成")) {
                return "等待结束（轮询 " + poll + " 次）:\n" + status;
            }
            // 还在运行中，继续等
            try { Thread.sleep(15000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "❌ 等待被中断（已轮询 " + poll + " 次）:\n" + status;
            }
        }
        return "❌ 等待超时（" + waitSeconds + " 秒，轮询 " + poll + " 次），编译仍未结束。\n"
            + "可稍后用 check_status 再查。";
    }

    /**
     * 一键完整流程：触发 → 等待编译结束 → 若失败返回日志，若成功返回 APK 链接
     * 这是 AI 实现「自动编译修复 bug」最方便的入口。
     */
    private String blockingFullPipeline(GithubApiClient client, String ref, String wf, int waitSeconds) {
        StringBuilder log = new StringBuilder();
        // 1. 触发
        log.append("【1/3 触发编译】\n");
        String triggerResult = blockingCall(client, new TriggerAction(client, ref, wf));
        log.append(triggerResult).append("\n\n");
        if (triggerResult == null || !triggerResult.contains("✅ 已触发编译")) {
            return log.toString() + "❌ 触发失败，流程终止。";
        }
        // 2. 等待 60 秒让 GitHub 创建 run（dispatch 后不会立即有 run）
        log.append("【2/3 等待编译】\n等待 30 秒让 GitHub 创建运行...\n");
        try { Thread.sleep(30000); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return log.toString() + "❌ 等待被中断";
        }
        // 3. 轮询等待结束
        String waitResult = blockingWaitComplete(client, waitSeconds);
        log.append(waitResult).append("\n\n");
        // 4. 根据结果返回日志或 APK
        if (waitResult != null && waitResult.contains("✅ 编译成功")) {
            log.append("【3/3 获取 APK】\n");
            String apk = blockingCall(client, new ApkAction(client));
            log.append(apk);
            return log.toString();
        } else {
            log.append("【3/3 获取编译日志】\n编译失败，获取日志供分析:\n");
            String logs = blockingCall(client, new LogsAction(client));
            log.append(logs);
            log.append("\n\n💡 请分析上述日志中的编译错误，用 file_write 修改源码后，"
                + "用 shell 工具执行 git add/commit/push，然后再次调用 github_compile(full_pipeline) 重新编译。");
            return log.toString();
        }
    }
}
