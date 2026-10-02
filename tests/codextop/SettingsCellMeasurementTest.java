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
import javax.tools.ToolProvider;

/** 提取真实 SettingCell 测量与 Factory。替身只表达行数和 span 是否参与，不代表 Android 字体像素。 */
public final class SettingsCellMeasurementTest {
    /** 编译实际单元格后核对 Codex 行高、省略、复用和原 Factory 契约。 */
    public static void main(String[] args) throws Exception {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        Path source = Path.of(args.length == 0 ? "TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java" : args[0]);
        ClassOrInterfaceDeclaration activity = StaticJavaParser.parse(source).getClassByName("SettingsActivity").orElseThrow();
        ClassOrInterfaceDeclaration cell = activity.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.getNameAsString().equals("SettingCell")).findFirst().orElseThrow();
        String bind = cell.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .filter(method -> method.getNameAsString().equals("bindView")).findFirst().orElseThrow().toString();
        if (bind.contains("setOnClickListener")) throw new AssertionError("绑定改写了原点击路径");
        Path temp = Files.createTempDirectory("codex-settings-cell-measure-");
        try {
            stub(temp, "org/telegram/ui/SettingsActivity.java", """
                    package org.telegram.ui;
                    import static org.telegram.messenger.AndroidUtilities.dp;
                    import android.content.Context;
                    import android.graphics.Canvas;
                    import android.graphics.ColorFilter;
                    import android.graphics.LinearGradient;
                    import android.graphics.Matrix;
                    import android.graphics.Paint;
                    import android.graphics.PixelFormat;
                    import android.graphics.Shader;
                    import android.graphics.drawable.Drawable;
                    import android.text.TextUtils;
                    import android.util.TypedValue;
                    import android.view.Gravity;
                    import android.view.View;
                    import android.view.View.MeasureSpec;
                    import org.telegram.tgnet.TLRPC;
                    import org.telegram.ui.Components.RecyclerListView;
                    import android.widget.FrameLayout;
                    import android.widget.ImageView;
                    import android.widget.LinearLayout;
                    import android.widget.TextView;
                    import androidx.annotation.NonNull;
                    import androidx.annotation.Nullable;
                    import org.telegram.messenger.AndroidUtilities;
                    import org.telegram.messenger.LocaleController;
                    import org.telegram.ui.ActionBar.Theme;
                    import org.telegram.ui.Components.LayoutHelper;
                    import org.telegram.ui.Components.UniversalAdapter;
                    import org.telegram.ui.Components.UniversalRecyclerView;
                    import org.telegram.ui.Components.UItem;
                    public class SettingsActivity {
                    """ + cell + "}");
            writeStubs(temp);
            stub(temp, "org/telegram/ui/SettingsCellMeasurementProbe.java", PROBE);
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
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, command.toArray(String[]::new)) != 0) {
                throw new AssertionError("单元格测量夹具编译失败");
            }
            ArrayList<URL> urls = new ArrayList<>();
            urls.add(temp.toUri().toURL());
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                urls.add(Path.of(entry).toUri().toURL());
            }
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass("org.telegram.ui.SettingsCellMeasurementProbe").getMethod("main", String[].class)
                            .invoke(null, (Object) new String[0]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("单元格测量回归失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    /** 写出测量契约替身。高度只区分一行、两行和 span 是否参与，不模拟真实字体。 */
    private static void writeStubs(Path temp) throws Exception {
        stub(temp, "android/view/View.java", """
                package android.view;
                public class View {
                    public ViewGroup.LayoutParams params;
                    public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
                    public int measuredWidth, measuredHeight, visibility;
                    public OnClickListener click;
                    public void setBackground(android.graphics.drawable.Drawable background) {}
                    public static class MeasureSpec {
                        public static final int UNSPECIFIED = 0, EXACTLY = 1 << 30;
                        public static int makeMeasureSpec(int size, int mode) { return mode | (size & 0x3fffffff); }
                        public static int getMode(int spec) { return spec & 0xc0000000; }
                        public static int getSize(int spec) { return spec & 0x3fffffff; }
                    }
                    public void setLayoutParams(ViewGroup.LayoutParams params) { this.params = params; }
                    public ViewGroup.LayoutParams getLayoutParams() { return params; }
                    public final void measure(int width, int height) { onMeasure(width, height); }
                    protected void onMeasure(int width, int height) { setMeasuredDimension(MeasureSpec.getSize(width), MeasureSpec.getSize(height)); }
                    protected final void setMeasuredDimension(int width, int height) { measuredWidth = width; measuredHeight = height; }
                    public int getMeasuredWidth() { return measuredWidth; }
                    public int getMeasuredHeight() { return measuredHeight; }
                    public void setVisibility(int visibility) { this.visibility = visibility; }
                    public void setOnClickListener(OnClickListener click) { this.click = click; }
                    public interface OnClickListener { void onClick(View view); }
                }
                """);
        stub(temp, "android/view/ViewGroup.java", """
                package android.view;
                import java.util.ArrayList;
                public class ViewGroup extends View {
                    public final ArrayList<View> children = new ArrayList<>();
                    public void addView(View child) { children.add(child); }
                    public void addView(View child, LayoutParams params) { child.setLayoutParams(params); children.add(child); }
                    public int getChildCount() { return children.size(); }
                    public View getChildAt(int index) { return children.get(index); }
                    public static class LayoutParams {
                        public int width, height, leftMargin, topMargin, rightMargin, bottomMargin;
                        public LayoutParams(int width, int height) { this.width = width; this.height = height; }
                    }
                }
                """);
        stub(temp, "android/view/Gravity.java", """
                package android.view;
                public class Gravity {
                    public static final int LEFT = 3, RIGHT = 5, TOP = 48, CENTER = 17, CENTER_VERTICAL = 16, FILL_HORIZONTAL = 7;
                }
                """);
        stub(temp, "android/content/Context.java", "package android.content; public class Context {}\n");
        stub(temp, "android/util/TypedValue.java", """
                package android.util;
                public class TypedValue { public static final int COMPLEX_UNIT_DIP = 1; }
                """);
        stub(temp, "android/widget/TextView.java", """
                package android.widget;
                import android.content.Context;
                import android.text.TextUtils;
                import android.view.View;
                import android.view.View.MeasureSpec;
                public class TextView extends View {
                    public CharSequence text = "";
                    public int maxLines = Integer.MAX_VALUE;
                    public boolean singleLine;
                    public TextUtils.TruncateAt ellipsize;
                    public TextView(Context context) {}
                    public void setText(CharSequence value) { text = value == null ? "" : value; }
                    public CharSequence getText() { return text; }
                    public void setTextSize(float size) {}
                    public void setTextSize(int unit, float size) {}
                    public void setTextColor(int color) {}
                    public void setGravity(int gravity) {}
                    public void setTranslationX(float translation) {}
                    public void setSingleLine(boolean single) { singleLine = single; if (single) maxLines = 1; }
                    public void setMaxLines(int lines) { maxLines = lines; singleLine = lines == 1; }
                    public void setEllipsize(TextUtils.TruncateAt value) { ellipsize = value; }
                    @Override protected void onMeasure(int width, int height) {
                        int lines = 1;
                        if (!singleLine && maxLines > 1 && text.length() > 18) lines = Math.min(maxLines, 2);
                        int extra = 0;
                        if (text instanceof android.text.Spanned spanned) {
                            for (Object span : spanned.getSpans(0, text.length(), Object.class)) {
                                if (span instanceof android.text.style.RelativeSizeSpan) extra = 16;
                            }
                        }
                        setMeasuredDimension(MeasureSpec.getSize(width), 24 * lines + extra);
                    }
                }
                """);
        stub(temp, "android/widget/ImageView.java", """
                package android.widget;
                import android.content.Context;
                import android.view.View;
                public class ImageView extends View {
                    public ImageView(Context context) {}
                    public void setScaleType(ScaleType type) {}
                    public void setImageResource(int resource) {}
                    public enum ScaleType { FIT_CENTER }
                }
                """);
        stub(temp, "android/widget/FrameLayout.java", """
                package android.widget;
                import android.content.Context;
                import android.view.ViewGroup;
                public class FrameLayout extends ViewGroup {
                    public FrameLayout(Context context) {}
                    public static class LayoutParams extends ViewGroup.LayoutParams {
                        public int gravity;
                        public LayoutParams(int width, int height) { super(width, height); }
                        public LayoutParams(int width, int height, int gravity) { super(width, height); this.gravity = gravity; }
                    }
                }
                """);
        stub(temp, "android/widget/LinearLayout.java", """
                package android.widget;
                import android.content.Context;
                import android.view.View;
                import android.view.View.MeasureSpec;
                import android.view.ViewGroup;
                public class LinearLayout extends ViewGroup {
                    public static final int HORIZONTAL = 0, VERTICAL = 1;
                    public int orientation = HORIZONTAL;
                    public LinearLayout(Context context) {}
                    public void setOrientation(int orientation) { this.orientation = orientation; }
                    public static class LayoutParams extends ViewGroup.LayoutParams {
                        public int gravity;
                        public float weight;
                        public LayoutParams(int width, int height) { super(width, height); }
                        public LayoutParams(int width, int height, float weight) { super(width, height); this.weight = weight; }
                    }
                    @Override protected void onMeasure(int widthSpec, int heightSpec) {
                        int width = MeasureSpec.getSize(widthSpec);
                        int mode = MeasureSpec.getMode(heightSpec);
                        int exact = MeasureSpec.getSize(heightSpec);
                        int total = 0, tallest = 0;
                        for (View child : children) {
                            int vertical = 0;
                            if (child.getLayoutParams() instanceof LayoutParams params) vertical = params.topMargin + params.bottomMargin;
                            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                            total += child.getMeasuredHeight() + vertical;
                            tallest = Math.max(tallest, child.getMeasuredHeight());
                        }
                        setMeasuredDimension(width, mode == MeasureSpec.EXACTLY ? exact : (orientation == VERTICAL ? total : tallest));
                    }
                }
                """);
        stub(temp, "android/text/TextUtils.java", """
                package android.text;
                public class TextUtils {
                    public enum TruncateAt { START, MIDDLE, END, MARQUEE }
                    public static boolean isEmpty(CharSequence value) { return value == null || value.length() == 0; }
                    public static boolean equals(CharSequence left, CharSequence right) {
                        if (left == right) return true;
                        if (left == null || right == null) return false;
                        return left.toString().equals(right.toString());
                    }
                }
                """);
        stub(temp, "android/text/Spanned.java", """
                package android.text;
                public interface Spanned extends CharSequence {
                    int getSpanStart(Object span);
                    int getSpanEnd(Object span);
                    int getSpanFlags(Object span);
                    <T> T[] getSpans(int start, int end, Class<T> type);
                }
                """);
        stub(temp, "android/text/Spannable.java", """
                package android.text;
                public interface Spannable extends Spanned {
                    void setSpan(Object span, int start, int end, int flags);
                    void removeSpan(Object span);
                }
                """);
        stub(temp, "android/text/SpannableStringBuilder.java", """
                package android.text;
                import java.util.ArrayList;
                public class SpannableStringBuilder implements Spannable {
                    private final StringBuilder text;
                    private final ArrayList<Mark> marks = new ArrayList<>();
                    public SpannableStringBuilder(CharSequence value) { text = new StringBuilder(value == null ? "" : value.toString()); }
                    public SpannableStringBuilder append(CharSequence value) { text.append(value); return this; }
                    public int length() { return text.length(); }
                    public char charAt(int index) { return text.charAt(index); }
                    public CharSequence subSequence(int start, int end) { return text.subSequence(start, end); }
                    public String toString() { return text.toString(); }
                    public void setSpan(Object span, int start, int end, int flags) { marks.add(new Mark(span, start, end, flags)); }
                    public void removeSpan(Object span) { marks.removeIf(mark -> mark.span == span); }
                    public int getSpanStart(Object span) { for (Mark mark : marks) if (mark.span == span) return mark.start; return -1; }
                    public int getSpanEnd(Object span) { for (Mark mark : marks) if (mark.span == span) return mark.end; return -1; }
                    public int getSpanFlags(Object span) { for (Mark mark : marks) if (mark.span == span) return mark.flags; return 0; }
                    @SuppressWarnings("unchecked")
                    public <T> T[] getSpans(int start, int end, Class<T> type) {
                        ArrayList<T> found = new ArrayList<>();
                        for (Mark mark : marks) if (type.isInstance(mark.span) && mark.start < end && mark.end > start) found.add(type.cast(mark.span));
                        return found.toArray((T[]) java.lang.reflect.Array.newInstance(type, found.size()));
                    }
                    static final class Mark {
                        final Object span; final int start, end, flags;
                        Mark(Object span, int start, int end, int flags) { this.span = span; this.start = start; this.end = end; this.flags = flags; }
                    }
                }
                """);
        stub(temp, "android/text/style/RelativeSizeSpan.java", """
                package android.text.style;
                public class RelativeSizeSpan {
                    public final float proportion;
                    public RelativeSizeSpan(float proportion) { this.proportion = proportion; }
                }
                """);
        stub(temp, "android/text/style/StyleSpan.java", """
                package android.text.style;
                public class StyleSpan { public final int style; public StyleSpan(int style) { this.style = style; } }
                """);
        stub(temp, "android/graphics/Paint.java", """
                package android.graphics;
                public class Paint {
                    public static final int ANTI_ALIAS_FLAG = 1;
                    public Paint() {}
                    public Paint(int flags) {}
                    public void setStyle(Style style) {}
                    public void setShader(Shader shader) {}
                    public void setStrokeWidth(float width) {}
                    public enum Style { FILL, STROKE }
                }
                """);
        stub(temp, "android/graphics/Shader.java", "package android.graphics; public class Shader { public enum TileMode { CLAMP, REPEAT, MIRROR } }\n");
        stub(temp, "android/graphics/LinearGradient.java", """
                package android.graphics;
                public class LinearGradient extends Shader {
                    public LinearGradient(float x0, float y0, float x1, float y1, int[] colors, float[] positions, Shader.TileMode tile) {}
                }
                """);
        stub(temp, "android/graphics/Matrix.java", "package android.graphics; public class Matrix { public void reset() {} public void postTranslate(float x, float y) {} }\n");
        stub(temp, "android/graphics/ColorFilter.java", "package android.graphics; public class ColorFilter {}\n");
        stub(temp, "android/graphics/PixelFormat.java", "package android.graphics; public class PixelFormat { public static final int TRANSPARENT = -2; }\n");
        stub(temp, "android/graphics/Canvas.java", "package android.graphics; public class Canvas { public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {} }\n");
        stub(temp, "android/graphics/Rect.java", "package android.graphics; public class Rect {}\n");
        stub(temp, "android/graphics/RectF.java", "package android.graphics; public class RectF { public float left, top; public void set(Rect rect) {} public void inset(float dx, float dy) {} }\n");
        stub(temp, "android/graphics/drawable/Drawable.java", """
                package android.graphics.drawable;
                import android.graphics.Canvas;
                import android.graphics.ColorFilter;
                import android.graphics.Rect;
                public abstract class Drawable {
                    public abstract void draw(Canvas canvas);
                    public abstract void setAlpha(int alpha);
                    public abstract void setColorFilter(ColorFilter filter);
                    public abstract int getOpacity();
                    public final Rect getBounds() { return new Rect(); }
                }
                """);
        stub(temp, "androidx/annotation/NonNull.java", """
                package androidx.annotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Retention(RetentionPolicy.CLASS) @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD}) public @interface NonNull {}
                """);
        stub(temp, "androidx/annotation/Nullable.java", """
                package androidx.annotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Retention(RetentionPolicy.CLASS) @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD}) public @interface Nullable {}
                """);
        stub(temp, "org/telegram/messenger/AndroidUtilities.java", """
                package org.telegram.messenger;
                import android.graphics.RectF;
                public final class AndroidUtilities {
                    public static float density = 1f;
                    public static final RectF rectTmp = new RectF();
                    public static int dp(float value) { return (int) Math.ceil(density * value); }
                }
                """);
        stub(temp, "org/telegram/messenger/LocaleController.java", """
                package org.telegram.messenger;
                public final class LocaleController { public static boolean isRTL; }
                """);
        stub(temp, "org/telegram/ui/ActionBar/Theme.java", """
                package org.telegram.ui.ActionBar;
                public final class Theme {
                    public static final int key_windowBackgroundWhiteBlackText = 1;
                    public static final int key_windowBackgroundWhiteGrayText = 2;
                    public static final int key_windowBackgroundWhiteBlueText = 3;
                    public static int getColor(int key, ResourcesProvider provider) { return key; }
                    public static boolean isCurrentThemeDark() { return false; }
                    public interface ResourcesProvider { boolean isDark(); }
                    public interface Colorable { void updateColors(); }
                }
                """);
        stub(temp, "org/telegram/ui/Components/LayoutHelper.java", """
                package org.telegram.ui.Components;
                import android.widget.FrameLayout;
                import android.widget.LinearLayout;
                public final class LayoutHelper {
                    public static final int MATCH_PARENT = -1, WRAP_CONTENT = -2;
                    public static FrameLayout.LayoutParams createFrame(int width, int height, int gravity) {
                        return new FrameLayout.LayoutParams(width, height, gravity);
                    }
                    public static LinearLayout.LayoutParams createLinear(int width, int height, float left, float top, float right, float bottom) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
                        params.leftMargin = (int) left; params.topMargin = (int) top; params.rightMargin = (int) right; params.bottomMargin = (int) bottom;
                        return params;
                    }
                    public static LinearLayout.LayoutParams createLinear(int width, int height, int gravity, int left, int top, int right, int bottom) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
                        params.gravity = gravity; params.leftMargin = left; params.topMargin = top; params.rightMargin = right; params.bottomMargin = bottom;
                        return params;
                    }
                    public static LinearLayout.LayoutParams createLinear(int width, int height, float weight, int gravity, int left, int top, int right, int bottom) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
                        params.gravity = gravity; params.leftMargin = left; params.topMargin = top; params.rightMargin = right; params.bottomMargin = bottom;
                        return params;
                    }
                }
                """);
        stub(temp, "org/telegram/ui/Components/UItem.java", """
                package org.telegram.ui.Components;
                import android.view.View;
                public class UItem {
                    public int id, iconResId, flags, viewType;
                    public long longValue;
                    public CharSequence text, subtext, textValue;
                    public Object object, object2;
                    public View.OnClickListener clickCallback;
                    public boolean enabled = true;
                    public UItem(int viewType, boolean selectable) { this.viewType = viewType; }
                    public UItem setEnabled(boolean value) { enabled = value; return this; }
                    public static UItem ofFactory(Class<?> type) { return new UItem(1, false); }
                    public static abstract class UItemFactory<V extends View> {
                        public final int viewType = 1;
                        public static void setup(UItemFactory<?> factory) {}
                        public UItemFactory() {}
                        public V createView(android.content.Context context, RecyclerListView listView, int currentAccount, int classGuid, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider) { return null; }
                        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {}
                        public boolean equals(UItem left, UItem right) { return false; }
                        public boolean contentsEquals(UItem left, UItem right) { return false; }
                    }
                }
                """);
        stub(temp, "org/telegram/ui/Components/RecyclerListView.java", "package org.telegram.ui.Components; public class RecyclerListView {}\n");
        stub(temp, "org/telegram/ui/Components/UniversalAdapter.java", "package org.telegram.ui.Components; public class UniversalAdapter {}\n");
        stub(temp, "org/telegram/ui/Components/UniversalRecyclerView.java", "package org.telegram.ui.Components; public class UniversalRecyclerView {}\n");
        stub(temp, "org/telegram/tgnet/TLRPC.java", """
                package org.telegram.tgnet;
                public final class TLRPC { public static class TL_attachMenuBot { public long bot_id; public String short_name; } }
                """);
    }

    private static void stub(Path temp, String name, String source) throws Exception {
        Path file = temp.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static final String PROBE = """
            package org.telegram.ui;
            import android.text.SpannableStringBuilder;
            import android.text.TextUtils;
            import android.text.style.RelativeSizeSpan;
            import android.view.View.MeasureSpec;
            import android.widget.TextView;
            import com.google.gson.JsonObject;
            import org.telegram.messenger.AndroidUtilities;
            import org.telegram.messenger.LocaleController;
            import org.telegram.ui.Components.UItem;
            public final class SettingsCellMeasurementProbe {
                static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
                static TextView text(SettingsActivity.SettingCell cell, String name) throws Exception {
                    var field = SettingsActivity.SettingCell.class.getDeclaredField(name);
                    field.setAccessible(true);
                    return (TextView) field.get(cell);
                }
                static int height(SettingsActivity.SettingCell cell) {
                    cell.measure(MeasureSpec.makeMeasureSpec(320, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    return cell.getMeasuredHeight();
                }
                static boolean span(TextView view) {
                    if (!(view.getText() instanceof android.text.Spanned spanned)) return false;
                    RelativeSizeSpan[] spans = spanned.getSpans(0, spanned.length(), RelativeSizeSpan.class);
                    return spans.length == 1 && spans[0].proportion == 1.375f;
                }
                public static void main(String[] args) throws Exception {
                    var factory = new SettingsActivity.SettingCell.Factory();
                    UItem original = SettingsActivity.SettingCell.Factory.of(25, 0, 0, 0, "来源电脑", "合成电脑");
                    check(original.textValue == null && "合成电脑".contentEquals(original.subtext) && original.flags == 0 && original.clickCallback == null,
                            "原六参 of 不再保持 value=null 或写了 Codex 标记");
                    String reason = "暂时无法读取额度，请检查电脑连接后重试";
                    UItem quota = SettingsActivity.SettingCell.Factory.ofCodex(40, 0, 0, 0, quotaTitle(), "窗口 · 5 小时");
                    UItem refresh = SettingsActivity.SettingCell.Factory.ofCodex(33, 0, 0, 0, "刷新额度", reason, SettingsActivity.SettingCell.Factory.CODEX_SUBTITLE_TWO);
                    UItem cache = SettingsActivity.SettingCell.Factory.ofCodex(23, 0, 0, 0, "本地缓存", "已读取的聊天记录保存在本机，联网后自动更新", SettingsActivity.SettingCell.Factory.CODEX_SUBTITLE_TWO);
                    UItem failure = SettingsActivity.SettingCell.Factory.ofCodex(9, 1, 2, 0, reason, "点击刷新", SettingsActivity.SettingCell.Factory.CODEX_TITLE_TWO);
                    check(quota.textValue == null && refresh.id == 33 && cache.id == 23 && failure.id == 9, "Codex 包装改了 id 或 value");
                    check((quota.flags & SettingsActivity.SettingCell.Factory.CODEX_ROW) != 0, "额度行没有 Codex 标记");
                    JsonObject row = new JsonObject();
                    row.addProperty("name", "合成电脑名称");
                    String longName = "合成电脑名称合成电脑名称合成电脑名称合成电脑名称";
                    UItem browse = SettingsActivity.SettingCell.Factory.ofBrowse("computers:synthetic", row, 1, 2, 0, longName, "在线");
                    UItem browseAgain = SettingsActivity.SettingCell.Factory.ofBrowse("computers:synthetic", row, 1, 2, 0, "另一个名字", "离线");
                    UItem other = SettingsActivity.SettingCell.Factory.ofBrowse("computers:other", new JsonObject(), 1, 2, 0, longName, "在线");
                    check(browse.id == 1000 && "computers:synthetic".equals(browse.object2) && browse.object == row && browse.textValue == null && browse.clickCallback == null,
                            "浏览行丢失稳定身份、负载或改了点击");
                    check(factory.equals(browse, browseAgain) && !factory.equals(browse, other), "浏览身份不再只看稳定键");
                    check(!factory.contentsEquals(browse, browseAgain), "浏览内容变化被判成同一内容");
                    var context = new android.content.Context();
                    LocaleController.isRTL = false;
                    var ltr = new SettingsActivity.SettingCell(context, null);
                    check(ltr.getChildAt(0) instanceof android.widget.FrameLayout && ltr.getChildAt(2) instanceof TextView, "LTR 子视图顺序被改");
                    LocaleController.isRTL = true;
                    var rtl = new SettingsActivity.SettingCell(context, null);
                    check(rtl.getChildAt(0) instanceof TextView && rtl.getChildAt(2) instanceof android.widget.FrameLayout, "RTL 子视图顺序被改");
                    LocaleController.isRTL = false;
                    var mini = new SettingsActivity.SettingCell(context, null, true);
                    factory.bindView(mini, original, false, null, null);
                    check(height(mini) == AndroidUtilities.dp(44), "mini 行高不再是 44dp");
                    var normal = new SettingsActivity.SettingCell(context, null);
                    factory.bindView(normal, original, false, null, null);
                    text(normal, "subtitleView").setText(reason);
                    check(height(normal) == AndroidUtilities.dp(60), "普通双行被 Codex 高度影响");
                    check(text(normal, "titleView").maxLines == Integer.MAX_VALUE && text(normal, "subtitleView").ellipsize == null, "普通行被加上省略");
                    var codex = new SettingsActivity.SettingCell(context, null);
                    factory.bindView(codex, refresh, false, null, null);
                    check(height(codex) > AndroidUtilities.dp(60), "两行错误说明仍被固定在 60dp");
                    check(text(codex, "subtitleView").maxLines == 2 && text(codex, "subtitleView").ellipsize == TextUtils.TruncateAt.END, "刷新说明没有保留两行省略");
                    check(text(codex, "titleView").maxLines == 1, "刷新标题被放成两行");
                    factory.bindView(codex, cache, false, null, null);
                    check(text(codex, "subtitleView").maxLines == 2 && height(codex) > AndroidUtilities.dp(60), "缓存说明不能显示两行");
                    factory.bindView(codex, failure, false, null, null);
                    check(text(codex, "titleView").maxLines == 2 && text(codex, "subtitleView").maxLines == 1, "浏览失败没有把原因留在标题两行");
                    factory.bindView(codex, quota, false, null, null);
                    int withSpan = height(codex);
                    check(span(text(codex, "titleView")), "额度比例的 RelativeSizeSpan 被去掉");
                    UItem plain = SettingsActivity.SettingCell.Factory.ofCodex(40, 0, 0, 0, "12.5%  采集时剩余", "窗口 · 5 小时");
                    factory.bindView(codex, plain, false, null, null);
                    check(withSpan > height(codex), "额度 span 没有参与高度");
                    factory.bindView(codex, browse, false, null, null);
                    check(text(codex, "titleView").maxLines == 1 && text(codex, "subtitleView").maxLines == 1, "长电脑名没有收成一行");
                    check(height(codex) == AndroidUtilities.dp(60), "一行 Codex 行高低于原来的 60dp");
                    factory.bindView(codex, original, false, null, null);
                    check(text(codex, "titleView").maxLines == Integer.MAX_VALUE && text(codex, "subtitleView").ellipsize == null, "复用单元格没有恢复普通模式");
                    check(codex.click == null, "绑定新增了点击");
                    System.out.println("PASS SettingsCellMeasurementTest");
                }
                static CharSequence quotaTitle() {
                    String value = "12.5%";
                    SpannableStringBuilder text = new SpannableStringBuilder(value).append("  采集时剩余");
                    text.setSpan(new RelativeSizeSpan(1.375f), 0, value.length(), 33);
                    return text;
                }
            }
            """;
}
