package com.butang.codextop;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;

/** 合成额度、电脑卡片和底栏字形。替身只表达换行与字号比例，不代表 Android 字体像素。 */
public final class SettingsOverviewLayoutTest {
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        Path source = root.resolve("TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java");
        var unit = StaticJavaParser.parse(source);
        var activity = unit.getClassByName("SettingsActivity").orElseThrow();
        String fill = activity.getMethodsByName("fillCodexUsageItems").get(0).toString()
                + activity.getMethodsByName("codexUsageItem").get(0);
        String items = activity.getMethodsByName("fillItems").get(0).toString();
        String click = activity.getMethodsByName("onClick").get(0).toString();
        if (!fill.contains("UsageCell.Factory.of") || !fill.contains("ofCodex(25,") || !fill.contains("ofCodex(33,"))
            throw new AssertionError("额度摘要或原来源/刷新入口丢失");
        if (!items.contains("ComputerCell.Factory.of") || !items.contains("AccountMetaCell.Factory.of") || !items.contains("SettingCell.Factory.ofBrowse"))
            throw new AssertionError("电脑卡片、紧凑资料或原项目会话行丢失");
        if (!click.contains("item.id == 1000") || !click.contains("codexMachine"))
            throw new AssertionError("电脑行不再走原来的项目路由");
        String setting = activity.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals("SettingCell")).findFirst().orElseThrow().toString();
        if (!setting.contains("twoLines ? 60 : 50")) throw new AssertionError("原来源行的最小高度被改掉");
        for (String name : List.of("UsageCell", "ComputerCell", "AccountMetaCell")) {
            String body = activity.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(type -> type.getNameAsString().equals(name)).findFirst().orElseThrow().toString();
            if (body.contains("setOnClickListener")) throw new AssertionError(name + " 新增了点击");
        }
        glyphs(root.resolve("TMessagesProj/src/main/res/drawable"));
        Path temp = Files.createTempDirectory("codex-overview-layout-");
        try {
            StringBuilder cells = new StringBuilder();
            for (String name : List.of("AccountMetaCell", "UsageCell", "ComputerCell")) {
                cells.append(activity.findAll(ClassOrInterfaceDeclaration.class).stream()
                        .filter(type -> type.getNameAsString().equals(name)).findFirst().orElseThrow());
            }
            writeStubs(temp);
            Files.writeString(temp.resolve("org/telegram/ui/SettingsActivity.java"), wrapper(cells.toString()));
            Files.writeString(temp.resolve("org/telegram/ui/SettingsOverviewProbe.java"), PROBE);
            ArrayList<String> files = new ArrayList<>();
            try (var walk = Files.walk(temp)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(path -> files.add(path.toString()));
            }
            ArrayList<String> command = new ArrayList<>();
            command.add("-encoding");
            command.add("UTF-8");
            command.add("-d");
            command.add(temp.toString());
            command.addAll(files);
            command.add(0, System.getProperty("java.class.path"));
            command.add(0, "-cp");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, command.toArray(String[]::new)) != 0)
                throw new AssertionError("概览夹具编译失败");
            ArrayList<URL> urls = new ArrayList<>();
            urls.add(temp.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                urls.add(Path.of(entry).toUri().toURL());
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass("org.telegram.ui.SettingsOverviewProbe").getMethod("main", String[].class)
                            .invoke(null, (Object) new String[0]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("概览布局回归失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    /** 底栏矢量仍是 24dp 白填充，字形外接框至少 20。 */
    private static void glyphs(Path drawable) throws Exception {
        for (String name : List.of("codextop_pixel_chat.xml", "codextop_pixel_devices.xml", "codextop_pixel_profile.xml")) {
            String xml = Files.readString(drawable.resolve(name));
            if (!xml.contains("android:width=\"24dp\"") || !xml.contains("android:height=\"24dp\"") || !xml.contains("#FFFFFF"))
                throw new AssertionError(name + " 不再是 24dp 白填充");
            String data = xml.replaceAll("(?s).*android:pathData=\"([^\"]+)\".*", "$1");
            int[] box = bounds(data);
            if (box[0] < 0 || box[1] < 0 || box[2] > 24 || box[3] > 24 || box[2] - box[0] < 20 || box[3] - box[1] < 20)
                throw new AssertionError(name + " 字形不在 20 到 24");
        }
    }

    private static int[] bounds(String data) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int x = 0, y = 0, sx = 0, sy = 0;
        Matcher matcher = Pattern.compile("([MmHhVvZz])(-?\\d+)?(?:,(-?\\d+))?").matcher(data);
        while (matcher.find()) {
            String op = matcher.group(1);
            if (op.equalsIgnoreCase("m")) {
                int nx = Integer.parseInt(matcher.group(2));
                int ny = Integer.parseInt(matcher.group(3));
                if (op.equals("m")) { nx += x; ny += y; }
                x = sx = nx;
                y = sy = ny;
            } else if (op.equalsIgnoreCase("h")) {
                int nx = op.equals("H") ? Integer.parseInt(matcher.group(2)) : x + Integer.parseInt(matcher.group(2));
                minX = Math.min(minX, Math.min(x, nx));
                maxX = Math.max(maxX, Math.max(x, nx));
                x = nx;
                continue;
            } else if (op.equalsIgnoreCase("v")) {
                int ny = op.equals("V") ? Integer.parseInt(matcher.group(2)) : y + Integer.parseInt(matcher.group(2));
                minY = Math.min(minY, Math.min(y, ny));
                maxY = Math.max(maxY, Math.max(y, ny));
                y = ny;
                continue;
            } else if (op.equalsIgnoreCase("z")) {
                x = sx;
                y = sy;
                continue;
            }
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return new int[] {minX, minY, maxX, maxY};
    }

    private static String wrapper(String cells) {
        return """
                package org.telegram.ui;
                import static org.telegram.messenger.AndroidUtilities.dp;
                import android.content.Context;
                import android.graphics.Canvas;
                import android.graphics.Paint;
                import android.text.TextUtils;
                import android.util.TypedValue;
                import android.view.Gravity;
                import android.view.View;
                import android.widget.LinearLayout;
                import android.widget.TextView;
                import org.telegram.ui.ActionBar.Theme;
                import org.telegram.ui.Components.LayoutHelper;
                import org.telegram.ui.Components.RecyclerListView;
                import org.telegram.ui.Components.UItem;
                import org.telegram.ui.Components.UniversalAdapter;
                import org.telegram.ui.Components.UniversalRecyclerView;
                import java.util.ArrayList;
                public class SettingsActivity {
                """ + cells + "}";
    }

    private static void stub(Path temp, String name, String source) throws Exception {
        Path file = temp.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static void writeStubs(Path temp) throws Exception {
        stub(temp, "android/view/View.java", """
                package android.view;
                public class View {
                    public static final int VISIBLE = 0;
                    public View() {}
                    public View(android.content.Context context) {}
                    public android.content.Context getContext() { return null; }
                    protected void onDraw(android.graphics.Canvas canvas) {}
                    public ViewGroup.LayoutParams params;
                    public int measuredWidth, measuredHeight, padL, padT, padR, padB, color, background;
                    public void setLayoutParams(ViewGroup.LayoutParams params) { this.params = params; }
                    public ViewGroup.LayoutParams getLayoutParams() { return params; }
                    public void setPadding(int l, int t, int r, int b) { padL = l; padT = t; padR = r; padB = b; }
                    public int getPaddingLeft() { return padL; }
                    public int getPaddingTop() { return padT; }
                    public int getPaddingRight() { return padR; }
                    public int getPaddingBottom() { return padB; }
                    public final void measure(int width, int height) { onMeasure(width, height); }
                    protected void onMeasure(int width, int height) { setMeasuredDimension(View.MeasureSpec.getSize(width), View.MeasureSpec.getSize(height)); }
                    protected final void setMeasuredDimension(int width, int height) { measuredWidth = width; measuredHeight = height; }
                    public int getMeasuredWidth() { return measuredWidth; }
                    public int getMeasuredHeight() { return measuredHeight; }
                    public int getWidth() { return measuredWidth; }
                    public int getHeight() { return measuredHeight; }
                    public void setBackgroundColor(int color) { background = color; }
                    public void invalidate() {}
                    public static class MeasureSpec {
                        public static final int UNSPECIFIED = 0, EXACTLY = 1 << 30;
                        public static int makeMeasureSpec(int size, int mode) { return mode | (size & 0x3fffffff); }
                        public static int getMode(int spec) { return spec & 0xc0000000; }
                        public static int getSize(int spec) { return spec & 0x3fffffff; }
                    }
                }
                """);
        stub(temp, "android/view/ViewGroup.java", """
                package android.view;
                import java.util.ArrayList;
                public class ViewGroup extends View {
                    public final ArrayList<View> children = new ArrayList<>();
                    public void addView(View child) { children.add(child); }
                    public void addView(View child, LayoutParams params) { child.setLayoutParams(params); children.add(child); }
                    public void removeAllViews() { children.clear(); }
                    public int getChildCount() { return children.size(); }
                    public View getChildAt(int index) { return children.get(index); }
                    public static class LayoutParams {
                        public int width, height, leftMargin, topMargin, rightMargin, bottomMargin;
                        public LayoutParams(int width, int height) { this.width = width; this.height = height; }
                    }
                }
                """);
        stub(temp, "android/view/Gravity.java", "package android.view; public class Gravity { public static final int CENTER_VERTICAL = 16; }\n");
        stub(temp, "android/content/Context.java", "package android.content; public class Context {}\n");
        stub(temp, "android/util/TypedValue.java", "package android.util; public class TypedValue { public static final int COMPLEX_UNIT_SP = 2; }\n");
        stub(temp, "android/graphics/Canvas.java", "package android.graphics; public class Canvas { public void drawRect(float l, float t, float r, float b, Paint paint) {} }\n");
        stub(temp, "android/graphics/Paint.java", "package android.graphics; public class Paint { public static final int ANTI_ALIAS_FLAG = 1; public Paint() {} public Paint(int flags) {} public void setColor(int color) {} }\n");
        stub(temp, "android/text/TextUtils.java", """
                package android.text;
                public class TextUtils {
                    public enum TruncateAt { END }
                    public static boolean equals(CharSequence a, CharSequence b) {
                        if (a == null || b == null) return a == b;
                        return a.toString().contentEquals(b);
                    }
                    public static boolean isEmpty(CharSequence value) { return value == null || value.length() == 0; }
                }
                """);
        stub(temp, "android/widget/TextView.java", """
                package android.widget;
                import android.content.Context;
                import android.text.TextUtils;
                import android.util.TypedValue;
                import android.view.View;
                import org.telegram.messenger.AndroidUtilities;
                public class TextView extends View {
                    public CharSequence text = "";
                    public float textSize = 15;
                    public int unit, color;
                    public TextUtils.TruncateAt ellipsize;
                    public boolean singleLine;
                    public TextView(Context context) {}
                    public void setText(CharSequence value) { text = value == null ? "" : value; }
                    public CharSequence getText() { return text; }
                    public void setTextSize(int unit, float size) { this.unit = unit; textSize = size; }
                    public void setTextColor(int color) { this.color = color; }
                    public void setSingleLine(boolean single) { singleLine = single; }
                    public void setEllipsize(TextUtils.TruncateAt value) { ellipsize = value; }
                    @Override protected void onMeasure(int widthSpec, int heightSpec) {
                        int width = Math.max(1, View.MeasureSpec.getSize(widthSpec));
                        float px = textSize * (unit == TypedValue.COMPLEX_UNIT_SP ? AndroidUtilities.fontScale : 1f);
                        int lines = singleLine || text.length() == 0 ? 1 : (int) Math.ceil(text.length() * px / width);
                        setMeasuredDimension(width, (int) Math.ceil(Math.max(1, lines) * px * 1.3f));
                    }
                }
                """);
        stub(temp, "android/widget/LinearLayout.java", """
                package android.widget;
                import android.content.Context;
                import android.view.View;
                import android.view.ViewGroup;
                public class LinearLayout extends ViewGroup {
                    public static final int HORIZONTAL = 0, VERTICAL = 1;
                    public int orientation = HORIZONTAL;
                    public LinearLayout(Context context) {}
                    public void setOrientation(int orientation) { this.orientation = orientation; }
                    public static class LayoutParams extends ViewGroup.LayoutParams {
                        public int gravity; public float weight;
                        public LayoutParams(int width, int height) { super(width, height); }
                        public LayoutParams(int width, int height, float weight) { super(width, height); this.weight = weight; }
                    }
                    @Override protected void onMeasure(int widthSpec, int heightSpec) {
                        int width = View.MeasureSpec.getSize(widthSpec);
                        int inner = Math.max(1, width - getPaddingLeft() - getPaddingRight());
                        int total = getPaddingTop() + getPaddingBottom();
                        int tallest = 0;
                        for (View child : children) {
                            int vertical = 0;
                            if (child.getLayoutParams() instanceof LayoutParams params) vertical = params.topMargin + params.bottomMargin;
                            child.measure(View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                            total += child.getMeasuredHeight() + vertical;
                            tallest = Math.max(tallest, child.getMeasuredHeight());
                        }
                        int natural = orientation == VERTICAL ? total : tallest + getPaddingTop() + getPaddingBottom();
                        int height = View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.EXACTLY ? View.MeasureSpec.getSize(heightSpec) : natural;
                        setMeasuredDimension(width, height);
                    }
                }
                """);
        stub(temp, "org/telegram/messenger/AndroidUtilities.java", """
                package org.telegram.messenger;
                public final class AndroidUtilities {
                    public static float density = 1f;
                    public static float fontScale = 1f;
                    public static int dp(float value) { return (int) Math.ceil(density * value); }
                }
                """);
        stub(temp, "org/telegram/ui/ActionBar/Theme.java", """
                package org.telegram.ui.ActionBar;
                public final class Theme {
                    public static final int key_dialogBackground = 1;
                    public static final int key_windowBackgroundWhiteBlackText = 2;
                    public static final int key_windowBackgroundWhiteGrayText = 3;
                    public static final int key_windowBackgroundWhiteBlueText = 4;
                    public static final int key_divider = 5;
                    public static int getColor(int key, ResourcesProvider provider) { return key; }
                    public interface ResourcesProvider {}
                    public interface Colorable { void updateColors(); }
                }
                """);
        stub(temp, "org/telegram/ui/Components/LayoutHelper.java", """
                package org.telegram.ui.Components;
                import android.widget.LinearLayout;
                public final class LayoutHelper {
                    public static final int MATCH_PARENT = -1, WRAP_CONTENT = -2;
                    private static int size(int value) { return value; }
                    public static LinearLayout.LayoutParams createLinear(int width, int height) {
                        return new LinearLayout.LayoutParams(size(width), size(height));
                    }
                    public static LinearLayout.LayoutParams createLinear(int width, int height, float left, float top, float right, float bottom) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size(width), size(height));
                        params.leftMargin = (int) left; params.topMargin = (int) top; params.rightMargin = (int) right; params.bottomMargin = (int) bottom;
                        return params;
                    }
                    public static LinearLayout.LayoutParams createLinear(int width, int height, int gravity, int left, int top, int right, int bottom) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size(width), size(height));
                        params.gravity = gravity; params.leftMargin = left; params.topMargin = top; params.rightMargin = right; params.bottomMargin = bottom;
                        return params;
                    }
                    public static LinearLayout.LayoutParams createLinear(int width, int height, float weight, int gravity, int left, int top, int right, int bottom) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size(width), size(height), weight);
                        params.gravity = gravity; params.leftMargin = left; params.topMargin = top; params.rightMargin = right; params.bottomMargin = bottom;
                        return params;
                    }
                }
                """);
        stub(temp, "org/telegram/ui/Components/UItem.java", """
                package org.telegram.ui.Components;
                import android.view.View;
                public class UItem {
                    public int id;
                    public boolean enabled = true;
                    public CharSequence text, subtext;
                    public Object object, object2;
                    public UItem setEnabled(boolean value) { enabled = value; return this; }
                    public UItem(int viewType, boolean selectable) {}
                    public static UItem ofFactory(Class<?> type) { return new UItem(1, false); }
                    public static abstract class UItemFactory<V extends View> {
                        public static void setup(UItemFactory<?> factory) {}
                        public V createView(android.content.Context context, RecyclerListView listView, int currentAccount, int classGuid, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider) { return null; }
                        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {}
                        public boolean isClickable() { return true; }
                        public boolean equals(UItem left, UItem right) { return false; }
                        public boolean contentsEquals(UItem left, UItem right) { return false; }
                    }
                }
                """);
        stub(temp, "org/telegram/ui/Components/RecyclerListView.java", "package org.telegram.ui.Components; public class RecyclerListView {}\n");
        stub(temp, "org/telegram/ui/Components/UniversalAdapter.java", "package org.telegram.ui.Components; public class UniversalAdapter {}\n");
        stub(temp, "org/telegram/ui/Components/UniversalRecyclerView.java", "package org.telegram.ui.Components; public class UniversalRecyclerView {}\n");
        stub(temp, "com/butang/codextop/BrowseStore.java", """
                package com.butang.codextop;
                import com.google.gson.JsonObject;
                public class BrowseStore {
                    public static String identity(String kind, JsonObject row) {
                        if ("conversations".equals(kind)) return row.get("remoteSessionId").getAsString();
                        return row.get("id").getAsString();
                    }
                }
                """);
    }

    private static final String PROBE = """
            package org.telegram.ui;
            import android.view.View;
            import android.view.ViewGroup;
            import android.widget.TextView;
            import com.google.gson.JsonObject;
            import java.util.ArrayList;
            import java.util.List;
            import org.telegram.messenger.AndroidUtilities;
            import org.telegram.ui.ActionBar.Theme;
            import org.telegram.ui.Components.UItem;
            public final class SettingsOverviewProbe {
                static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
                static int height(View view) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    return view.getMeasuredHeight();
                }
                static List<TextView> texts(View view) {
                    ArrayList<TextView> found = new ArrayList<>();
                    if (view instanceof TextView text) found.add(text);
                    if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) found.addAll(texts(group.getChildAt(i)));
                    return found;
                }
                static List<SettingsActivity.UsageCell.ScaleView> scales(View view) {
                    ArrayList<SettingsActivity.UsageCell.ScaleView> found = new ArrayList<>();
                    if (view instanceof SettingsActivity.UsageCell.ScaleView scale) found.add(scale);
                    if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) found.addAll(scales(group.getChildAt(i)));
                    return found;
                }
                static boolean has(View view, String value) {
                    for (TextView text : texts(view)) if (value.contentEquals(text.getText())) return true;
                    return false;
                }
                static TextView text(View view, String value) {
                    for (TextView item : texts(view)) if (value.contentEquals(item.getText())) return item;
                    throw new AssertionError(value);
                }
                static SettingsActivity.UsageCell.MeterLine meter(String label, String window, String percent, String suffix, String reset, boolean hide, double amount) {
                    return new SettingsActivity.UsageCell.MeterLine(label, window, percent, suffix, reset, hide, amount);
                }
                static SettingsActivity.UsageCell.State state(boolean stale, SettingsActivity.UsageCell.MeterLine... lines) {
                    return new SettingsActivity.UsageCell.State(List.of(lines), "示例账号", "示例时刻", stale, false, null);
                }
                public static void main(String[] args) {
                    var usageFactory = new SettingsActivity.UsageCell.Factory();
                    var missing = meter("示例窗口", "周期未返回", null, null, "来源未返回", true, 0);
                    var live = meter("示例窗口", "5 小时", "72%", "采集时预计剩余", "示例日期", false, 72);
                    UItem first = SettingsActivity.UsageCell.Factory.of(state(false, missing, live));
                    UItem same = SettingsActivity.UsageCell.Factory.of(state(false, missing, live));
                    UItem changed = SettingsActivity.UsageCell.Factory.of(state(false, missing, meter("示例窗口", "5 小时", "10%", "采集时剩余", "示例日期", false, 10)));
                    check(!first.enabled && !usageFactory.isClickable(), "额度块仍可点按");
                    check("codex-usage".equals(first.object2) && first.id == 40, "额度行没有稳定位置");
                    check(usageFactory.equals(first, changed) && usageFactory.contentsEquals(first, same) && !usageFactory.contentsEquals(first, changed), "额度内容被当成同一引用");
                    var context = new android.content.Context();
                    AndroidUtilities.fontScale = 1f;
                    var cell = new SettingsActivity.UsageCell(context, null);
                    usageFactory.bindView(cell, first, false, null, null);
                    check(has(cell, "剩余比例未返回") && !has(cell, "0%"), "缺失额度被画成 0");
                    int one = height(cell);
                    check(scales(cell).get(0).hideFill && scales(cell).get(0).getMeasuredHeight() == 4, "缺失刻度仍有填充或过厚");
                    check(!scales(cell).get(1).hideFill && scales(cell).get(1).percent == 72, "72 没有按真实比例保留");
                    check(Math.abs(SettingsActivity.UsageCell.ScaleView.segmentFill(0, 1) - 0.1f) < 0.001f
                            && Math.abs(SettingsActivity.UsageCell.ScaleView.segmentFill(9, 95) - 0.5f) < 0.001f
                            && SettingsActivity.UsageCell.ScaleView.segmentFill(9, 95) < 1f, "1% 被画空或 95% 被画满");
                    check(text(cell, "72%").color == Theme.key_windowBackgroundWhiteBlueText, "实时百分比不是强调色");
                    check(has(cell, "采集时预计剩余") && has(cell, "重置时间：来源未返回"), "预计或重置说明丢失");
                    usageFactory.bindView(cell, SettingsActivity.UsageCell.Factory.of(state(false, live, live, live)), false, null, null);
                    check(height(cell) > one, "多个窗口没有把卡片撑高");
                    usageFactory.bindView(cell, SettingsActivity.UsageCell.Factory.of(state(true, live)), false, null, null);
                    check(has(cell, "采集时间（已过期）") && text(cell, "72%").color == Theme.key_windowBackgroundWhiteGrayText, "过期额度仍像实时");
                    var metaFactory = new SettingsActivity.AccountMetaCell.Factory();
                    String longAccount = "示例账号示例账号示例账号示例账号示例账号";
                    UItem meta = SettingsActivity.AccountMetaCell.Factory.of(longAccount, "示例连接", "示例地址", "已读取的聊天记录保存在本机，联网后自动更新", "示例版本");
                    UItem metaAgain = SettingsActivity.AccountMetaCell.Factory.of(longAccount, "示例连接", "示例地址", "已读取的聊天记录保存在本机，联网后自动更新", "示例版本");
                    check(!meta.enabled && !metaFactory.isClickable(), "资料块仍可点按");
                    check(metaFactory.contentsEquals(meta, metaAgain), "相同资料被看成不同内容");
                    var metaCell = new SettingsActivity.AccountMetaCell(context, null);
                    metaFactory.bindView(metaCell, meta, false, null, null);
                    check(metaCell.orientation == android.widget.LinearLayout.VERTICAL, "资料被排成左右两列");
                    for (TextView item : texts(metaCell)) check(item.getLayoutParams().width == -1, "资料行没有占满宽度");
                    AndroidUtilities.fontScale = 1f;
                    int compact = height(metaCell);
                    AndroidUtilities.fontScale = 2f;
                    int grown = height(metaCell);
                    check(grown > compact && longAccount.contentEquals(text(metaCell, longAccount).getText()), "长账号在大字号下被裁切");
                    JsonObject row = new JsonObject();
                    row.addProperty("id", "synthetic");
                    row.addProperty("name", "示例电脑甲");
                    row.addProperty("active", true);
                    UItem computer = SettingsActivity.ComputerCell.Factory.of(row);
                    JsonObject copy = new JsonObject();
                    copy.addProperty("id", "synthetic");
                    copy.addProperty("name", "示例电脑甲");
                    copy.addProperty("active", true);
                    UItem computerCopy = SettingsActivity.ComputerCell.Factory.of(copy);
                    JsonObject other = new JsonObject();
                    other.addProperty("id", "other");
                    other.addProperty("name", "示例电脑甲");
                    other.addProperty("active", true);
                    UItem otherItem = SettingsActivity.ComputerCell.Factory.of(other);
                    JsonObject offline = new JsonObject();
                    offline.addProperty("id", "synthetic");
                    offline.addProperty("name", "示例电脑甲");
                    offline.addProperty("active", false);
                    UItem renamed = SettingsActivity.ComputerCell.Factory.of(offline);
                    var computerFactory = new SettingsActivity.ComputerCell.Factory();
                    check(computer.enabled && computerFactory.isClickable(), "电脑行不能再进入项目");
                    check(computer.id == 1000 && computer.object == row && "computers:synthetic".equals(computer.object2), "电脑行丢失原来的身份或负载");
                    check(computerFactory.equals(computer, computerCopy) && !computerFactory.equals(computer, otherItem), "电脑稳定键不再只看身份");
                    check(computerFactory.contentsEquals(computer, computerCopy) && !computerFactory.contentsEquals(computer, renamed), "电脑在线状态变化没有更新内容");
                    JsonObject unknown = new JsonObject();
                    unknown.addProperty("id", "synthetic");
                    unknown.addProperty("name", "示例电脑乙");
                    check("未知".contentEquals(SettingsActivity.ComputerCell.Factory.of(unknown).subtext), "缺失连接被写成离线");
                    AndroidUtilities.fontScale = 1f;
                    var computerCell = new SettingsActivity.ComputerCell(context, null);
                    computerFactory.bindView(computerCell, computer, false, null, null);
                    int natural = height(computerCell);
                    String longName = "示例电脑甲示例电脑甲示例电脑甲示例电脑甲示例电脑甲";
                    row.addProperty("name", longName);
                    UItem longItem = SettingsActivity.ComputerCell.Factory.of(row);
                    AndroidUtilities.fontScale = 2f;
                    computerFactory.bindView(computerCell, longItem, false, null, null);
                    int tall = height(computerCell);
                    check(natural >= AndroidUtilities.dp(80) && tall > natural && tall > AndroidUtilities.dp(104), "长电脑名或大字号被固定高度裁切");
                    check(longName.contentEquals(text(computerCell, longName).getText()) && text(computerCell, longName).ellipsize == null, "电脑名被省略");
                    check(computerCell.monitor.color == Theme.key_windowBackgroundWhiteBlueText && has(computerCell, "查看项目"), "电脑符号不是强调色");
                    System.out.println("PASS SettingsOverviewLayoutTest");
                }
            }
            """;
}
