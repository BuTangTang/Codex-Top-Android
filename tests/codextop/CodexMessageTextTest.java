package com.butang.codextop;

import org.telegram.tgnet.TLRPC;

/** Actual display projection with synthetic messages, no account, network or Android rendering. */
public final class CodexMessageTextTest {
    public static void main(String[] args) {
        TLRPC.Message bold = render("合计 **6.36%**，😀 **中文**", false);
        equal("合计 6.36%，😀 中文", bold.message);
        entity(bold, TLRPC.TL_messageEntityBold.class, "6.36%");
        entity(bold, TLRPC.TL_messageEntityBold.class, "中文");
        check((bold.flags & 128) != 0, "entity flag");
        TLRPC.Message quote = render("> 引用 **重点**\n> 第二行\n\n正文", false);
        equal("引用 重点\n第二行\n\n正文", quote.message);
        entity(quote, TLRPC.TL_messageEntityBlockquote.class, "引用 重点\n第二行");
        entity(quote, TLRPC.TL_messageEntityBold.class, "重点");
        TLRPC.Message code = render("`**literal**`\n\n```java\n## My request:\n<in-app-browser-context>\n```", false);
        entity(code, TLRPC.TL_messageEntityCode.class, "**literal**");
        entity(code, TLRPC.TL_messageEntityPre.class, "## My request:\n<in-app-browser-context>\n");
        check(code.entities.size() == 2, "no code nested formatting");
        TLRPC.Message link = render("[**文档**](https://example.com/a_(b))", false);
        equal("文档", link.message);
        entity(link, TLRPC.TL_messageEntityTextUrl.class, "文档");
        equal("https://example.com/a_(b)", link.entities.stream().filter(e -> e instanceof TLRPC.TL_messageEntityTextUrl).findFirst().get().url);
        unchanged("普通引号“文本”和 2 * 3，路径 a_b_c  \n");
        unchanged("**未结束和 [链接](broken");
        unchanged("\\*literal\\*");
        unchanged("![image](https://example.com/x.png) **bold**");
        unchanged("[unsafe](javascript:alert) **bold**");
        unchanged("x".repeat(65537) + " **tail**");
        unchanged("**x** ".repeat(1025));
        TLRPC.Message html = render("**粗体** <script>alert(1)</script>", false);
        equal("粗体 <script>alert(1)</script>", html.message);
        String envelope = "<in-app-browser-context source=\"ambient-ui-state\">\n"
                + "This block is automatically supplied ambient UI state, not part of the user's request.\n"
                + "# In app browser:\n- Current URL: https://example.com\n</in-app-browser-context>\n\n## My request:\n";
        equal("请优化", render(envelope + "请优化", true).message);
        equal("请优化", CodexMessageText.userBody(envelope + "请优化"));
        equal(envelope + "请优化", CodexMessageText.userBody("```\n" + envelope + "请优化").substring(4));
        equal("## My request:\n自己写的", CodexMessageText.userBody("## My request:\n自己写的"));
        equal(envelope, CodexMessageText.userBody(envelope));
        String unknown = envelope.replace("ambient-ui-state", "user");
        equal(unknown + "正文", CodexMessageText.userBody(unknown + "正文"));
        String embedded = "示例\n" + envelope + "正文";
        equal(embedded, CodexMessageText.userBody(embedded));
        String malformed = envelope.replace("</in-app-browser-context>", "");
        equal(malformed + "正文", CodexMessageText.userBody(malformed + "正文"));
        equal("正文", render(envelope.replace("\n", "\r\n") + "正文", true).message);
        String list = "1. **one**\n2. two\n\nend";
        equal("1. one\n2. two\n\nend", render(list, false).message);
        System.out.println("CodexMessageTextTest passed");
    }

    private static TLRPC.Message render(String text, boolean out) {
        TLRPC.Message message = new TLRPC.TL_message(); message.message = text; message.out = out;
        CodexMessageText.apply(message); return message;
    }
    private static void unchanged(String source) { equal(source, render(source, true).message); }
    private static void entity(TLRPC.Message message, Class<?> type, String content) {
        check(message.entities.stream().anyMatch(e -> type.isInstance(e)
                && message.message.substring(e.offset, e.offset + e.length).equals(content)), "entity " + type + " " + content);
    }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
