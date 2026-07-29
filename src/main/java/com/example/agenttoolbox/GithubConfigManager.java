package com.example.agenttoolbox;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * GitHub 配置管理器 - 存储 Token、仓库 owner/repo、API base
 * <p>
 * 所有配置保存在 SharedPreferences("github_config") 中。
 */
public class GithubConfigManager {

    private static final String PREFS_NAME = "github_config";
    private static final String KEY_TOKEN = "github_token";
    private static final String KEY_OWNER = "github_owner";
    private static final String KEY_REPO = "github_repo";
    private static final String KEY_API_BASE = "github_api_base";
    private static final String KEY_WORKFLOW_ID = "github_workflow_id";

    /** 默认 API base（普通用户不用改） */
    public static final String DEFAULT_API_BASE = "https://api.github.com";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 获取 Token（ghp_ 开头） */
    public static String getToken(Context ctx) {
        return prefs(ctx).getString(KEY_TOKEN, "");
    }

    public static void setToken(Context ctx, String token) {
        prefs(ctx).edit().putString(KEY_TOKEN, token == null ? "" : token.trim()).apply();
    }

    /** 获取仓库 owner */
    public static String getOwner(Context ctx) {
        return prefs(ctx).getString(KEY_OWNER, "");
    }

    public static void setOwner(Context ctx, String owner) {
        prefs(ctx).edit().putString(KEY_OWNER, owner == null ? "" : owner.trim()).apply();
    }

    /** 获取仓库名 */
    public static String getRepo(Context ctx) {
        return prefs(ctx).getString(KEY_REPO, "");
    }

    public static void setRepo(Context ctx, String repo) {
        prefs(ctx).edit().putString(KEY_REPO, repo == null ? "" : repo.trim()).apply();
    }

    /** 获取完整 owner/repo 字符串 */
    public static String getFullRepo(Context ctx) {
        String owner = getOwner(ctx);
        String repo = getRepo(ctx);
        if (owner.isEmpty() || repo.isEmpty()) return "";
        return owner + "/" + repo;
    }

    /** 从 owner/repo 字符串解析并保存 */
    public static void setFullRepo(Context ctx, String fullRepo) {
        if (fullRepo == null) return;
        String trimmed = fullRepo.trim();
        // 兼容用户输入完整 URL：https://github.com/owner/repo
        if (trimmed.startsWith("http")) {
            int idx = trimmed.indexOf("github.com/");
            if (idx > 0) trimmed = trimmed.substring(idx + 11);
        }
        trimmed = trimmed.replaceAll("/+$", ""); // 去掉末尾斜杠
        String[] parts = trimmed.split("/");
        if (parts.length >= 2) {
            setOwner(ctx, parts[0]);
            setRepo(ctx, parts[1]);
        }
    }

    /** 获取 API base（普通用户不用改，保持默认） */
    public static String getApiBase(Context ctx) {
        String base = prefs(ctx).getString(KEY_API_BASE, DEFAULT_API_BASE);
        return (base == null || base.isEmpty()) ? DEFAULT_API_BASE : base;
    }

    public static void setApiBase(Context ctx, String base) {
        prefs(ctx).edit().putString(KEY_API_BASE, base == null ? DEFAULT_API_BASE : base.trim()).apply();
    }

    /** 获取 workflow 文件名（默认 build.yml） */
    public static String getWorkflowId(Context ctx) {
        return prefs(ctx).getString(KEY_WORKFLOW_ID, "build.yml");
    }

    public static void setWorkflowId(Context ctx, String wfId) {
        prefs(ctx).edit().putString(KEY_WORKFLOW_ID, wfId == null ? "build.yml" : wfId.trim()).apply();
    }

    /** 是否已配置完整（Token + owner + repo 都非空） */
    public static boolean isFullyConfigured(Context ctx) {
        return !getToken(ctx).isEmpty() && !getOwner(ctx).isEmpty() && !getRepo(ctx).isEmpty();
    }

    /** Token 是否有效格式（ghp_ 开头，长度>=20） */
    public static boolean isTokenValidFormat(String token) {
        return token != null && token.length() >= 20
            && (token.startsWith("ghp_") || token.startsWith("github_pat_") || token.startsWith("gho_"));
    }
}
