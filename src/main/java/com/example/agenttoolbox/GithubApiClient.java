package com.example.agenttoolbox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub API 客户端 - 封装仓库列表/触发编译/查进度/拿APK/拿日志
 * <p>
 * 所有网络调用在后台线程执行，通过回调返回结果。
 * 必须先通过 GithubConfigManager 配置 Token。
 * <p>
 * 前提：仓库已配置 .github/workflows/build.yml，Token 含 repo + workflow 权限。
 */
public class GithubApiClient {

    private static final String TAG = "GithubApiClient";
    private static final ExecutorService ioPool = Executors.newFixedThreadPool(4);

    public interface Callback {
        void onSuccess(String result);
        void onError(String error);
    }

    private final Context ctx;

    public GithubApiClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private String apiBase() {
        return GithubConfigManager.getApiBase(ctx);
    }

    private String authHeader() {
        return "token " + GithubConfigManager.getToken(ctx);
    }

    /** 校验配置是否完整 */
    private String checkConfig() {
        if (GithubConfigManager.getToken(ctx).isEmpty()) return "未配置 GitHub Token";
        if (GithubConfigManager.getOwner(ctx).isEmpty() || GithubConfigManager.getRepo(ctx).isEmpty())
            return "未配置仓库 owner/repo";
        return null;
    }

