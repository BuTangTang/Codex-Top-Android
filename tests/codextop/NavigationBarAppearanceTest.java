package com.butang.codextop;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;

/** 编译真实导航栏外观方法，用平台替身核对 Codex 在 API 35 上的读写是否一致。 */
public final class NavigationBarAppearanceTest {
    /** 提取原方法并在临时目录执行；不启动界面，也不证明真机像素。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        String source = Files.readString(root.resolve(
                "TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java"));
        String methods = between(source, "    public static boolean getLightNavigationBar(Window window) {",
                "    private static HashMap<Window, ValueAnimator> navigationBarColorAnimators;");
        Path temp = Files.createTempDirectory("codex-nav-appearance-");
        try {
            Files.writeString(temp.resolve("NonNull.java"), """
                    package androidx.annotation;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    @Retention(RetentionPolicy.CLASS)
                    public @interface NonNull {}
                    """);
            Files.writeString(temp.resolve("Build.java"), """
                    package android.os;
                    public final class Build {
                        private Build() {}
                        public static final class VERSION {
                            public static int SDK_INT;
                        }
                        public static final class VERSION_CODES {
                            public static final int O = 26;
                            public static final int VANILLA_ICE_CREAM = 35;
                        }
                    }
                    """);
            Files.writeString(temp.resolve("WindowInsetsController.java"), """
                    package android.view;
                    public interface WindowInsetsController {
                        int APPEARANCE_OPAQUE_STATUS_BARS = 1;
                        int APPEARANCE_LIGHT_STATUS_BARS = 8;
                        int APPEARANCE_LIGHT_NAVIGATION_BARS = 16;
                        void setSystemBarsAppearance(int appearance, int mask);
                        int getSystemBarsAppearance();
                    }
                    """);
            Files.writeString(temp.resolve("FakeInsetsController.java"), """
                    package android.view;
                    public final class FakeInsetsController implements WindowInsetsController {
                        public int appearance;
                        public int lastMask = -1;
                        public int calls;
                        public void setSystemBarsAppearance(int appearance, int mask) {
                            calls++;
                            lastMask = mask;
                            this.appearance = (this.appearance & ~mask) | (appearance & mask);
                        }
                        public int getSystemBarsAppearance() { return appearance; }
                    }
                    """);
            Files.writeString(temp.resolve("View.java"), """
                    package android.view;
                    public class View {
                        public static final int SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR = 16;
                        public static final int SYSTEM_UI_FLAG_LIGHT_STATUS_BAR = 0x2000;
                        public static final int SYSTEM_UI_FLAG_LOW_PROFILE = 1;
                        public int visibility;
                        public WindowInsetsController controller;
                        public int getSystemUiVisibility() { return visibility; }
                        public void setSystemUiVisibility(int visibility) { this.visibility = visibility; }
                        public WindowInsetsController getWindowInsetsController() { return controller; }
                    }
                    """);
            Files.writeString(temp.resolve("Window.java"), """
                    package android.view;
                    public class Window {
                        private final View decor;
                        public Window(View decor) { this.decor = decor; }
                        public View getDecorView() { return decor; }
                    }
                    """);
            Files.writeString(temp.resolve("Dialog.java"), """
                    package android.app;
                    import android.view.Window;
                    public class Dialog {
                        private final Window window;
                        public Dialog(Window window) { this.window = window; }
                        public Window getWindow() { return window; }
                    }
                    """);
            Files.writeString(temp.resolve("Activity.java"), """
                    package android.app;
                    import android.view.Window;
                    public class Activity {
                        private final Window window;
                        public Activity(Window window) { this.window = window; }
                        public Window getWindow() { return window; }
                    }
                    """);
            Files.writeString(temp.resolve("CodexRuntime.java"), """
                    package com.butang.codextop;
                    public final class CodexRuntime {
                        public static boolean on;
                        private CodexRuntime() {}
                        public static boolean enabled() { return on; }
                    }
                    """);
            Files.writeString(temp.resolve("AndroidUtilities.java"), """
                    package org.telegram.messenger;
                    import android.app.Activity;
                    import android.app.Dialog;
                    import android.os.Build;
                    import android.view.View;
                    import android.view.Window;
                    import android.view.WindowInsetsController;
                    import androidx.annotation.NonNull;
                    import me.vkryl.core.BitwiseUtils;
                    public final class AndroidUtilities {
                        private AndroidUtilities() {}
                    """ + methods + "\n}\n");
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", temp.toString(),
                    temp.resolve("NonNull.java").toString(),
                    temp.resolve("Build.java").toString(),
                    temp.resolve("WindowInsetsController.java").toString(),
                    temp.resolve("FakeInsetsController.java").toString(),
                    temp.resolve("View.java").toString(),
                    temp.resolve("Window.java").toString(),
                    temp.resolve("Dialog.java").toString(),
                    temp.resolve("Activity.java").toString(),
                    temp.resolve("CodexRuntime.java").toString(),
                    root.resolve("TMessagesProj/src/main/java/me/vkryl/core/BitwiseUtils.java").toString(),
                    temp.resolve("AndroidUtilities.java").toString()) != 0) {
                throw new AssertionError("导航栏外观夹具编译失败");
            }
            Files.writeString(temp.resolve("NavigationBarAppearanceCases.java"), CASES);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", temp.toString(),
                    "-d", temp.toString(), temp.resolve("NavigationBarAppearanceCases.java").toString()) != 0) {
                throw new AssertionError("导航栏外观用例编译失败");
            }
            var classpath = new java.util.ArrayList<URL>();
            classpath.add(temp.toUri().toURL());
            try (var loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass("com.butang.codextop.NavigationBarAppearanceCases")
                            .getMethod("main", String[].class).invoke(null, (Object) new String[0]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    throw new AssertionError("导航栏外观回归失败", error.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    /** 取原方法区间；后面的颜色动画不属于这次外观读写。 */
    private static String between(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = first < 0 ? -1 : source.indexOf(end, first + start.length());
        if (first < 0 || last < 0) throw new AssertionError("缺少导航栏外观方法");
        return source.substring(first, last);
    }

