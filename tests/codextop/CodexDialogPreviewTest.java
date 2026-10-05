package com.butang.codextop;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 编译真实列表预览折叠，并用合成文本核对图片、链接、强调和代码；不读取真实会话。 */
public final class CodexDialogPreviewTest {
    /** 在临时目录执行格式化契约，换行复用平台替身。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        Path source = root.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexDialogPreview.java");
        if (!Files.isRegularFile(source)) throw new AssertionError("缺少 CodexDialogPreview");
        Path temp = Files.createTempDirectory("codex-dialog-preview-format-");
        try {
            Files.writeString(temp.resolve("AndroidUtilities.java"), """
                    package org.telegram.messenger;
                    public final class AndroidUtilities {
                        private AndroidUtilities() {}
                        /** 与原预览相同，只把换行收成空格；没有换行时返回原对象。 */
                        public static CharSequence replaceNewLines(CharSequence original) {
                            if (original == null || original.toString().indexOf('\\n') < 0) return original;
                            if (original instanceof android.text.SpannableStringBuilder) return original;
                            return original.toString().replace('\\n', ' ');
                        }
                    }
                    """);
            Files.writeString(temp.resolve("Spanned.java"), SPANNED);
            Files.writeString(temp.resolve("Spannable.java"), SPANNABLE);
            Files.writeString(temp.resolve("SpannableStringBuilder.java"), SPANNABLE_BUILDER);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", temp.toString(),
                    temp.resolve("Spanned.java").toString(),
                    temp.resolve("Spannable.java").toString(),
                    temp.resolve("SpannableStringBuilder.java").toString(),
                    temp.resolve("AndroidUtilities.java").toString(), source.toString()) != 0) {
                throw new AssertionError("预览折叠编译失败");
            }
            ArrayList<URL> classpath = new ArrayList<>();
            classpath.add(temp.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                classpath.add(Path.of(entry).toUri().toURL());
            }
            try (URLClassLoader loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                loader.loadClass("com.butang.codextop.CodexDialogPreviewCases").getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]);
            } catch (ClassNotFoundException missing) {
                Files.writeString(temp.resolve("CodexDialogPreviewCases.java"), CASES);
                if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", temp.toString(),
                        "-d", temp.toString(), temp.resolve("CodexDialogPreviewCases.java").toString()) != 0) {
                    throw new AssertionError("预览用例编译失败");
                }
                try (URLClassLoader loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                    try {
                        loader.loadClass("com.butang.codextop.CodexDialogPreviewCases").getMethod("main", String[].class)
                                .invoke(null, (Object) new String[0]);
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        throw new AssertionError("预览折叠回归失败", error.getCause());
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    private static final String SPANNED = """
            package android.text;
            public interface Spanned extends CharSequence {
                int getSpanStart(Object span);
                int getSpanEnd(Object span);
                int getSpanFlags(Object span);
                Object[] getSpans(int start, int end, Class type);
            }
            """;

    private static final String SPANNABLE = """
            package android.text;
            public interface Spannable extends Spanned {
                void setSpan(Object span, int start, int end, int flags);
                void removeSpan(Object span);
            }
            """;

    private static final String SPANNABLE_BUILDER = """
            package android.text;
            import java.util.ArrayList;
            public class SpannableStringBuilder implements Spannable {
                private final StringBuilder text;
                private final ArrayList<Mark> marks = new ArrayList<>();
                public SpannableStringBuilder(CharSequence value) { text = new StringBuilder(value.toString()); }
                public int length() { return text.length(); }
                public char charAt(int index) { return text.charAt(index); }
                public CharSequence subSequence(int start, int end) { return new SpannableStringBuilder(text.subSequence(start, end)); }
                public String toString() { return text.toString(); }
                public void setSpan(Object span, int start, int end, int flags) { marks.add(new Mark(span, start, end, flags)); }
                public void removeSpan(Object span) { marks.removeIf(mark -> mark.span == span); }
                public Object[] getSpans(int start, int end, Class type) {
                    ArrayList<Object> found = new ArrayList<>();
                    for (Mark mark : marks) if (mark.start < end && mark.end > start) found.add(mark.span);
                    return found.toArray();
                }
                public int getSpanStart(Object span) { for (Mark mark : marks) if (mark.span == span) return mark.start; return -1; }
                public int getSpanEnd(Object span) { for (Mark mark : marks) if (mark.span == span) return mark.end; return -1; }
                public int getSpanFlags(Object span) { for (Mark mark : marks) if (mark.span == span) return mark.flags; return 0; }
                static final class Mark {
                    final Object span; final int start, end, flags;
                    Mark(Object span, int start, int end, int flags) { this.span = span; this.start = start; this.end = end; this.flags = flags; }
                }
            }
            """;

    private static final String CASES = """
            package com.butang.codextop;
            public final class CodexDialogPreviewCases {
                static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
                static CharSequence show(String text) { return CodexDialogPreview.readable(text, "AttachPhoto"); }
                static void same(String text) {
                    check(show(text) == text, "不该改写: " + text);
                }
                /** 同时执行自动化摘要和已有 Markdown、附件、span 与扫描预算回归。 */
                public static void main(String[] args) {
                    automationPreviews();
                    check("请看 文档 这里".contentEquals(show("请看 [文档](https://example.com/a) 这里")), "链接没有留下标签");
                    check("请看 界面 这里".contentEquals(show("请看 ![界面](https://example.com/a.png) 这里")), "图片没有留下说明");
                    check("AttachPhoto".contentEquals(show("![](screenshot.png)")), "空图片说明没有使用原照片文案");
                    check("AttachPhoto".contentEquals(show("![](<screenshot.png>)")), "尖括号空图片说明没有使用原照片文案");
                    check("见 AttachPhoto 完".contentEquals(show("见 ![](a.png) 完")), "空图片说明没有留在原句中");
                    check("说明".contentEquals(show("[说明](<https://example.com/a b>)")), "尖括号链接目标没有留下标签");
                    check("条目".contentEquals(show("[条目](https://example.com/foo_(bar))")), "括号平衡的链接目标被截断");
                    check("一二".contentEquals(show("![一](a.png)![二](b.png)")), "相邻图片被合并成一次删除");
                    check("看 文档".contentEquals(show("**看 [文档](https://example.com)**")), "强调里的链接没有先留下标签");
                    same("[没有结尾](https://example.com");
                    same("[只有左括号");
                    same("前缀 ![坏](没完 后缀");
                    same("[带标题](https://example.com \\"t\\")");
                    same("\\\\*不是强调\\\\*");
                    same("\\\\[不是链接](https://example.com)");
                    same("\\\\![alt](https://example.com/a.png)");
                    check("这是加粗和斜体".contentEquals(show("这是**加粗**和*斜体*")), "成对强调没有去掉标记");
                    check("保留下划线文字".contentEquals(show("保留__下划线__文字")), "中文两侧的下划线强调没有去掉标记");
                    same("foo_bar_baz");
                    same("a*b*c");
                    check("使用 a *b* 代码".contentEquals(show("使用 `a *b*` 代码")), "代码里的强调被当成格式");
                    check("int x = 1;".contentEquals(show("```java\\nint x = 1;\\n```")), "代码围栏标记仍留在预览里");
                    check("第一行 第二".contentEquals(show("```\\n第一行\\n第二\\n```")), "多行代码围栏没有收成一行");
                    same("```\\nint x = 1;");
                    same("**没有结束");
                    same("https://example.com/docs/readme");
                    same("/synthetic/project/src/Main.java");
                    same("C:\\\\synthetic\\\\build\\\\app.apk");
                    same("<heartbeat>ping</heartbeat>");
                    check("<heartbeat>ping</heartbeat>\\n已完成".contentEquals(show("<heartbeat>ping</heartbeat>\\n已完成")), "心跳指令被删掉或换行被提前收起");
                    check("请看 图 😊".contentEquals(show("请看 ![图](https://example.com/a.png) 😊")), "中文或 emoji 被损坏");
                    Marked plain = new Marked("你好");
                    check(CodexDialogPreview.readable(plain, "AttachPhoto") == plain, "没有格式时没有保留原 CharSequence");
                    Marked emoji = new Marked("普通 😊");
                    check(CodexDialogPreview.readable(emoji, "AttachPhoto") == emoji, "emoji 预览被复制掉原 span");
                    same("第一行\\n**未闭合");
                    android.text.SpannableStringBuilder marked = new android.text.SpannableStringBuilder("看 [说明](https://example.com) 😊");
                    Object keep = new Object();
                    Object drop = new Object();
                    int url = marked.toString().indexOf("https");
                    int emojiAt = marked.toString().indexOf("😊");
                    marked.setSpan(keep, 0, 1, 0);
                    marked.setSpan(drop, url, url + 5, 0);
                    CharSequence shifted = CodexDialogPreview.readable(marked, "AttachPhoto");
                    check(shifted instanceof android.text.Spanned, "折叠后的显示副本丢掉了原 span");
                    check("看 说明 😊".contentEquals(shifted), "带 span 的链接没有留下标签和 emoji");
                    android.text.Spanned spans = (android.text.Spanned) shifted;
                    check(spans.getSpanStart(keep) == 0 && spans.getSpanEnd(keep) == 1, "未改动文字上的 span 没有留在原位置");
                    check(spans.getSpanStart(drop) < 0, "落在被删链接目标上的 span 被按旧偏移贴回");
                    int emojiStart = spans.getSpanStart(keep) == 0 ? shifted.toString().indexOf("😊") : -1;
                    Object emojiSpan = new Object();
                    marked.setSpan(emojiSpan, emojiAt, emojiAt + "😊".length(), 0);
                    CharSequence withEmojiSpan = CodexDialogPreview.readable(marked, "AttachPhoto");
                    android.text.Spanned emojiSpans = (android.text.Spanned) withEmojiSpan;
                    check(emojiSpans.getSpanStart(emojiSpan) == emojiStart, "emoji span 没有跟随保留文字移动");
                    String widePrefix = "甲".repeat(80);
                    android.text.SpannableStringBuilder wide = new android.text.SpannableStringBuilder(widePrefix + " [说明](https://example.com)");
                    Object wideSpan = new Object();
                    wide.setSpan(wideSpan, 0, widePrefix.length(), 33);
                    CharSequence wideOut = CodexDialogPreview.readable(wide, "AttachPhoto");
                    check(wideOut instanceof android.text.Spanned, "连续原文上的 span 被拆掉");
                    check((widePrefix + " 说明").contentEquals(wideOut), "连续原文里的链接没有留下标签");
                    android.text.Spanned wideSpans = (android.text.Spanned) wideOut;
                    check(wideSpans.getSpanStart(wideSpan) == 0 && wideSpans.getSpanEnd(wideSpan) == widePrefix.length(), "连续原文上的 span 没有整段留下");
                    check(wideSpans.getSpanFlags(wideSpan) == 33, "连续原文上的 span 标志被改掉");
                    String nested = "![".repeat(20) + "x" + "](u)".repeat(20);
                    long nestedStarted = System.nanoTime();
                    CharSequence nestedOut = CodexDialogPreview.readable(nested, "AttachPhoto");
                    long nestedMs = (System.nanoTime() - nestedStarted) / 1_000_000L;
                    check("x".contentEquals(nestedOut), "20 层图片没有留下最内层说明");
                    check(nestedMs < 20, "20 层图片把说明展开了两遍: " + nestedMs + "ms");
                    check("AttachPhoto".contentEquals(show("![   ](https://example.com/a.png)")), "空白图片说明没有使用原照片文案");
                    check(" a ".contentEquals(show("![ a ](https://example.com/a.png)")), "带字的图片说明被换成了照片文案");
                    String imageText = "前 ![图](https://example.com/a.png)";
                    android.text.SpannableStringBuilder imageMarked = new android.text.SpannableStringBuilder(imageText);
                    Object imageSpan = new Object();
                    int imageAt = imageText.indexOf("图");
                    imageMarked.setSpan(imageSpan, imageAt, imageAt + 1, 33);
                    CharSequence imageOut = CodexDialogPreview.readable(imageMarked, "AttachPhoto");
                    check("前 图".contentEquals(imageOut), "图片说明上的文字被丢掉");
                    check(imageOut instanceof android.text.Spanned, "图片说明上的 span 被丢掉");
                    android.text.Spanned imageSpans = (android.text.Spanned) imageOut;
                    check(imageSpans.getSpanStart(imageSpan) == imageOut.toString().indexOf("图")
                            && imageSpans.getSpanEnd(imageSpan) == imageSpans.getSpanStart(imageSpan) + 1
                            && imageSpans.getSpanFlags(imageSpan) == 33, "图片说明上的 span 没有留在新位置");
                    String blankImage = "![   ](https://example.com/a.png)";
                    android.text.SpannableStringBuilder blankMarked = new android.text.SpannableStringBuilder(blankImage);
                    Object blankSpan = new Object();
                    blankMarked.setSpan(blankSpan, 2, 5, 33);
                    CharSequence blankOut = CodexDialogPreview.readable(blankMarked, "AttachPhoto");
                    check("AttachPhoto".contentEquals(blankOut), "带 span 的空白图片说明没有使用原照片文案");
                    check(!(blankOut instanceof android.text.Spanned) || ((android.text.Spanned) blankOut).getSpanStart(blankSpan) < 0,
                            "空白图片说明上的 span 被贴到了照片文案");
                    String edge = "[".repeat(1024);
                    long edgeStarted = System.nanoTime();
                    CharSequence edgeOut = CodexDialogPreview.readable(edge, "AttachPhoto");
                    long edgeMs = (System.nanoTime() - edgeStarted) / 1_000_000L;
                    check(edgeOut == edge, "1024 个未闭合括号被改写");
                    check(edgeMs < 8, "1024 个未闭合括号仍超过界面线程预算: " + edgeMs + "ms");
                    String dense = "[a](u)".repeat(160);
                    long denseStarted = System.nanoTime();
                    CharSequence denseOut = CodexDialogPreview.readable(dense, "AttachPhoto");
                    long denseMs = (System.nanoTime() - denseStarted) / 1_000_000L;
                    check("a".repeat(160).contentEquals(denseOut), "上限内的密集链接没有留下标签");
                    check(denseMs < 8, "上限内的密集链接扫描过重: " + denseMs + "ms");
                    String bold = "**已完成**" + "字".repeat(1100);
                    CharSequence boldOut = CodexDialogPreview.readable(bold, "AttachPhoto");
                    check(boldOut != bold, "长文开头的成对加粗被原样退回");
                    check(boldOut.toString().startsWith("已完成"), "长文开头的成对加粗没有去掉标记");
                    check(!boldOut.toString().contains("**"), "长文加粗标记留在显示副本里");
                    String mid = "请看 [文档](https://example.com/a)" + "字".repeat(1100);
                    CharSequence midOut = CodexDialogPreview.readable(mid, "AttachPhoto");
                    check(midOut != mid, "前缀内已闭合的长链接被原样退回");
                    check(midOut.toString().startsWith("请看 文档"), "前缀内已闭合的长链接没有留下标签");
                    check(!midOut.toString().contains("https://"), "前缀内已闭合的长链接仍露出目标");
                    check(midOut.length() <= 1024, "长链接的显示副本带上了窗口之后的原文");
                    String late = "请看 [文档](https://example.com/" + "a".repeat(2000) + ")";
                    check(late.length() > 1024 && late.indexOf(')') > 1024, "后闭合用例没有把右括号放在扫描窗口外");
                    check(CodexDialogPreview.readable(late, "AttachPhoto") == late, "扫描边界之后才闭合的链接被折叠或换了对象");
                    int closeAt = 1023 - "请看 [文档](https://example.com/".length();
                    String inside = "请看 [文档](https://example.com/" + "a".repeat(closeAt) + ")" + "字".repeat(80);
                    check(inside.charAt(1023) == ')' && inside.length() > 1024, "1024 边界内的右括号位置不对");
                    CharSequence insideOut = CodexDialogPreview.readable(inside, "AttachPhoto");
                    check(insideOut != inside && insideOut.toString().startsWith("请看 文档")
                            && !insideOut.toString().contains("https://"), "恰好落在窗口内的链接没有折叠");
                    String edgeStars = "**" + "a".repeat(1020) + "**tail";
                    check(edgeStars.length() == 1028 && CodexDialogPreview.readable(edgeStars, "AttachPhoto") == edgeStars,
                            "窗口端部的强调闭合还要看后面的字符");
                    String edgeTicks = "`" + "a".repeat(1022) + "``tail";
                    check(edgeTicks.length() == 1029 && CodexDialogPreview.readable(edgeTicks, "AttachPhoto") == edgeTicks,
                            "窗口端部的反引号长度还要看后面的字符");
                    String edgeFence = "```java\\n" + "a".repeat(1012) + "\\n```x tail";
                    check(edgeFence.length() == 1030 && CodexDialogPreview.readable(edgeFence, "AttachPhoto") == edgeFence,
                            "窗口端部的围栏结束行还要看后面的字符");
                    String boundaryEmoji = new String(new char[] {(char) 0xD83D, (char) 0xDE0A});
                    String boldMark = "**好**";
                    String exact = boldMark + "字".repeat(1024 - boldMark.length() - boundaryEmoji.length()) + boundaryEmoji;
                    check(exact.length() == 1024, "整段 1024 的 emoji 用例长度不对");
                    check(Character.isHighSurrogate(exact.charAt(1022)) && Character.isLowSurrogate(exact.charAt(1023)),
                            "emoji 没有完整落在 1024 窗口末尾");
                    CharSequence exactOut = CodexDialogPreview.readable(exact, "AttachPhoto");
                    check(exactOut.toString().startsWith("好") && exactOut.toString().endsWith(boundaryEmoji)
                            && !exactOut.toString().contains("**"), "1024 末尾的 emoji 被拆开或加粗没有折叠");
                    check(paired(exactOut), "1024 窗口内的 emoji 显示副本有孤立代理项");
                    String straddling = boldMark + "字".repeat(1023 - boldMark.length()) + boundaryEmoji + "后";
                    check(straddling.charAt(1023) == (char) 0xD83D && straddling.charAt(1024) == (char) 0xDE0A,
                            "跨窗口的 emoji 没有落在 1023/1024");
                    CharSequence straddleOut = CodexDialogPreview.readable(straddling, "AttachPhoto");
                    check(straddleOut != straddling && straddleOut.toString().startsWith("好")
                            && straddleOut.toString().indexOf((char) 0xD83D) < 0, "跨边界的 emoji 被拆进扫描窗口");
                    check(paired(straddleOut), "跨边界 emoji 的显示副本有孤立代理项");
                    String open = "[".repeat(20000);
                    long openStarted = System.nanoTime();
                    CharSequence openOut = CodexDialogPreview.readable(open, "AttachPhoto");
                    long openMs = (System.nanoTime() - openStarted) / 1_000_000L;
                    check(openOut == open, "超长未闭合括号没有退回原对象");
                    check(openMs < 20, "超长未闭合括号仍在整段重扫: " + openMs + "ms");
                    String over = "请看 [文档](https://example.com/a)" + "字".repeat(4096);
                    CharSequence overOut = CodexDialogPreview.readable(over, "AttachPhoto");
                    check(overOut != over && overOut.toString().startsWith("请看 文档")
                            && !overOut.toString().contains("https://"), "更长正文里已经闭合的链接没有留下标签");
                    String huge = "前".repeat(100000) + " [文档](https://example.com/a)";
                    android.text.SpannableStringBuilder hugeMarked = new android.text.SpannableStringBuilder(huge);
                    Object hugeSpan = new Object();
                    hugeMarked.setSpan(hugeSpan, 0, 1, 33);
                    long hugeStarted = System.nanoTime();
                    CharSequence hugeOut = CodexDialogPreview.readable(hugeMarked, "AttachPhoto");
                    long hugeMs = (System.nanoTime() - hugeStarted) / 1_000_000L;
                    check(hugeOut == hugeMarked, "超长带链接的原文被改写成新对象");
                    check(hugeMarked.getSpanStart(hugeSpan) == 0 && hugeMarked.getSpanEnd(hugeSpan) == 1 && hugeMarked.getSpanFlags(hugeSpan) == 33, "超长回退改动了原 span");
                    check(hugeMs < 20, "超长带格式文字折叠不受限: " + hugeMs + "ms");
                    Object plainSpan = new Object();
                    Probe plainProbe = new Probe("前".repeat(100000), new Object[] {plainSpan}, new int[] {0}, new int[] {1}, new int[] {33});
                    long plainStarted = System.nanoTime();
                    CharSequence plainOut = CodexDialogPreview.readable(plainProbe, "AttachPhoto");
                    long plainMs = (System.nanoTime() - plainStarted) / 1_000_000L;
                    check(plainOut == plainProbe, "十万字纯文本被换成了新对象");
                    check(plainProbe.queries == 0, "没有改写时仍枚举了 span");
                    check(plainProbe.getSpanStart(plainSpan) == 0 && plainProbe.getSpanEnd(plainSpan) == 1 && plainProbe.getSpanFlags(plainSpan) == 33,
                            "纯文本退回时改动了原 span");
                    check(plainMs < 20, "十万字纯文本前缀扫描过重: " + plainMs + "ms");
                    String lead = "看 [说明](https://example.com) 尾";
                    String spannedText = lead + "字".repeat(2000);
                    Object early = new Object();
                    Object labelSpan = new Object();
                    Object urlSpan = new Object();
                    Object kept = new Object();
                    Object crossing = new Object();
                    Object outside = new Object();
                    int labelAt = lead.indexOf('说');
                    int urlAt = lead.indexOf("https");
                    int outsideCount = 20000;
                    Object[] spanObjs = new Object[6 + outsideCount];
                    int[] spanStarts = new int[spanObjs.length];
                    int[] spanEnds = new int[spanObjs.length];
                    int[] spanFlags = new int[spanObjs.length];
                    spanObjs[0] = early; spanStarts[0] = 0; spanEnds[0] = 1; spanFlags[0] = 33;
                    spanObjs[1] = labelSpan; spanStarts[1] = labelAt; spanEnds[1] = labelAt + 1; spanFlags[1] = 17;
                    spanObjs[2] = urlSpan; spanStarts[2] = urlAt; spanEnds[2] = urlAt + 5; spanFlags[2] = 9;
                    spanObjs[3] = kept; spanStarts[3] = 800; spanEnds[3] = 810; spanFlags[3] = 21;
                    spanObjs[4] = crossing; spanStarts[4] = 1000; spanEnds[4] = 1100; spanFlags[4] = 4;
                    spanObjs[5] = outside; spanStarts[5] = 1500; spanEnds[5] = 1510; spanFlags[5] = 6;
                    for (int i = 0; i < outsideCount; i++) {
                        spanObjs[6 + i] = new Object();
                        spanStarts[6 + i] = 3000 + i;
                        spanEnds[6 + i] = 3001 + i;
                        spanFlags[6 + i] = 1;
                    }
                    Probe rich = new Probe(spannedText, spanObjs, spanStarts, spanEnds, spanFlags);
                    long richStarted = System.nanoTime();
                    CharSequence richOut = CodexDialogPreview.readable(rich, "AttachPhoto");
                    long richMs = (System.nanoTime() - richStarted) / 1_000_000L;
                    check(richOut != rich && richOut instanceof android.text.Spanned, "长文链接的显示副本没有带走保留 span");
                    check(richOut.toString().startsWith("看 说明 尾") && !richOut.toString().contains("https://"),
                            "带 span 的长链接没有留下标签");
                    android.text.Spanned richSpans = (android.text.Spanned) richOut;
                    check(richSpans.getSpanStart(early) == 0 && richSpans.getSpanEnd(early) == 1 && richSpans.getSpanFlags(early) == 33,
                            "窗口内开头的 span 没有留在原位置");
                    check(richSpans.getSpanStart(labelSpan) == richOut.toString().indexOf('说') && richSpans.getSpanFlags(labelSpan) == 17,
                            "链接标签上的 span 没有跟着保留文字移动");
                    check(richSpans.getSpanStart(urlSpan) < 0, "链接目标上的 span 被贴进显示副本");
                    int removed = lead.length() - "看 说明 尾".length();
                    check(richSpans.getSpanStart(kept) == 800 - removed && richSpans.getSpanEnd(kept) == 810 - removed
                            && richSpans.getSpanFlags(kept) == 21, "窗口内后段 span 没有按删除长度平移");
                    check(richSpans.getSpanStart(crossing) < 0 && richSpans.getSpanStart(outside) < 0,
                            "越过窗口或落在窗口外的 span 被保留");
                    check(rich.queries > 0 && rich.maxQueryEnd <= 1024, "span 查询读过了扫描窗口: " + rich.maxQueryEnd);
                    check(richMs < 20, "窗口外的 span 数量拖慢了预览: " + richMs + "ms");
                    for (int i = 0; i < spanObjs.length; i++) {
                        if (rich.getSpanStart(spanObjs[i]) != spanStarts[i] || rich.getSpanEnd(spanObjs[i]) != spanEnds[i]
                                || rich.getSpanFlags(spanObjs[i]) != spanFlags[i]) {
                            check(false, "显示副本改写了原 span");
                        }
                    }
                    System.out.println("bounded open=" + openMs + "ms spanned=" + hugeMs + "ms plain=" + plainMs + "ms rich=" + richMs + "ms");
                    System.out.println("PASS CodexDialogPreviewTest");
                }
                /** 按桌面正式触发及响应模板构造合成信封，防止内部字段直接进入会话摘要。 */
                static void automationPreviews() {
                    String response = "<heartbeat>\\n  <automation_id>synthetic-monitor</automation_id>\\n"
                            + "  <decision>DONT_NOTIFY</decision>\\n  <message>暂无变化，继续检查。</message>\\n</heartbeat>";
                    check("暂无变化，继续检查。".contentEquals(show(response)), "自动化响应摘要泄漏内部标签");
                    String trigger = "<heartbeat>\\n  <automation_id>synthetic-monitor</automation_id>\\n"
                            + "  <current_time_iso>2026-10-06T00:00:00Z</current_time_iso>\\n"
                            + "  <instructions>\\n检查任务进展。\\n  </instructions>\\n</heartbeat>";
                    check("检查任务进展。".contentEquals(show(trigger)), "自动化触发摘要没有提取 instructions 正文");
                    check("暂无变化，继续检查。".contentEquals(show(" \\n" + response + "\\n ")),
                            "自动化信封外围空白影响识别");
                    check("已完成 图 😊".contentEquals(show(response.replace("DONT_NOTIFY", "NOTIFY")
                            .replace("暂无变化，继续检查。", "**已完成** ![图](shot.png) 😊"))),
                            "自动化正文没有沿用 Markdown、附件说明和 emoji 处理");
                    check("自动化任务".contentEquals(show(response.replace("暂无变化，继续检查。", " "))),
                            "纯协议响应仍泄漏字段或伪造任务完成状态");
                    check("自动化任务".contentEquals(show("<heartbeat><automation_id>synthetic-monitor</automation_id></heartbeat>")),
                            "仅有自动化身份的协议包仍泄漏字段");
                    check("自动化任务".contentEquals(show(response.replace("  <message>暂无变化，继续检查。</message>\\n", ""))),
                            "只有响应决定的协议包仍泄漏字段");
                    check("自动化任务".contentEquals(show(trigger.replace("  <instructions>\\n检查任务进展。\\n  </instructions>\\n", ""))),
                            "只有触发元数据的协议包仍泄漏字段");
                    check("保留 <item>正文</item>".contentEquals(show(response.replace("暂无变化，继续检查。",
                            "<![CDATA[保留 <item>正文</item>]]>"))), "响应 CDATA 被当作可见协议标签");
                    same("<item><automation_id>example</automation_id><message>正常 XML</message></item>");
                    same("引用：" + response);
                    same("\\\"" + response + "\\\"");
                    same("    " + response);
                    same("\\t" + response);
                    check(response.replace('\\n', ' ').contentEquals(show("```xml\\n" + response + "\\n```")),
                            "代码围栏里的 XML 示例被当作自动化信封删掉");
                    String longTrigger = trigger.replace("检查任务进展。", "**未闭合 " + "字".repeat(4000));
                    CharSequence longOut = show(longTrigger);
                    check(longOut.toString().startsWith("**未闭合 ") && longOut.length() <= 1024
                            && !longOut.toString().contains("automation_id") && !longOut.toString().contains("current_time_iso"),
                            "正文格式未闭合时回退到了原始自动化信封");
                    String longId = trigger.replace("synthetic-monitor", "a".repeat(4000));
                    Probe limited = new Probe(longId, new Object[0], new int[0], new int[0], new int[0]);
                    check("自动化任务".contentEquals(CodexDialogPreview.readable(limited, "AttachPhoto")),
                            "超长内部字段越过预算或泄漏到摘要");
                    // 逐字符移动窗口边界，覆盖字段头、字段尾、正文与外层结束标签被截断。
                    for (int padding = 800; padding < 1050; padding++) {
                        String boundary = response.replace("synthetic-monitor", "x".repeat(padding));
                        String output = show(boundary).toString();
                        check(output.indexOf('<') < 0 && !output.contains("x".repeat(30)) && output.length() <= 1024,
                                "自动化响应在边界 " + padding + " 回退或泄漏了协议: " + output.substring(0, Math.min(30, output.length())));
                        String triggerBoundary = trigger.replace("synthetic-monitor", "x".repeat(padding));
                        String triggerOutput = show(triggerBoundary).toString();
                        check(triggerOutput.indexOf('<') < 0 && !triggerOutput.contains("x".repeat(30)) && triggerOutput.length() <= 1024,
                                "自动化触发在边界 " + padding + " 回退或泄漏了协议");
                        String cdataBoundary = boundary.replace("暂无变化，继续检查。", "<![CDATA[暂无变化，继续检查。]]>");
                        String cdataOutput = show(cdataBoundary).toString();
                        check(cdataOutput.indexOf('<') < 0 && !cdataOutput.contains("CDATA") && !cdataOutput.contains("x".repeat(30)),
                                "CDATA 在边界 " + padding + " 回退或泄漏了协议");
                    }
                    String longCdata = response.replace("暂无变化，继续检查。", "<![CDATA[" + "字".repeat(1500) + "]]>");
                    check(show(longCdata).toString().startsWith("字") && show(longCdata).toString().indexOf('<') < 0,
                            "长 CDATA 正文仍显示内部包裹标签");
                    check("保留 </message> 标签".contentEquals(show(response.replace("暂无变化，继续检查。",
                            "<![CDATA[保留 </message> 标签]]>"))), "CDATA 内字面结束标签被误认成协议边界");
                    String longLiteral = response.replace("暂无变化，继续检查。", "<![CDATA[保留 </heartbeat> 标签" + "字".repeat(1500) + "]]>");
                    check(show(longLiteral).toString().startsWith("保留 </heartbeat> 标签")
                            && !show(longLiteral).toString().contains("automation_id"), "长正文内外层标签字面量导致整信封回退");
                    check("完成".contentEquals(show(response.replace("暂无变化，继续检查。", "**完成**") + " ".repeat(1200))),
                            "信封已完整时，窗口外的空白阻止了正文格式折叠");
                    android.text.SpannableStringBuilder marked = new android.text.SpannableStringBuilder(response);
                    Object bodySpan = new Object(), metadataSpan = new Object();
                    int bodyAt = response.indexOf("暂无变化");
                    marked.setSpan(bodySpan, bodyAt, bodyAt + 4, 33);
                    marked.setSpan(metadataSpan, response.indexOf("synthetic-monitor"), response.indexOf("synthetic-monitor") + 4, 17);
                    CharSequence projected = CodexDialogPreview.readable(marked, "AttachPhoto");
                    check(projected instanceof android.text.Spanned, "自动化正文丢失原 span");
                    android.text.Spanned spans = (android.text.Spanned) projected;
                    check(spans.getSpanStart(bodySpan) == 0 && spans.getSpanEnd(bodySpan) == 4 && spans.getSpanFlags(bodySpan) == 33
                            && spans.getSpanStart(metadataSpan) < 0, "自动化摘要的 span 没有按保留正文重映射");
                    check(marked.toString().equals(response) && marked.getSpanStart(bodySpan) == bodyAt,
                            "生成自动化摘要改写了原始消息或 span");
                    System.out.println("PASS automation preview: trigger, response, protocol-only, XML, bounded fallback, spans");
                }
                static boolean paired(CharSequence text) {
                    for (int index = 0; index < text.length(); index++) {
                        char value = text.charAt(index);
                        if (Character.isHighSurrogate(value)) {
                            if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) return false;
                            index++;
                        } else if (Character.isLowSurrogate(value)) return false;
                    }
                    return true;
                }
                static final class Probe implements CharSequence, android.text.Spanned {
                    final char[] data;
                    final Object[] spans;
                    final int[] starts, ends, flags;
                    int queries, maxQueryEnd;
                    Probe(String text, Object[] spans, int[] starts, int[] ends, int[] flags) {
                        data = text.toCharArray();
                        this.spans = spans;
                        this.starts = starts;
                        this.ends = ends;
                        this.flags = flags;
                    }
                    public int length() { return data.length; }
                    /** 原文探针只允许读取摘要窗口，超长协议和普通文本遵守同一边界。 */
                    public char charAt(int index) {
                        if (index >= 1024) throw new AssertionError("逐字读取越过扫描窗口: " + index);
                        return data[index];
                    }
                    public CharSequence subSequence(int start, int end) {
                        if (end - start > 1024) throw new AssertionError("读取了超过扫描窗口的原文");
                        return new String(data, start, end - start);
                    }
                    public String toString() { throw new AssertionError("把超长原文整段复制成字符串"); }
                    public Object[] getSpans(int start, int end, Class type) {
                        queries++;
                        if (end > maxQueryEnd) maxQueryEnd = end;
                        if (end > 1024) throw new AssertionError("span 查询越过扫描窗口: " + end);
                        java.util.ArrayList<Object> found = new java.util.ArrayList<>();
                        for (int index = 0; index < spans.length; index++) {
                            if (starts[index] < end && ends[index] > start) found.add(spans[index]);
                        }
                        return found.toArray();
                    }
                    public int getSpanStart(Object span) { return locate(span, true); }
                    public int getSpanEnd(Object span) { return locate(span, false); }
                    public int getSpanFlags(Object span) {
                        for (int index = 0; index < spans.length; index++) if (spans[index] == span) return flags[index];
                        return 0;
                    }
                    int locate(Object span, boolean start) {
                        for (int index = 0; index < spans.length; index++) {
                            if (spans[index] == span) return start ? starts[index] : ends[index];
                        }
                        return -1;
                    }
                }
                static final class Marked implements CharSequence {
                    final String text;
                    Marked(String text) { this.text = text; }
                    public int length() { return text.length(); }
                    public char charAt(int index) { return text.charAt(index); }
                    public CharSequence subSequence(int start, int end) { return text.subSequence(start, end); }
                    public String toString() { return text; }
                }
            }
            """;
}
