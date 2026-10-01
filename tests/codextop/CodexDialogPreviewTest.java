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
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", temp.toString(),
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
                public static void main(String[] args) {
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
                    String mid = "请看 [文档](https://example.com/a)" + "字".repeat(1100);
                    check(CodexDialogPreview.readable(mid, "AttachPhoto") == mid, "超过 1024 字的完整链接仍被解析");
                    String open = "[".repeat(20000);
                    long openStarted = System.nanoTime();
                    CharSequence openOut = CodexDialogPreview.readable(open, "AttachPhoto");
                    long openMs = (System.nanoTime() - openStarted) / 1_000_000L;
                    check(openOut == open, "超长未闭合括号没有退回原对象");
                    check(openMs < 20, "超长未闭合括号仍在整段重扫: " + openMs + "ms");
                    String over = "请看 [文档](https://example.com/a)" + "字".repeat(4096);
                    check(CodexDialogPreview.readable(over, "AttachPhoto") == over, "超过上限后先截断再解析，链接目标泄漏");
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
                    System.out.println("bounded open=" + openMs + "ms spanned=" + hugeMs + "ms");
                    System.out.println("PASS CodexDialogPreviewTest");
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
