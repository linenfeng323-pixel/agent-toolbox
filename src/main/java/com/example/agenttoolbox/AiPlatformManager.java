package com.example.agenttoolbox;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AI 平台管理器 - 支持多 AI 平台切换
 * <p>
 * 内置支持的平台：DeepSeek/ChatGPT/Claude/通义千问/Kimi/豆包/智谱清言/文心一言/Gemini/自定义
 * <p>
 * 用户选择的平台会作为 AI 助手页 WebView 的加载 URL，所有平台共享同一套 MCP 工具能力。
 */
public class AiPlatformManager {

    private static final String PREFS_NAME = "mcp_config";
    private static final String KEY_CURRENT_PLATFORM = "ai_platform_id";
    private static final String KEY_CUSTOM_NAME = "ai_custom_name";
    private static final String KEY_CUSTOM_URL = "ai_custom_url";

    /** 内置平台列表（id, 名称, URL, 图标 emoji, 描述） */
    private static final Platform[] BUILTIN_PLATFORMS = {
        new Platform("deepseek", "DeepSeek 深度求索", "https://chat.deepseek.com", "🤖", "国内可用，免费，代码能力强"),
        new Platform("chatgpt", "ChatGPT (OpenAI)", "https://chat.openai.com", "🌍", "需科学上网，GPT-4 最强"),
        new Platform("claude", "Claude (Anthropic)", "https://claude.ai", "🎭", "需科学上网，长文本能力强"),
        new Platform("qwen", "通义千问 (阿里)", "https://tongyi.aliyun.com", "🦄", "国内可用，免费，千问大模型"),
        new Platform("kimi", "Kimi (月之暗面)", "https://kimi.moonshot.cn", "🌙", "国内可用，免费，超长上下文"),
        new Platform("doubao", "豆包 (字节跳动)", "https://www.doubao.com", "🫘", "国内可用，免费，响应快"),
        new Platform("chatglm", "智谱清言 (智谱AI)", "https://chatglm.cn", "✨", "国内可用，免费，GLM-4"),
        new Platform("wenxin", "文心一言 (百度)", "https://yiyan.baidu.com", "🔥", "国内可用，免费，文心大模型"),
        new Platform("gemini", "Gemini (Google)", "https://gemini.google.com", "💎", "需科学上网，Google 最新模型"),
        new Platform("custom", "自定义平台", "", "⚙️", "填入任意 AI 网页版地址"),
    };

    /** 平台数据结构 */
    public static class Platform {
        public final String id;
        public final String name;
        public final String url;
        public final String icon;
        public final String desc;

        public Platform(String id, String name, String url, String icon, String desc) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.icon = icon;
            this.desc = desc;
        }
    }

    /** 获取所有内置平台 */
    public static Platform[] getBuiltinPlatforms() {
        return BUILTIN_PLATFORMS;
    }

    /** 根据 id 获取平台 */
    public static Platform getPlatform(String id) {
        if (id == null) return BUILTIN_PLATFORMS[0];
        for (Platform p : BUILTIN_PLATFORMS) {
            if (p.id.equals(id)) return p;
        }
        return BUILTIN_PLATFORMS[0]; // 默认 DeepSeek
    }

    /** 获取当前选中的平台 id */
    public static String getCurrentPlatformId(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CURRENT_PLATFORM, "deepseek");
    }

    /** 设置当前选中的平台 id */
    public static void setCurrentPlatformId(Context ctx, String id) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CURRENT_PLATFORM, id).apply();
    }

    /** 获取自定义平台名称 */
    public static String getCustomName(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CUSTOM_NAME, "");
    }

    /** 设置自定义平台名称 */
    public static void setCustomName(Context ctx, String name) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CUSTOM_NAME, name).apply();
    }

    /** 获取自定义平台 URL */
    public static String getCustomUrl(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CUSTOM_URL, "");
    }

    /** 设置自定义平台 URL */
    public static void setCustomUrl(Context ctx, String url) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CUSTOM_URL, url).apply();
    }

    /**
     * 获取当前平台应加载的 URL
     */
    public static String getCurrentUrl(Context ctx) {
        String platformId = getCurrentPlatformId(ctx);
        if ("custom".equals(platformId)) {
            String customUrl = getCustomUrl(ctx);
            if (customUrl != null && !customUrl.isEmpty()) return customUrl;
            return "https://chat.deepseek.com"; // 自定义为空时回退
        }
        Platform p = getPlatform(platformId);
        return p.url;
    }

    /** 获取当前平台显示名称 */
    public static String getCurrentName(Context ctx) {
        String platformId = getCurrentPlatformId(ctx);
        if ("custom".equals(platformId)) {
            String customName = getCustomName(ctx);
            if (customName != null && !customName.isEmpty()) return customName;
        }
        Platform p = getPlatform(platformId);
        return p.name;
    }

    /** 获取当前平台图标 */
    public static String getCurrentIcon(Context ctx) {
        String platformId = getCurrentPlatformId(ctx);
        Platform p = getPlatform(platformId);
        return p.icon;
    }
}
