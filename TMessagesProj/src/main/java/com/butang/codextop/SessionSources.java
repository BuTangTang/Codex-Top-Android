package com.butang.codextop;

/**
 * D-83 三期 A：会话来源。Codex 会话保留原远端编号作身份；Claude Code 会话在远端编号前加来源前缀，
 * 同一台电脑上与 Codex 同编号也不会共用本地编号、历史或草稿。不依赖 Android 与 gson，可直接测试。
 */
public final class SessionSources {
    public static final String CODEX = "codex";
    public static final String CLAUDE = "claude";
    private static final String CLAUDE_PREFIX = "claude:";

    /** Claude Code 状态接通前的说明；只出现在聊天页状态行与读屏描述。 */
    public static final String CLAUDE_STATUS_UNSUPPORTED = "状态未知 · Claude Code 状态暂未接入";
    /** 只读聊天页底部说明，三期 B 发送接通后再撤掉。 */
    public static final String CLAUDE_READ_ONLY = "Claude Code 会话请在电脑上回复";

    private SessionSources() { }

    /** 手机内部使用的远端身份；Codex 原样返回。 */
    public static String localRemote(String provider, String remoteSessionId) {
        return CLAUDE.equals(provider) ? CLAUDE_PREFIX + remoteSessionId : remoteSessionId;
    }

    public static boolean isClaude(String localRemote) {
        return localRemote != null && localRemote.startsWith(CLAUDE_PREFIX)
                && localRemote.length() > CLAUDE_PREFIX.length();
    }

    /** 请求电脑时填的 providerId。 */
    public static String provider(String localRemote) {
        return isClaude(localRemote) ? CLAUDE : CODEX;
    }

    /** 请求电脑时填的原远端编号。 */
    public static String nativeRemote(String localRemote) {
        return isClaude(localRemote) ? localRemote.substring(CLAUDE_PREFIX.length()) : localRemote;
    }

    /** 两种来源并存时第二行最前的来源标记；只有一种来源时不加。 */
    public static String mark(String localRemote) {
        return isClaude(localRemote) ? "CC" : "CX";
    }
}
