package com.butang.codextop;

import org.telegram.tgnet.TLRPC;

/** 使用合成消息执行真实显示转换，不访问账号、网络或 Android 界面。 */
public final class CodexMessageTextTest {
    public static void main(String[] args) {
        check(CodexMessageText.isSourceReference("[源码](/tmp/a.swift:50)", "/tmp/a.swift:50"), "普通源码行定位");
        check(CodexMessageText.isSourceReference("[源码](/tmp/a.swift:50:2)", "/tmp/a.swift:50:2"), "源码列定位");
        check(CodexMessageText.isSourceReference("[源码](/tmp/a.swift#L50-L60)", "/tmp/a.swift#L50-L60"), "普通路径片段原样");
        check(CodexMessageText.isSourceReference("[源码](file:///tmp/a%20b.swift#L50)", "/tmp/a b.swift"), "fileURL解码片段");
        check(CodexMessageText.isSourceReference("[源码](file://localhost/tmp/a%20b.swift:50)", "/tmp/a b.swift:50"), "本地file主机");
        check(CodexMessageText.isSourceReference("[源码](/tmp/a%20b.swift:50)", "/tmp/a%20b.swift:50"), "普通绝对路径不解码");
        check(!CodexMessageText.isSourceReference("[源码](/tmp/a%20b.swift:50)", "/tmp/a b.swift:50"), "百分号真实字符不得混同空格");
        check(!CodexMessageText.isSourceReference("![源码](/tmp/a.swift:50)", "/tmp/a.swift:50"), "图片保留");
        check(!CodexMessageText.isSourceReference("`[源码](/tmp/a.swift:50)`", "/tmp/a.swift:50"), "行内代码保留");
        check(!CodexMessageText.isSourceReference("```\n[源码](/tmp/a.swift:50)\n```", "/tmp/a.swift:50"), "代码块保留");
        check(!CodexMessageText.isSourceReference("[文件](/tmp/a.swift)", "/tmp/a.swift"), "无行号的真实文件保留");
        check(!CodexMessageText.isSourceReference("[网页](https://example.com/a.swift:50)", "/a.swift:50"), "网页不冒充本地");
        check(!CodexMessageText.isSourceReference("[源码](file://remote/tmp/a.swift:50)", "/tmp/a.swift:50"), "远端file主机不冒充本地");
        check(!CodexMessageText.isSourceReference("[源码](file:///tmp/a%2Fb.swift:50)", "/tmp/a/b.swift:50"), "编码路径分隔符无效");
        TLRPC.Message mixed = render("**本轮结果**\n\n> 已核对\n\n[页面源码](/Users/example/PrivacyAndSecurityController.swift:50)\n\n"
                + "| 项目 | 结果 |\n| --- | --- |\n| 字体 | **完整内容** |\n| 路径 | `a_b` |", false);
        equal("本轮结果\n\n已核对\n\n页面源码（/Users/example/PrivacyAndSecurityController.swift:50）\n\n"
                + "项目：字体\n结果：完整内容\n\n项目：路径\n结果：a_b", mixed.message);
        entity(mixed, TLRPC.TL_messageEntityBold.class, "本轮结果");
        entity(mixed, TLRPC.TL_messageEntityBlockquote.class, "已核对");
        entity(mixed, TLRPC.TL_messageEntityBold.class, "完整内容");
        entity(mixed, TLRPC.TL_messageEntityCode.class, "a_b");
        check(mixed.entities.stream().noneMatch(e -> e instanceof TLRPC.TL_messageEntityTextUrl), "本地路径不可作为URL执行");
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
        TLRPC.Message image = render("![image](https://example.com/x.png) **bold**", false);
        equal("图片：image（https://example.com/x.png） bold", image.message);
        entity(image, TLRPC.TL_messageEntityBold.class, "bold");
        TLRPC.Message unknownLink = render("[unsafe](javascript:alert) **bold**", false);
        equal("unsafe（javascript:alert） bold", unknownLink.message);
        entity(unknownLink, TLRPC.TL_messageEntityBold.class, "bold");
        check(unknownLink.entities.stream().noneMatch(e -> e instanceof TLRPC.TL_messageEntityTextUrl), "未知协议不可点");
        equal("列名：值", render("| 列名 |\n| --- |\n| 值 |", false).message);
        equal("甲：a|b\n乙：末尾", render("|甲|乙|\n|-|-|\n|a\\|b|末尾|", false).message);
        equal("甲\n乙", render("|甲|乙|\n|-|-|", false).message);
        TLRPC.Message extraCells = render("**保留**\n\n|甲|乙|\n|-|-|\n|一|二|额外完整内容|", false);
        equal("保留\n\n|甲|乙|\n|-|-|\n|一|二|额外完整内容|", extraCells.message);
        entity(extraCells, TLRPC.TL_messageEntityBold.class, "保留");
        for (String irregular : new String[]{"|甲|乙|\n|-|-|\n|-|-|-|\n|一|二|",
                "|甲|乙|\n|-|-|\n单格\n|一|二|额外完整内容|"}) {
            TLRPC.Message preserved = render("**保留**\n\n" + irregular, false);
            equal("保留\n\n" + irregular, preserved.message);
            entity(preserved, TLRPC.TL_messageEntityBold.class, "保留");
        }
        TLRPC.Message quotedTable = render("> |甲|乙|\n> |-|-|\n> |一|二|额外完整内容|", false);
        check(quotedTable.message.contains("额外完整内容"), "引用内异常表格不能丢格");
        entity(quotedTable, TLRPC.TL_messageEntityBlockquote.class, "|甲|乙|\n|-|-|\n|一|二|额外完整内容|");
        TLRPC.Message listedTable = render("- |甲|乙|\n  |-|-|\n  |一|二|额外完整内容|", false);
        check(listedTable.message.contains("额外完整内容"), "列表内异常表格不能丢格");
        for (String prefix : new String[]{"> ", ">> ", "- ", "* ", "+ ", "1. ", "2) ", "  ", "\t"}) {
            String indent = prefix.startsWith(">") ? prefix : "   ";
            TLRPC.Message container = render("**保留粗体**\n\n" + prefix + "|甲|乙|\n" + indent
                    + "|-|-|\n" + indent + "|一|二|额外完整内容|", false);
            check(container.message.contains("额外完整内容"), "容器内容完整：" + prefix);
            entity(container, TLRPC.TL_messageEntityBold.class, "保留粗体");
        }
        equal("甲：\n乙：", render("|甲|乙|\n|-|-|\n|||", false).message);
        equal("列 1：一\n乙：二", render("||乙|\n|-|-|\n|一|二|", false).message);
        String completeCell = "完整长内容".repeat(200);
        equal("列名：" + completeCell, render("|列名|\n|-|\n|" + completeCell + "|", false).message);
        unchanged("|" + "列名".repeat(2000) + "|\n|-|\n" + "|值|\n".repeat(100));
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