    private static final String CASES = """
            package com.butang.codextop;
            import android.app.Activity;
            import android.app.Dialog;
            import android.os.Build;
            import android.view.FakeInsetsController;
            import android.view.View;
            import android.view.Window;
            import android.view.WindowInsetsController;
            import org.telegram.messenger.AndroidUtilities;
            public final class NavigationBarAppearanceCases {
                static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
                static void sdk(int value) { Build.VERSION.SDK_INT = value; }
                static View attached() {
                    View view = new View();
                    view.visibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LOW_PROFILE;
                    FakeInsetsController controller = new FakeInsetsController();
                    controller.appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;
                    view.controller = controller;
                    return view;
                }
                static FakeInsetsController controller(View view) { return (FakeInsetsController) view.controller; }
                public static void main(String[] args) {
                    sdk(25);
                    CodexRuntime.on = true;
                    View old = attached();
                    int before = old.visibility;
                    AndroidUtilities.setLightNavigationBar(old, true);
                    check(old.visibility == before, "API 25 改了系统标志");
                    check(controller(old).calls == 0, "API 25 调用了新外观接口");
                    check(!AndroidUtilities.getLightNavigationBar(new Window(old)), "API 25 读成了浅色导航栏");

                    for (int api : new int[] {26, 34}) {
                        sdk(api);
                        CodexRuntime.on = true;
                        View view = attached();
                        AndroidUtilities.setLightNavigationBar(view, true);
                        check(controller(view).calls == 0, "API " + api + " 提前走了新接口");
                        check((view.visibility & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0, "API " + api + " 没有留下原标志");
                        check((view.visibility & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0, "API " + api + " 清掉了状态栏标志");
                        check((view.visibility & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0, "API " + api + " 清掉了其他标志");
                        check(AndroidUtilities.getLightNavigationBar(new Window(view)), "API " + api + " 读不到刚写下的原标志");
                        AndroidUtilities.setLightNavigationBar(view, false);
                        check((view.visibility & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) == 0, "API " + api + " 没有清除原标志");
                        check((view.visibility & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0, "API " + api + " 关闭时清掉了状态栏标志");
                    }

                    sdk(35);
                    CodexRuntime.on = false;
                    View plain = attached();
                    AndroidUtilities.setLightNavigationBar(plain, true);
                    check(controller(plain).calls == 0, "非 Codex 在 API 35 改了新外观");
                    check((plain.visibility & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0, "非 Codex 没有走原标志");
                    check(AndroidUtilities.getLightNavigationBar(new Window(plain)), "非 Codex 没有从原标志读回");

                    sdk(35);
                    CodexRuntime.on = true;
                    View detached = attached();
                    detached.controller = null;
                    AndroidUtilities.setLightNavigationBar(detached, true);
                    check((detached.visibility & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0, "未挂上控制器时没有退回原标志");
                    check(AndroidUtilities.getLightNavigationBar(new Window(detached)), "未挂上控制器时没有从原标志读回");

                    sdk(35);
                    CodexRuntime.on = true;
                    View view = attached();
                    int frozen = view.visibility;
                    Window window = new Window(view);
                    Dialog dialog = new Dialog(window);
                    AndroidUtilities.setLightNavigationBar(dialog, true);
                    FakeInsetsController modern = controller(view);
                    check(modern.calls == 1, "API 35 Codex 没有写入导航栏外观");
                    check(modern.lastMask == WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, "外观掩码包含了导航栏以外的位");
                    check((modern.appearance & WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) != 0, "没有置上浅色导航栏这一位");
                    check((modern.appearance & WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0, "写入导航栏时清掉了状态栏外观");
                    check((modern.appearance & WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS) != 0, "写入导航栏时清掉了其他外观");
                    check(view.visibility == frozen, "新路径仍改了旧的系统标志");
                    check(AndroidUtilities.getLightNavigationBar(window), "读回没有使用新外观");
                    boolean saved = AndroidUtilities.getLightNavigationBar(window);
                    AndroidUtilities.setLightNavigationBar(new Activity(window), false);
                    check(!AndroidUtilities.getLightNavigationBar(window), "关闭后仍读成浅色导航栏");
                    check((modern.appearance & WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) == 0, "关闭没有清掉导航栏这一位");
                    check((modern.appearance & WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0, "关闭导航栏时清掉了状态栏外观");
                    check(view.visibility == frozen, "关闭时改了旧的系统标志");
                    AndroidUtilities.setLightNavigationBar(dialog, saved);
                    check(AndroidUtilities.getLightNavigationBar(window) == saved, "对话框保存后没有按原值恢复");
                    check(modern.lastMask == WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, "恢复时掩码不再只含导航栏");
                    AndroidUtilities.setLightNavigationBar((View) null, true);
                    AndroidUtilities.setLightNavigationBar((Activity) null, true);
                    AndroidUtilities.setLightNavigationBar(new Dialog(null), true);
                    check(!AndroidUtilities.getLightNavigationBar(null), "空窗口没有按未设置返回");
                    check(!AndroidUtilities.getLightNavigationBar(new Window(null)), "空装饰视图没有按未设置返回");
                    System.out.println("PASS NavigationBarAppearanceTest");
                }
            }
            """;
}
