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
        if (!fill.contains("UsageCell.Factory.of") || !fill.contains("ofCodex(25,"))
            throw new AssertionError("额度摘要或原来源入口丢失");
        if (fill.contains("ofCodex(33,") || fill.contains("刷新额度"))
            throw new AssertionError("根页或额度详情重新出现手动刷新入口");
        var accountMeta = activity.getMethodsByName("fillItems").get(0)
                .findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("of")
                        && call.getScope().map(Object::toString).orElse("").equals("AccountMetaCell.Factory"))
                .findFirst().orElseThrow();
        if (!accountMeta.getArgument(2).isNullLiteralExpr() || !accountMeta.getArgument(3).isNullLiteralExpr())
            throw new AssertionError("我的页重新填入服务地址或本地缓存说明");
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
        var computer = activity.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals("ComputerCell")).findFirst().orElseThrow();
        for (var draw : computer.getMethodsByName("onDraw")) {
            if (!draw.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class).isEmpty())
                throw new AssertionError("电脑卡片绘制分配了新对象");
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
                    public static final int VISIBLE = 0, GONE = 8;
                    private int visibility = VISIBLE;
                    public View() {}
                    public View(android.content.Context context) {}
                    public android.content.Context getContext() { return null; }
                    protected void onDraw(android.graphics.Canvas canvas) {}
                    protected void onSizeChanged(int w, int h, int oldw, int oldh) {}
                    private int laidWidth, laidHeight;
                    public void layout(int l, int t, int r, int b) {
                        int w = r - l, h = b - t;
                        if (w != laidWidth || h != laidHeight) onSizeChanged(w, h, laidWidth, laidHeight);
                        laidWidth = w; laidHeight = h;
                    }
                    public void draw(android.graphics.Canvas canvas) { onDraw(canvas); }
                    public void setWillNotDraw(boolean ignored) {}
                    public ViewGroup.LayoutParams params;
                    public int measuredWidth, measuredHeight, padL, padT, padR, padB, color, background;
                    public void setLayoutParams(ViewGroup.LayoutParams params) { this.params = params; }
                    public ViewGroup.LayoutParams getLayoutParams() { return params; }
                    public void setPadding(int l, int t, int r, int b) { padL = l; padT = t; padR = r; padB = b; }
                    public int getPaddingLeft() { return padL; }
                    public int getPaddingTop() { return padT; }
                    public int getPaddingRight() { return padR; }
                    public int getPaddingBottom() { return padB; }
                    public void setVisibility(int visibility) { this.visibility = visibility; }
                    public int getVisibility() { return visibility; }
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
        stub(temp, "android/view/Gravity.java", "package android.view; public class Gravity { public static final int CENTER_VERTICAL = 16, END = 0x800005; }\n");
        stub(temp, "android/content/Context.java", "package android.content; public class Context {}\n");
        stub(temp, "android/util/TypedValue.java", "package android.util; public class TypedValue { public static final int COMPLEX_UNIT_SP = 2; }\n");
        stub(temp, "android/graphics/Canvas.java", """
                package android.graphics;
                import java.util.ArrayList;
                public class Canvas {
                    public final ArrayList<Draw> paths = new ArrayList<>();
                    public void drawRect(float l, float t, float r, float b, Paint paint) {}
                    public void drawPath(Path path, Paint paint) { paths.add(new Draw(path, paint)); }
                    public static class Draw {
                        public final Path source;
                        public final Paint.Style style;
                        public final float strokeWidth, radius;
                        public final int color, alpha;
                        public final boolean rounded;
                        public final ArrayList<float[]> points = new ArrayList<>();
                        public Draw(Path path, Paint paint) {
                            source = path; style = paint.style; strokeWidth = paint.strokeWidth;
                            radius = path.radius; rounded = path.rounded; color = paint.color; alpha = paint.alpha;
                            for (float[] point : path.points) points.add(point.clone());
                        }
                        public float bound(int axis, boolean max) {
                            float result = max ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
                            for (float[] command : points) for (int i = axis; i < command.length; i += 2)
                                result = max ? Math.max(result, command[i]) : Math.min(result, command[i]);
                            return result;
                        }
                        public boolean hasPoint(float x, float y) {
                            for (float[] point : points) if (point.length == 2 && Math.abs(point[0] - x) < .01f && Math.abs(point[1] - y) < .01f) return true;
                            return false;
                        }
                    }
                }
                """);
        stub(temp, "android/graphics/Paint.java", """
                package android.graphics;
                public class Paint {
                    public static final int ANTI_ALIAS_FLAG = 1;
                    public enum Style { FILL, STROKE }
                    public Style style = Style.FILL;
                    public float strokeWidth;
                    public int color, alpha = 255;
                    public Paint() {} public Paint(int flags) {}
                    public void setColor(int color) { this.color = color; }
                    public void setStyle(Style style) { this.style = style; }
                    public void setStrokeWidth(float width) { strokeWidth = width; }
                    public void setAlpha(int alpha) { this.alpha = alpha; }
                }
                """);
        stub(temp, "android/graphics/Path.java", """
                package android.graphics;
                import java.util.ArrayList;
                public class Path {
                    public enum Direction { CW, CCW }
                    public final ArrayList<float[]> points = new ArrayList<>();
                    public int resets;
                    public float radius;
                    public boolean rounded;
                    public void reset() { points.clear(); radius = 0; rounded = false; resets++; }
                    public void moveTo(float x, float y) { points.add(new float[]{x, y}); }
                    public void lineTo(float x, float y) { points.add(new float[]{x, y}); }
                    public void close() {}
                    public void addRoundRect(float l, float t, float r, float b, float rx, float ry, Direction direction) {
                        points.add(new float[]{l, t, r, b}); radius = rx; rounded = true;
                    }
                }
                """);
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
                    public int unit, color, gravity;
                    public TextUtils.TruncateAt ellipsize;
                    public boolean singleLine;
                    public TextView(Context context) {}
                    public void setText(CharSequence value) { text = value == null ? "" : value; }
                    public CharSequence getText() { return text; }
                    public void setTextSize(int unit, float size) { this.unit = unit; textSize = size; }
                    public void setTextColor(int color) { this.color = color; }
                    public void setSingleLine(boolean single) { singleLine = single; }
                    public void setEllipsize(TextUtils.TruncateAt value) { ellipsize = value; }
                    public void setGravity(int gravity) { this.gravity = gravity; }
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
                        int reserved = 0;
                        if (orientation == HORIZONTAL) for (View child : children) {
                            if (child.getVisibility() == View.GONE) continue;
                            LayoutParams params = (LayoutParams) child.getLayoutParams();
                            reserved += params.leftMargin + params.rightMargin;
                            if (params.weight == 0 && params.width >= 0) reserved += params.width;
                        }
                        for (View child : children) {
                            if (child.getVisibility() == View.GONE) continue;
                            int vertical = 0;
                            if (child.getLayoutParams() instanceof LayoutParams params) vertical = params.topMargin + params.bottomMargin;
                            LayoutParams params = (LayoutParams) child.getLayoutParams();
                            int childWidth = params.width >= 0 ? params.width : Math.max(1, inner - params.leftMargin - params.rightMargin);
                            if (orientation == HORIZONTAL && params.weight > 0) childWidth = Math.max(1, inner - reserved);
                            int childHeight = params.height >= 0 ? View.MeasureSpec.makeMeasureSpec(params.height, View.MeasureSpec.EXACTLY)
                                    : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                            child.measure(View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY), childHeight);
                            total += child.getMeasuredHeight() + vertical;
                            tallest = Math.max(tallest, child.getMeasuredHeight() + vertical);
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
                    public static int styleId = 1;
                    public static class PixelStyle { public int id; PixelStyle(int id) { this.id = id; } }
                    public static PixelStyle getCodexPixelStyle() { return new PixelStyle(styleId); }
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
                    private static int size(int value) { return value < 0 ? value : org.telegram.messenger.AndroidUtilities.dp(value); }
                    public static LinearLayout.LayoutParams createLinear(int width, int height) {
                        return new LinearLayout.LayoutParams(size(width), size(height));
                    }
                    public static LinearLayout.LayoutParams createLinear(int width, int height, float left, float top, float right, float bottom) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size(width), size(height));
                        params.leftMargin = org.telegram.messenger.AndroidUtilities.dp(left); params.topMargin = org.telegram.messenger.AndroidUtilities.dp(top); params.rightMargin = org.telegram.messenger.AndroidUtilities.dp(right); params.bottomMargin = org.telegram.messenger.AndroidUtilities.dp(bottom);
                        return params;
                    }
                    public static LinearLayout.LayoutParams createLinear(int width, int height, int gravity, int left, int top, int right, int bottom) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size(width), size(height));
                        params.gravity = gravity; params.leftMargin = org.telegram.messenger.AndroidUtilities.dp(left); params.topMargin = org.telegram.messenger.AndroidUtilities.dp(top); params.rightMargin = org.telegram.messenger.AndroidUtilities.dp(right); params.bottomMargin = org.telegram.messenger.AndroidUtilities.dp(bottom);
                        return params;
                    }
                    public static LinearLayout.LayoutParams createLinear(int width, int height, float weight, int gravity, int left, int top, int right, int bottom) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size(width), size(height), weight);
                        params.gravity = gravity; params.leftMargin = org.telegram.messenger.AndroidUtilities.dp(left); params.topMargin = org.telegram.messenger.AndroidUtilities.dp(top); params.rightMargin = org.telegram.messenger.AndroidUtilities.dp(right); params.bottomMargin = org.telegram.messenger.AndroidUtilities.dp(bottom);
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
                static boolean hasVisible(View view, String value) {
                    for (TextView text : texts(view)) if (text.getVisibility() == View.VISIBLE && value.contentEquals(text.getText())) return true;
                    return false;
                }
                static TextView text(View view, String value) {
                    for (TextView item : texts(view)) if (value.contentEquals(item.getText())) return item;
                    throw new AssertionError(value);
                }
                static android.graphics.Canvas outline(View cell) {
                    cell.layout(0, 0, cell.getMeasuredWidth(), cell.getMeasuredHeight());
                    var canvas = new android.graphics.Canvas();
                    cell.draw(canvas);
                    return canvas;
                }
                static void computerGeometry(SettingsActivity.ComputerCell cell, SettingsActivity.ComputerCell.Factory factory, UItem item) {
                    AndroidUtilities.fontScale = 1f;
                    factory.bindView(cell, item, false, null, null);
                    check(cell.monitor.getLayoutParams().width == AndroidUtilities.dp(48)
                            && cell.monitor.getLayoutParams().height == AndroidUtilities.dp(48), "电脑图形未形成48dp内容分区");
                    check(cell.getPaddingLeft() == AndroidUtilities.dp(32) && cell.getPaddingRight() == AndroidUtilities.dp(32)
                            && cell.getPaddingTop() == AndroidUtilities.dp(22) && cell.getPaddingBottom() == AndroidUtilities.dp(22),
                            "外16/6与内16/16间距未保留");
                    TextView entry = text(cell, "查看项目 →");
                    check(entry.gravity == android.view.Gravity.END && !entry.singleLine && entry.ellipsize == null,
                            "整行项目提示未靠末端或被截断");
                    var body = (ViewGroup) cell.getChildAt(2);
                    check(body.getChildCount() == 3 && body.getChildAt(2) == entry,
                            "项目提示没有留在原名称和状态之后");
                    check(body.getChildAt(1).getLayoutParams().topMargin == AndroidUtilities.dp(4)
                            && entry.getLayoutParams().topMargin == AndroidUtilities.dp(12), "状态与右下提示没有分层");
                    float industrialRadius = 0, terminalRadius = 0, blueprintRadius = 0;
                    for (int style : new int[]{1, 2, 3, 4, 6}) {
                        Theme.styleId = style;
                        cell.updateColors();
                        int h = height(cell);
                        check(h >= AndroidUtilities.dp(122), "电脑卡片未保留自然最低122dp");
                        check(cell.getChildAt(0).getVisibility() == (style == 3 ? View.VISIBLE : View.GONE),
                                "原边条不是只在03可见");
                        check(cell.monitor.getMeasuredWidth() == AndroidUtilities.dp(48)
                                && cell.monitor.getMeasuredHeight() == AndroidUtilities.dp(48), "设备图实际测量尺寸不是48dp");
                        check(body.getMeasuredWidth() >= AndroidUtilities.dp(180), "320dp下名称区被设备图挤掉");
                        var canvas = outline(cell);
                        check(canvas.paths.size() == 2, "卡片没有单一填充和内侧轮廓");
                        var fill = canvas.paths.get(0); var stroke = canvas.paths.get(1);
                        check(fill.style == android.graphics.Paint.Style.FILL && fill.color == Theme.key_dialogBackground
                                && stroke.style == android.graphics.Paint.Style.STROKE && stroke.strokeWidth == AndroidUtilities.dp(1),
                                "卡片未复用主题表面或密度描边");
                        check(Math.abs(stroke.bound(0, false) - stroke.strokeWidth / 2 - AndroidUtilities.dp(16)) < .01f
                                && Math.abs(stroke.bound(0, true) + stroke.strokeWidth / 2 - (cell.getMeasuredWidth() - AndroidUtilities.dp(16))) < .01f
                                && Math.abs(stroke.bound(1, false) - stroke.strokeWidth / 2 - AndroidUtilities.dp(6)) < .01f
                                && Math.abs(stroke.bound(1, true) + stroke.strokeWidth / 2 - (h - AndroidUtilities.dp(6))) < .01f,
                                "卡片可见边界不在16/6内或描边被裁切");
                        check(fill.source == stroke.source, "填充与描边复制了路径");
                        int resets = stroke.source.resets;
                        var again = outline(cell);
                        check(again.paths.get(1).source == stroke.source && stroke.source.resets == resets,
                                "绘制期间重新分配或重建了轮廓");
                        if (style == 2 || style == 4) {
                            check(!stroke.rounded, "出版或色块主题没有静态切角");
                            float right = stroke.bound(0, true), top = stroke.bound(1, false), bottom = stroke.bound(1, true);
                            check(style == 2 ? !stroke.hasPoint(right, bottom) && stroke.hasPoint(right, top)
                                    : !stroke.hasPoint(right, top) && stroke.hasPoint(right, bottom), "02/04切角方向没有区分");
                        } else {
                            check(stroke.rounded, "终端/工业/蓝图卡片没有轻圆角");
                            if (style == 1) terminalRadius = stroke.radius;
                            if (style == 3) industrialRadius = stroke.radius;
                            if (style == 6) blueprintRadius = stroke.radius;
                        }
                    }
                    check(industrialRadius > terminalRadius && terminalRadius > blueprintRadius, "三个圆角轮廓未区分层次");
                    JsonObject cached = new JsonObject(); cached.addProperty("id", "synthetic");
                    cached.addProperty("name", "示例电脑"); cached.addProperty("cached", true); cached.addProperty("active", true);
                    factory.bindView(cell, SettingsActivity.ComputerCell.Factory.of(cached), false, null, null);
                    check(has(cell, "状态未更新"), "缓存连接被排版改成在线");
                    JsonObject unknown = new JsonObject(); unknown.addProperty("id", "synthetic"); unknown.addProperty("name", "示例电脑");
                    factory.bindView(cell, SettingsActivity.ComputerCell.Factory.of(unknown), false, null, null);
                    check(has(cell, "未知"), "未知连接被排版改成离线");
                    AndroidUtilities.fontScale = 2f;
                    String longName = "很长的电脑名称需要自然换行".repeat(6), longStatus = "状态信息需要完整显示".repeat(4);
                    cell.bind(longName, longStatus);
                    int tall = height(cell);
                    check(tall > AndroidUtilities.dp(122) && has(cell, longName) && has(cell, longStatus)
                            && !text(cell, longName).singleLine && !text(cell, longStatus).singleLine
                            && text(cell, longName).ellipsize == null && text(cell, longStatus).ellipsize == null,
                            "320dp和2倍字号下电脑名或状态被截断");
                    var tallCanvas = outline(cell);
                    check(Math.abs(tallCanvas.paths.get(1).bound(1, true) + AndroidUtilities.dp(1) / 2f
                            - (tall - AndroidUtilities.dp(6))) < .01f, "自然增高没有更新轮廓边界");
                    AndroidUtilities.density = 2f;
                    var dense = new SettingsActivity.ComputerCell(cell.getContext(), null);
                    factory.bindView(dense, item, false, null, null);
                    dense.measure(View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(320), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    var denseCanvas = outline(dense);
                    check(dense.monitor.getLayoutParams().width == AndroidUtilities.dp(48)
                            && denseCanvas.paths.get(1).strokeWidth == AndroidUtilities.dp(1), "设备图或轮廓未随密度缩放");
                    check(Math.abs(denseCanvas.paths.get(1).bound(0, false) - AndroidUtilities.dp(1) / 2f
                            - AndroidUtilities.dp(16)) < .01f, "高密度外边距未缩放");
                    AndroidUtilities.density = 1f; AndroidUtilities.fontScale = 1f; Theme.styleId = 1;
                }
                static void samplingNextToScale(SettingsActivity.UsageCell cell, int expected, String fetched, boolean stale) {
                    int windows = 0;
                    String sampling = (stale ? "采集时间（已过期）：" : "采集时间：") + fetched;
                    for (int i = 0; i < cell.getChildCount(); i++) {
                        if (!(cell.getChildAt(i) instanceof SettingsActivity.UsageCell.ScaleView)) continue;
                        windows++;
                        check(i + 2 < cell.getChildCount() && cell.getChildAt(i + 1) instanceof TextView
                                && cell.getChildAt(i + 2) instanceof TextView, "刻度旁没有完整的时间信息");
                        TextView reset = (TextView) cell.getChildAt(i + 1), collected = (TextView) cell.getChildAt(i + 2);
                        check(reset.getText().toString().startsWith("重置时间：") && sampling.contentEquals(collected.getText()),
                                "窗口重置或采集时间与刻度分离");
                        check(!reset.singleLine && !collected.singleLine && reset.ellipsize == null && collected.ellipsize == null,
                                "窗口时间被限制行数或省略");
                    }
                    check(windows == expected, "实际窗口被遗漏");
                    check(texts(cell).stream().filter(item -> "采集账号".contentEquals(item.getText())).count() == 1,
                            "采集账号未在尾部只显示一次");
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
                    samplingNextToScale(cell, 2, "示例时刻", false);
                    check(text(cell, "72%").textSize == 32 && text(cell, "72%").unit == android.util.TypedValue.COMPLEX_UNIT_SP,
                            "实际比例的 32sp 字阶被改掉");
                    int one = height(cell);
                    check(scales(cell).get(0).hideFill && scales(cell).get(0).getMeasuredHeight() == 4, "缺失刻度仍有填充或过厚");
                    check(!scales(cell).get(1).hideFill && scales(cell).get(1).percent == 72, "72 没有按真实比例保留");
                    check(Math.abs(SettingsActivity.UsageCell.ScaleView.segmentFill(0, 1) - 0.1f) < 0.001f
                            && Math.abs(SettingsActivity.UsageCell.ScaleView.segmentFill(9, 95) - 0.5f) < 0.001f
                            && SettingsActivity.UsageCell.ScaleView.segmentFill(9, 95) < 1f, "1% 被画空或 95% 被画满");
                    check(text(cell, "72%").color == Theme.key_windowBackgroundWhiteBlueText, "实时百分比不是强调色");
                    check(has(cell, "采集时预计剩余") && has(cell, "重置时间：来源未返回"), "预计或重置说明丢失");
                    usageFactory.bindView(cell, SettingsActivity.UsageCell.Factory.of(state(false, live, live, live)), false, null, null);
                    samplingNextToScale(cell, 3, "示例时刻", false);
                    check(height(cell) > one, "多个窗口没有把卡片撑高");
                    usageFactory.bindView(cell, SettingsActivity.UsageCell.Factory.of(state(true, live)), false, null, null);
                    samplingNextToScale(cell, 1, "示例时刻", true);
                    check(has(cell, "采集时间（已过期）：示例时刻") && text(cell, "72%").color == Theme.key_windowBackgroundWhiteGrayText, "过期额度仍像实时");
                    usageFactory.bindView(cell, SettingsActivity.UsageCell.Factory.of(state(true)), false, null, null);
                    check(scales(cell).isEmpty() && has(cell, "采集时间（已过期）：示例时刻") && has(cell, "示例账号"),
                            "零窗口采集值丢失时间或账号");
                    String longTime = "示例完整本地日期与时间示例完整本地日期与时间示例完整本地日期与时间";
                    var longState = new SettingsActivity.UsageCell.State(List.of(meter("示例窗口", "5 小时", "72%", "采集时剩余", longTime, false, 72)),
                            "示例账号", longTime, false, false, null);
                    usageFactory.bindView(cell, SettingsActivity.UsageCell.Factory.of(longState), false, null, null);
                    AndroidUtilities.fontScale = 1f;
                    int usageCompact = height(cell);
                    AndroidUtilities.fontScale = 2f;
                    samplingNextToScale(cell, 1, longTime, false);
                    check(height(cell) > usageCompact && has(cell, "重置时间：" + longTime)
                            && has(cell, "采集时间：" + longTime), "长时间或大字号被裁切");
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
                    UItem hiddenMeta = SettingsActivity.AccountMetaCell.Factory.of(longAccount, "示例连接", null, null, "示例版本");
                    metaFactory.bindView(metaCell, hiddenMeta, false, null, null);
                    check(!hasVisible(metaCell, "服务地址") && !hasVisible(metaCell, "本地缓存")
                            && !hasVisible(metaCell, "示例地址") && !hasVisible(metaCell, "已读取的聊天记录保存在本机，联网后自动更新"), "空服务地址或缓存说明仍在展示");
                    check(hasVisible(metaCell, longAccount) && hasVisible(metaCell, "示例连接") && hasVisible(metaCell, "示例版本"), "隐藏说明误删账号、连接或版本");
                    check(texts(metaCell).stream().filter(item -> item.getVisibility() == View.GONE).count() == 4
                            && height(metaCell) < grown, "隐藏行仍占用高度");
                    metaFactory.bindView(metaCell, meta, false, null, null);
                    check(hasVisible(metaCell, "服务地址") && hasVisible(metaCell, "示例地址")
                            && hasVisible(metaCell, "本地缓存") && hasVisible(metaCell, "已读取的聊天记录保存在本机，联网后自动更新"), "资料复用没有恢复原说明");
                    check(texts(metaCell).stream().allMatch(item -> item.getVisibility() == View.VISIBLE)
                            && height(metaCell) == grown, "资料复用恢复后高度或可见性不一致");
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
                    computerGeometry(computerCell, computerFactory, computerCopy);
                    check(computerCell.monitor.color == Theme.key_windowBackgroundWhiteBlueText && has(computerCell, "查看项目 →"), "电脑符号不是强调色");
                    System.out.println("PASS SettingsOverviewLayoutTest");
                }
            }
            """;
}