    /** 统一 HTTP 请求 */
    private String http(String method, String urlStr, String body, String accept) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", authHeader());
            conn.setRequestProperty("Accept", accept != null ? accept : "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "agent-toolbox");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is);
            if (code >= 200 && code < 300) return resp;
            // 解析错误信息
            String errMsg = "HTTP " + code;
            try {
                JSONObject errJson = new JSONObject(resp);
                if (errJson.has("message")) errMsg += ": " + errJson.getString("message");
            } catch (Exception ignore) {}
            throw new RuntimeException(errMsg + (resp.length() < 500 ? "\n" + resp : ""));
        } finally {
            conn.disconnect();
        }
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        reader.close();
        return sb.toString();
    }

    /** 测试 Token 是否可用（GET /user） */
    public void testToken(final Callback cb) {
        ioPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String resp = http("GET", apiBase() + "/user", null, null);
                    JSONObject obj = new JSONObject(resp);
                    final String login = obj.optString("login", "?");
                    final String name = obj.optString("name", login);
                    // 检查权限范围
                    // 注意：GitHub 不直接返回 token scopes 在 /user，需要单独 HEAD 请求看 X-OAuth-Scopes 头
                    cb.onSuccess("✅ Token 有效！\n账号: " + name + " (" + login + ")");
                } catch (Exception e) {
                    cb.onError("❌ Token 测试失败: " + e.getMessage());
                }
            }
        });
    }

    /** 仓库信息结构 */
    public static class RepoInfo {
        public final String fullName;   // owner/repo
        public final String name;       // repo name
        public final String owner;
        public final String desc;
        public final boolean hasWorkflow;
        public final String updatedAt;
        public final String htmlUrl;
        public RepoInfo(String fullName, String name, String owner, String desc,
                        boolean hasWorkflow, String updatedAt, String htmlUrl) {
            this.fullName = fullName; this.name = name; this.owner = owner;
            this.desc = desc; this.hasWorkflow = hasWorkflow;
            this.updatedAt = updatedAt; this.htmlUrl = htmlUrl;
        }
    }

    /**
     * 拉取我的仓库列表（GET /user/repos，按更新时间倒序，最多 100 个）
     * 标记是否有 .github/workflows 目录（通过 /contents/.github/workflows 探测）
     */
    public void listMyRepos(final Callback cb) {
        ioPool.execute(new Runnable() {
            @Override
            public void run() {
                if (GithubConfigManager.getToken(ctx).isEmpty()) {
                    cb.onError("未配置 GitHub Token，请先在「GitHub 配置」里填入 Token");
                    return;
                }
                try {
                    String resp = http("GET", apiBase() + "/user/repos?per_page=100&sort=updated&type=all", null, null);
                    JSONArray arr = new JSONArray(resp);
                    StringBuilder sb = new StringBuilder();
                    sb.append("找到 ").append(arr.length()).append(" 个仓库：\n\n");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject r = arr.getJSONObject(i);
                        String fullName = r.optString("full_name", "");
                        String name = r.optString("name", "");
                        String owner = r.getJSONObject("owner").optString("login", "");
                        String desc = r.optString("description", "");
                        String updated = r.optString("updated_at", "").replace("T", " ").replace("Z", "");
                        sb.append("【").append(i + 1).append("】").append(fullName);
                        if (!desc.isEmpty() && !"null".equals(desc)) sb.append("\n     ").append(desc);
                        sb.append("\n     更新: ").append(updated).append("\n");
                    }
                    sb.append("\n点击对话框列表中的仓库可自动配置 owner/repo。");
                    cb.onSuccess(sb.toString());
                } catch (Exception e) {
                    cb.onError("拉取仓库列表失败: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 检测仓库是否配置了 workflow（GET /repos/{owner}/{repo}/contents/.github/workflows）
     * 返回该目录下的 workflow 文件名列表
     */
    public void checkWorkflow(final String owner, final String repo, final Callback cb) {
        ioPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String resp = http("GET",
                        apiBase() + "/repos/" + owner + "/" + repo + "/contents/.github/workflows", null, null);
                    JSONArray arr = new JSONArray(resp);
                    StringBuilder sb = new StringBuilder();
                    sb.append("找到 ").append(arr.length()).append(" 个 workflow 文件:\n");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject f = arr.getJSONObject(i);
                        sb.append("  - ").append(f.optString("name", "")).append("\n");
                    }
                    cb.onSuccess(sb.toString());
                } catch (Exception e) {
                    cb.onError("未找到 .github/workflows 目录，请先在仓库配置 build.yml\n(" + e.getMessage() + ")");
                }
            }
        });
    }

    /**
     * 触发 workflow_dispatch（POST /repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches）
     * @param ref 触发分支，默认 main
     * @param workflowId workflow 文件名，如 build.yml
     */
    public void triggerWorkflow(final String ref, final String workflowId, final Callback cb) {
        ioPool.execute(new Runnable() {
            @Override
            public void run() {
                String err = checkConfig();
                if (err != null) { cb.onError(err); return; }
                try {
                    String owner = GithubConfigManager.getOwner(ctx);
                    String repo = GithubConfigManager.getRepo(ctx);
                    String wf = (workflowId == null || workflowId.isEmpty())
                        ? GithubConfigManager.getWorkflowId(ctx) : workflowId;
                    String branch = (ref == null || ref.isEmpty()) ? "main" : ref;
                    String body = new JSONObject().put("ref", branch).toString();
                    String urlStr = apiBase() + "/repos/" + owner + "/" + repo
                        + "/actions/workflows/" + wf + "/dispatches";
                    http("POST", urlStr, body, null);
                    cb.onSuccess("✅ 已触发编译！\n仓库: " + owner + "/" + repo
                        + "\n分支: " + branch + "\nWorkflow: " + wf
                        + "\n\n请等待 1-2 分钟后点「查进度」");
                } catch (Exception e) {
                    cb.onError("触发编译失败: " + e.getMessage()
                        + "\n\n请确认：\n1. Token 含 repo + workflow 权限\n2. 仓库存在 .github/workflows/" +
                            (workflowId == null ? "build.yml" : workflowId));
                }
            }
        });
    }

    /**
     * 查询最近一次运行状态（GET /repos/{owner}/{repo}/actions/runs?per_page=1）
     * 返回 status / conclusion / html_url / run_number
     */
    public void getLatestRunStatus(final Callback cb) {
        ioPool.execute(new Runnable() {
            @Override
            public void run() {
                String err = checkConfig();
                if (err != null) { cb.onError(err); return; }
                try {
                    String owner = GithubConfigManager.getOwner(ctx);
                    String repo = GithubConfigManager.getRepo(ctx);
                    String resp = http("GET",
                        apiBase() + "/repos/" + owner + "/" + repo + "/actions/runs?per_page=1", null, null);
                    JSONObject obj = new JSONObject(resp);
                    int totalCount = obj.optInt("total_count", 0);
                    if (totalCount == 0) {
                        cb.onSuccess("该仓库还没有任何 Actions 运行记录。\n请先点「触发编译」。");
                        return;
                    }
                    JSONArray runs = obj.getJSONArray("workflow_runs");
                    JSONObject run = runs.getJSONObject(0);
                    String status = run.optString("status", "?");         // queued / in_progress / completed
                    String conclusion = run.optString("conclusion", "null"); // success / failure / cancelled / null
                    String displayTitle = run.optString("display_title", "");
                    String htmlUrl = run.optString("html_url", "");
                    String createdAt = run.optString("created_at", "").replace("T", " ").replace("Z", "");
                    long runId = run.optLong("id", 0);
                    String runNumber = String.valueOf(run.optInt("run_number", 0));
                    GithubConfigManager.setWorkflowId(ctx, GithubConfigManager.getWorkflowId(ctx)); // 缓存

                    StringBuilder sb = new StringBuilder();
                    sb.append("最近一次运行 #").append(runNumber).append("\n");
                    sb.append("状态: ").append(translateStatus(status, conclusion)).append("\n");
                    sb.append("触发: ").append(displayTitle).append("\n");
                    sb.append("开始: ").append(createdAt).append("\n");
                    sb.append("运行ID: ").append(runId).append("\n");
                    sb.append("详情: ").append(htmlUrl);
                    cb.onSuccess(sb.toString());
                } catch (Exception e) {
                    cb.onError("查询进度失败: " + e.getMessage());
                }
            }
        });
    }

    private String translateStatus(String status, String conclusion) {
        if ("queued".equals(status)) return "⏳ 排队中";
        if ("in_progress".equals(status)) return "🔄 编译中...";
        if ("completed".equals(status)) {
            if ("success".equals(conclusion)) return "✅ 编译成功";
            if ("failure".equals(conclusion)) return "❌ 编译失败";
            if ("cancelled".equals(conclusion)) return "🚫 已取消";
            return "✓ 已完成 (" + conclusion + ")";
        }
        return status + " / " + conclusion;
    }

    /** 判断是否编译成功 */
    public static boolean isBuildSuccess(String statusText) {
        return statusText != null && statusText.contains("✅ 编译成功");
    }

    /**
     * 获取最新运行的 APK 下载链接
     * 先查 artifacts 列表（GET /repos/{owner}/{repo}/actions/runs/{run_id}/artifacts）
     * 然后返回下载地址（需 Token 才能下载：archive_download_url）
     */
    public void getLatestArtifact(final Callback cb) {
        ioPool.execute(new Runnable() {
            @Override
            public void run() {
                String err = checkConfig();
                if (err != null) { cb.onError(err); return; }
                try {
                    String owner = GithubConfigManager.getOwner(ctx);
                    String repo = GithubConfigManager.getRepo(ctx);
                    // 先拿最新 run_id
                    String resp = http("GET",
                        apiBase() + "/repos/" + owner + "/" + repo + "/actions/runs?per_page=1", null, null);
                    JSONObject obj = new JSONObject(resp);
                    JSONArray runs = obj.getJSONArray("workflow_runs");
                    if (runs.length() == 0) {
                        cb.onError("还没有任何运行记录");
                        return;
                    }
                    JSONObject run = runs.getJSONObject(0);
                    String conclusion = run.optString("conclusion", "null");
                    if (!"success".equals(conclusion)) {
                        cb.onError("最近一次运行未成功 (" + conclusion + ")，无法获取 APK\n请先确认编译成功");
                        return;
                    }
                    long runId = run.optLong("id", 0);
                    // 查 artifacts
                    String artResp = http("GET",
                        apiBase() + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/artifacts", null, null);
                    JSONObject artObj = new JSONObject(artResp);
                    JSONArray arts = artObj.getJSONArray("artifacts");
                    if (arts.length() == 0) {
                        // 没有 artifact，尝试从 Release 获取
                        getLatestReleaseApk(cb);
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("找到 ").append(arts.length()).append(" 个构建产物:\n\n");
                    String token = GithubConfigManager.getToken(ctx);
                    for (int i = 0; i < arts.length(); i++) {
                        JSONObject a = arts.getJSONObject(i);
                        String name = a.optString("name", "");
                        long size = a.optLong("size_in_bytes", 0);
                        String archiveUrl = a.optString("archive_download_url", "");
                        // 加上 token 让 curl/wget 可直接下载
                        String dlUrl = archiveUrl + "?token=" + token;
                        sb.append("【").append(i + 1).append("】").append(name)
                          .append(" (").append(size / 1024).append(" KB)\n")
                          .append(dlUrl).append("\n\n");
                    }
                    sb.append("复制下载链接到浏览器即可下载（含 Token，有效期有限）。");
                    cb.onSuccess(sb.toString());
                } catch (Exception e) {
                    cb.onError("获取 APK 失败: " + e.getMessage());
                }
            }
        });
    }

    /** 从 Release 获取最新 APK（latest 预发布版） */
    private void getLatestReleaseApk(final Callback cb) {
        try {
            String owner = GithubConfigManager.getOwner(ctx);
            String repo = GithubConfigManager.getRepo(ctx);
            String resp = http("GET",
                apiBase() + "/repos/" + owner + "/" + repo + "/releases/latest", null, null);
            JSONObject rel = new JSONObject(resp);
            String tagName = rel.optString("tag_name", "");
            JSONArray assets = rel.getJSONArray("assets");
            if (assets.length() == 0) {
                cb.onError("Release " + tagName + " 没有 APK 附件");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Release: ").append(tagName).append("\n\n");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", "");
                long size = a.optLong("size", 0);
                sb.append("【").append(i + 1).append("】").append(name)
                  .append(" (").append(size / 1024).append(" KB)\n")
                  .append(url).append("\n\n");
            }
            sb.append("点击链接可直接下载（公开仓库无需 Token）。");
            cb.onSuccess(sb.toString());
        } catch (Exception e) {
            cb.onError("获取 Release 失败: " + e.getMessage());
        }
    }

    /**
     * 获取最新运行的编译日志（用于 AI 解析错误）
     * GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs（zip，返回下载 URL）
     * 或者 GET /repos/{owner}/{repo}/actions/jobs/{job_id}/logs（纯文本）
     */
    public void getLatestRunLogs(final Callback cb) {
        ioPool.execute(new Runnable() {
            @Override
            public void run() {
                String err = checkConfig();
                if (err != null) { cb.onError(err); return; }
                try {
                    String owner = GithubConfigManager.getOwner(ctx);
                    String repo = GithubConfigManager.getRepo(ctx);
                    // 拿最新 run_id
                    String resp = http("GET",
                        apiBase() + "/repos/" + owner + "/" + repo + "/actions/runs?per_page=1", null, null);
                    JSONObject obj = new JSONObject(resp);
                    JSONArray runs = obj.getJSONArray("workflow_runs");
                    if (runs.length() == 0) { cb.onError("没有运行记录"); return; }
                    JSONObject run = runs.getJSONObject(0);
                    long runId = run.optLong("id", 0);
                    String status = run.optString("status", "");
                    // 拿 jobs
                    String jobsResp = http("GET",
                        apiBase() + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/jobs", null, null);
                    JSONObject jobsObj = new JSONObject(jobsResp);
                    JSONArray jobs = jobsObj.getJSONArray("jobs");
                    if (jobs.length() == 0) { cb.onError("没有 job 记录"); return; }
                    JSONObject job = jobs.getJSONObject(0);
                    long jobId = job.optLong("id", 0);
                    // 拿 job 日志（纯文本）
                    String logUrl = apiBase() + "/repos/" + owner + "/" + repo + "/actions/jobs/" + jobId + "/logs";
                    String logs;
                    try {
                        logs = http("GET", logUrl, null, "text/plain");
                    } catch (Exception le) {
                        cb.onError("获取日志失败: " + le.getMessage());
                        return;
                    }
                    if (logs == null || logs.isEmpty()) {
                        cb.onError("日志为空（可能运行还未结束）");
                        return;
                    }
                    // 截断超长日志，保留最后部分（错误通常在末尾）
                    String result = logs;
                    if (result.length() > 8000) {
                        result = "...(日志过长，仅显示最后 8000 字符)\n" + result.substring(result.length() - 8000);
                    }
                    cb.onSuccess(result);
                } catch (Exception e) {
                    cb.onError("获取日志失败: " + e.getMessage());
                }
            }
        });
    }

    /** 关闭线程池 */
    public static void shutdown() {
        ioPool.shutdownNow();
    }
}
