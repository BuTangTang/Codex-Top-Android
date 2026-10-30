package com.butang.codextop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/** 产品更新的唯一状态owner；有限前台IO不使用账号、聊天队列或常驻轮询。 */
public final class CodexAppUpdater {
    public enum State { UNAVAILABLE, IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, PAUSED, READY, ERROR }
    public static final class Snapshot {
        public final State state;
        public final String title, detail, versionName;
        public final long versionCode;
        public final float progress;
        /** 向原设置列表发布不可变状态，不暴露本地文件或会话信息。 */
        private Snapshot(State state, String title, String detail, UpdateManifest manifest, float progress) {
            this.state = state; this.title = title; this.detail = detail;
            this.versionName = manifest == null ? null : manifest.versionName;
            this.versionCode = manifest == null ? 0 : manifest.versionCode; this.progress = progress;
        }
    }
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final long FAILURE_INTERVAL_MS = 15L * 60 * 1000;
    private static volatile CodexAppUpdater instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final File directory, partial, ready;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = new Snapshot(State.UNAVAILABLE, "应用更新", "更新暂不可用", null, 0);
    private volatile String manifestUrl = "";
    private volatile UpdateManifest manifest;
    private volatile boolean foreground;
    private volatile long generation;
    private volatile HttpURLConnection activeConnection;
    private boolean busy, resumeAfterTask;

    /** 只初始化自身小型偏好与路径，实际磁盘/网络工作在按需任务中进行。 */
    private CodexAppUpdater(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("codextop_app_update", Context.MODE_PRIVATE);
        directory = new File(this.context.getFilesDir(), "cache/codex-updates");
        partial = new File(directory, "download.part"); ready = new File(directory, "ready.apk");
    }

    /** 应用进程内只有一个更新owner，与登录账号完全无关。 */
    public static CodexAppUpdater getInstance() {
        if (instance == null) synchronized (CodexAppUpdater.class) {
            if (instance == null) instance = new CodexAppUpdater(ApplicationLoader.applicationContext);
        }
        return instance;
    }
    /** 原设置页订阅UI线程通知，销毁时移除同一个回调。 */
    public void addListener(Runnable listener) { if (listener != null) listeners.addIfAbsent(listener); }
    /** 原设置页离开时解绑，不维持页面或Activity引用。 */
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    /** 无IO读取当前状态，聊天页面不必等待更新器。 */
    public Snapshot getSnapshot() { return snapshot; }

    /** 首开或回到前台静默检查；URL由产品构建注入，空配置绝不拼接线上地址。 */
    public void onForeground(String configuredUrl) {
        String value = configuredUrl == null ? "" : configuredUrl.trim();
        synchronized (this) {
            boolean returning = !foreground; foreground = true;
            boolean changed = !value.equals(manifestUrl);
            if (!value.equals(manifestUrl)) {
                generation++; manifestUrl = value; manifest = null;
                HttpURLConnection connection = activeConnection; if (connection != null) connection.disconnect();
            }
            if (busy) { if (returning || changed) resumeAfterTask = true; return; }
        }
        if (value.isEmpty()) { publish(generation, State.UNAVAILABLE, "更新暂不可用", 0); return; }
        startCheck(false);
    }

    /** 后台只暂停本次下载并保留已落盘part，不继续耗流量或影响消息连接。 */
    public void onBackground() {
        final long serial;
        synchronized (this) { foreground = false; serial = ++generation; }
        HttpURLConnection connection = activeConnection; if (connection != null) connection.disconnect();
        if (snapshot.state == State.DOWNLOADING) publish(serial, State.PAUSED, "下载已暂停，回到前台后继续", snapshot.progress);
    }
    /** 用户点击原更新行时强制检查，仍受同一在途门约束。 */
    public void checkNow() { startCheck(true); }
    /** 用户明确点击下载可用当前网络；自动下载仍只允许非计费Wi-Fi。 */
    public void downloadNow() {
        if (manifest == null) { checkNow(); return; }
        final long serial = generation;
        runTask(() -> { try { download(serial, true); } catch (Exception error) { failed(serial, error); } });
    }

    /** 用户点击才进入安装；未知来源授权及安装确认均由Android系统承担。 */
    public void install(Activity activity) {
        final long serial = generation; final UpdateManifest target = manifest;
        if (target == null || snapshot.state != State.READY || activity == null) return;
        runTask(() -> {
            try {
                ensureActive(serial); target.verifyFile(ready); verifyArchive(target, ready);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!active(serial) || manifest != target || activity.isFinishing()
                            || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
                    try {
                        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + context.getPackageName())));
                            publish(serial, State.READY, "允许安装后，点此继续", 1); return;
                        }
                        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", ready);
                        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        activity.startActivity(intent);
                        publish(serial, State.READY, "请在系统界面确认安装", 1);
                    } catch (Exception error) { publish(serial, State.READY, "无法打开安装界面，点此重试", 1); }
                });
            } catch (Exception error) { failed(serial, error); }
        });
    }

    /** 唯一按需任务入口，任务结束线程退出；恢复前台发生在取消收尾期间时只补一次检查。 */
    private void runTask(Runnable work) {
        synchronized (this) { if (busy || !foreground || manifestUrl.isEmpty()) return; busy = true; }
        new Thread(() -> {
            try { work.run(); }
            finally {
                activeConnection = null;
                final boolean resume;
                synchronized (this) { busy = false; resume = resumeAfterTask && foreground; resumeAfterTask = false; }
                if (resume) AndroidUtilities.runOnUIThread(() -> onForeground(manifestUrl));
            }
        }, "CodexAppUpdate").start();
    }

    /** 已校验清单可冷恢复；成功24小时、失败15分钟节流，手动检查绕过时间但不绕过在途门。 */
    private void startCheck(boolean force) {
        final long serial = generation; final String source = manifestUrl;
        if (source.isEmpty()) { publish(serial, State.UNAVAILABLE, "更新暂不可用", 0); return; }
        runTask(() -> {
            try {
                ensureActive(serial); prepareDirectory();
                if (manifest == null) restoreManifest(source, serial);
                long now = System.currentTimeMillis();
                if (!force && recent(now, preferences.getLong("lastSuccess", 0), CHECK_INTERVAL_MS)
                        && source.equals(preferences.getString("source", ""))) {
                    if (snapshot.state != State.READY) showAvailable(serial); return;
                }
                if (!force && recent(now, preferences.getLong("lastFailure", 0), FAILURE_INTERVAL_MS)
                        && source.equals(preferences.getString("failureSource", ""))) {
                    if (manifest != null) showAvailable(serial); else publish(serial, State.ERROR, "检查失败，点此重试", 0);
                    return;
                }
                publish(serial, State.CHECKING, "正在检查", 0);
                String cached = preferences.getString("manifest", "");
                HttpURLConnection connection = connect(source, serial);
                String etag = preferences.getString("manifestEtag", "");
                if (!cached.isEmpty() && !etag.isEmpty() && source.equals(preferences.getString("source", ""))) connection.setRequestProperty("If-None-Match", etag);
                try {
                    int status = connection.getResponseCode(); String json;
                    if (status == 304 && !cached.isEmpty()) json = cached;
                    else if (status == 200) {
                        rejectEncoded(connection);
                        if (contentLength(connection) > UpdateManifest.MAX_MANIFEST_BYTES) throw new IOException("更新清单过大");
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (InputStream input = connection.getInputStream()) { UpdateManifest.copyLimited(input, bytes, UpdateManifest.MAX_MANIFEST_BYTES, () -> active(serial), count -> {}); }
                        json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                    } else throw new IOException("更新检查未成功");
                    UpdateManifest next = parse(json, source); ensureActive(serial);
                    UpdateManifest previous = manifest;
                    if (previous == null || !previous.toJson().equals(next.toJson())) clearDownload();
                    // 发布身份与小型偏好一同核代次，配置切换不能被旧请求贴上新身份。
                    synchronized (this) {
                        ensureActive(serial);
                        SharedPreferences.Editor edit = preferences.edit().putString("source", source).putString("manifest", next.toJson())
                                .putLong("lastSuccess", now).remove("lastFailure");
                        String nextEtag = connection.getHeaderField("ETag");
                        if (status != 304) edit.putString("manifestEtag", safeEtag(nextEtag));
                        if (!edit.commit()) throw new IOException("无法保存更新状态");
                        manifest = next;
                    }
                } finally { connection.disconnect(); activeConnection = null; }
                showAvailable(serial);
            } catch (Exception error) {
                if (active(serial)) preferences.edit().putString("failureSource", source).putLong("lastFailure", System.currentTimeMillis()).apply();
                failed(serial, error);
            }
        });
    }

    /** 不将时钟回拨当成永久节流；仅比较一个有界窗口。 */
    private static boolean recent(long now, long then, long interval) { return then > 0 && now >= then && now - then < interval; }
    /** 恢复的清单仍走同一严格解析规则，不接受其它发布来源遗留文件。 */
    private void restoreManifest(String source, long serial) throws IOException {
        String json = source.equals(preferences.getString("source", "")) ? preferences.getString("manifest", "") : "";
        UpdateManifest restored = null;
        try { if (!json.isEmpty()) restored = parse(json, source); }
        catch (IOException error) {
            synchronized (this) {
                ensureActive(serial);
                preferences.edit().remove("manifest").remove("lastSuccess").remove("manifestEtag").apply();
            }
        }
        synchronized (this) { ensureActive(serial); manifest = restored; }
    }
    /** 所有清单入口共用实际安装包名、SDK和ABI。 */
    private UpdateManifest parse(String json, String source) throws IOException {
        return UpdateManifest.parse(json, source, context.getPackageName(), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS);
    }

    /** 清单完成后优先复用已验文件，只有真实新版本才显示下载/安装。 */
    private void showAvailable(long serial) throws Exception {
        ensureActive(serial); UpdateManifest target = manifest;
        if (target == null) { publish(serial, State.IDLE, "点此检查更新", 0); return; }
        if (!target.isNewerThan(installedCode())) { clearDownload(); publish(serial, State.CURRENT, "已是最新版本", 0); return; }
        if (ready.isFile()) {
            try { target.verifyFile(ready); verifyArchive(target, ready); publish(serial, State.READY, "已下载，点此安装 " + target.versionName, 1); return; }
            catch (IOException error) { if (!ready.delete()) throw new IOException("无法清理失效安装包"); }
        }
        if (wifiAllowed() && !recent(System.currentTimeMillis(), preferences.getLong("downloadFailure", 0), FAILURE_INTERVAL_MS)) download(serial, false);
        else publish(serial, partial.length() > 0 ? State.PAUSED : State.AVAILABLE, "新版本 " + target.versionName + " · " + sizeLabel(target.sizeBytes) + "，点此下载", 0);
    }

    /** 有界续传只写本owner私有part，200响应从零写入，验签/完整SHA成功后才发布ready。 */
    private void download(long serial, boolean manual) throws Exception {
        ensureActive(serial); prepareDirectory(); final UpdateManifest target = manifest;
        if (target == null || !target.isNewerThan(installedCode())) { showAvailable(serial); return; }
        UpdateManifest.validateUrl(manifestUrl, target.apkUrl);
        if (!manual && !wifiAllowed()) { publish(serial, State.PAUSED, "等待非计费 Wi-Fi，点此下载", 0); return; }
        if (directory.getUsableSpace() < target.sizeBytes - Math.min(partial.length(), target.sizeBytes) + 8L * 1024 * 1024) throw new IOException("存储空间不足");
        long existing = partial.isFile() ? partial.length() : 0;
        if (existing == target.sizeBytes) { finishDownload(serial, target); return; }
        String etag = preferences.getString("downloadEtag", "");
        if (existing > target.sizeBytes || etag.isEmpty() || etag.startsWith("W/")) existing = 0;
        HttpURLConnection connection = connect(target.apkUrl, serial);
        if (existing > 0) { connection.setRequestProperty("Range", "bytes=" + existing + "-"); connection.setRequestProperty("If-Range", etag); }
        publish(serial, State.DOWNLOADING, "正在下载 " + target.versionName, (float)existing / target.sizeBytes);
        try {
            int status = connection.getResponseCode(); rejectEncoded(connection);
            final long offset = UpdateManifest.resumeOffset(status, connection.getHeaderField("Content-Range"), existing, target.sizeBytes);
            String receivedEtag = safeEtag(connection.getHeaderField("ETag"));
            if (offset > 0 && !etag.equals(receivedEtag)) throw new IOException("下载资源已变化");
            long length = contentLength(connection);
            if (length >= 0 && length != target.sizeBytes - offset) throw new IOException("下载长度与清单不符");
            if (!preferences.edit().putString("downloadEtag", receivedEtag).commit()) throw new IOException("无法保存下载状态");
            final long[] lastNotification = {0};
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial, offset > 0)) {
                long copied = UpdateManifest.copyLimited(input, output, target.sizeBytes - offset,
                        () -> active(serial) && (manual || wifiAllowed()), count -> {
                            long now = System.currentTimeMillis();
                            if (now - lastNotification[0] >= 1000) {
                                lastNotification[0] = now; float progress = (float)(offset + count) / target.sizeBytes;
                                publish(serial, State.DOWNLOADING, "正在下载 " + Math.round(progress * 100) + "%", progress);
                            }
                        });
                output.getFD().sync();
                if (offset + copied != target.sizeBytes) throw new IOException("下载中断，点此重试");
            }
        } finally { connection.disconnect(); activeConnection = null; }
        finishDownload(serial, target);
    }

    /** 已收齐内容也必须匹配真实APK身份；失败不交给安装器。 */
    private void finishDownload(long serial, UpdateManifest target) throws Exception {
        ensureActive(serial);
        try { target.verifyFile(partial); verifyArchive(target, partial); }
        catch (IOException error) { partial.delete(); preferences.edit().remove("downloadEtag").apply(); throw error; }
        ensureActive(serial);
        if (ready.exists() && !ready.delete()) throw new IOException("无法替换旧安装包");
        if (!partial.renameTo(ready)) throw new IOException("无法保存安装包");
        preferences.edit().remove("downloadFailure").apply();
        publish(serial, State.READY, "已下载，点此安装 " + target.versionName, 1);
    }

    /** APK实际包名/版本/当前证书必须一致；系统安装器仍执行最终签名与平台兼容校验。 */
    private void verifyArchive(UpdateManifest target, File file) throws IOException {
        try {
            PackageManager manager = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
            PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            if (archive == null || !target.packageName.equals(archive.packageName) || code(archive) != target.versionCode
                    || !target.versionName.equals(archive.versionName)
                    || !target.isNewerThan(code(installed)) || archive.applicationInfo == null
                    || (archive.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_TEST_ONLY) != 0
                    || (Build.VERSION.SDK_INT >= 24 && archive.applicationInfo.minSdkVersion != target.minSdk)
                    || !signers(installed).equals(signers(archive)) || signers(archive).isEmpty()) throw new IOException("安装包身份或签名不匹配");
        } catch (PackageManager.NameNotFoundException error) { throw new IOException("无法确认当前应用身份", error); }
    }
    /** 对当前签名集合做精确比较，本片不推测签名轮换关系。 */
    private static Set<String> signers(PackageInfo info) throws IOException {
        Signature[] values = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (values == null || values.length == 0) return Collections.emptySet();
        try { Set<String> result = new TreeSet<>(); MessageDigest digest = MessageDigest.getInstance("SHA-256"); for (Signature value : values) result.add(UpdateManifest.hex(digest.digest(value.toByteArray()))); return result; }
        catch (java.security.NoSuchAlgorithmException error) { throw new IOException("无法校验证书", error); }
    }
    /** 从实际已安装包读取版本，不把保存的更新目标当成已装版本。 */
    private long installedCode() throws PackageManager.NameNotFoundException { return code(context.getPackageManager().getPackageInfo(context.getPackageName(), 0)); }
    /** API28以下仍使用原int版本；本产品版本上限保持Android支持范围。 */
    private static long code(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }

    /** 仅HTTPS原地址，不跟随任何重定向；使用系统TLS校验与有限超时。 */
    private HttpURLConnection connect(String address, long serial) throws IOException {
        ensureActive(serial);
        URL url = new URL(address); if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getRef() != null) throw new IOException("更新地址无效");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection(); activeConnection = connection;
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept-Encoding", "identity"); connection.setRequestProperty("User-Agent", "CodexTop-Android-Updater/1"); return connection;
    }
    /** API21兼容地读取声明长度；缺失可流式读取，畸形值明确拒绝。 */
    private static long contentLength(HttpURLConnection connection) throws IOException {
        String value = connection.getHeaderField("Content-Length"); if (value == null) return -1;
        try { long length = Long.parseLong(value); if (length < 0) throw new NumberFormatException(); return length; }
        catch (NumberFormatException error) { throw new IOException("下载长度无效", error); }
    }
    /** 编码后的大小不等于APK原字节大小，拒绝服务端压缩响应。 */
    private static void rejectEncoded(HttpURLConnection connection) throws IOException { String encoding = connection.getHeaderField("Content-Encoding"); if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) throw new IOException("更新响应编码不支持"); }
    /** 下载恢复只保存一个有界实体校验器，不把任意响应头写入请求。 */
    private static String safeEtag(String value) { return value != null && value.length() <= 512 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0 ? value : ""; }
    /** 非计费Wi-Fi才可自动消耗流量；网络失败按不可自动下载处理。 */
    private boolean wifiAllowed() {
        try { ConnectivityManager manager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
            return network != null && network.isConnected() && network.getType() == ConnectivityManager.TYPE_WIFI && !network.isRoaming() && !manager.isActiveNetworkMetered();
        } catch (Exception error) { return false; }
    }
    /** 本轮任务只属于当前配置和前台生命周期。 */
    private boolean active(long serial) { return foreground && generation == serial && !manifestUrl.isEmpty(); }
    /** 生命周期变化通过暂停异常退出，不自动重试网络。 */
    private void ensureActive(long serial) throws UpdateManifest.PausedException { if (!active(serial)) throw new UpdateManifest.PausedException(); }
    /** 仅创建既有FileProvider允许的私有更新子目录。 */
    private void prepareDirectory() throws IOException { if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新目录"); }
    /** 新目标替换只清本owner两份文件，不触及附件、草稿或历史。 */
    private void clearDownload() throws IOException { for (File file : new File[]{partial, ready}) if (file.exists() && !file.delete()) throw new IOException("无法清理旧更新包"); preferences.edit().remove("downloadEtag").remove("downloadFailure").apply(); }
    /** 失败只反映在原更新行；后台暂停与普通失败分别显示，不弹窗。 */
    private void failed(long serial, Exception error) {
        if (generation != serial) return;
        if (!foreground || error instanceof UpdateManifest.PausedException) publish(serial, manifest == null ? State.IDLE : State.PAUSED, "下载已暂停，回到前台后继续", 0);
        else { if (manifest != null) preferences.edit().putLong("downloadFailure", System.currentTimeMillis()).apply(); publish(serial, State.ERROR, "更新未完成，点此重试", 0); }
    }
    /** 状态仅保留小对象，通知切回UI线程并隔离旧配置回调。 */
    private void publish(long serial, State state, String detail, float progress) {
        synchronized (this) { if (generation != serial) return; snapshot = new Snapshot(state, "应用更新", detail, manifest, progress); }
        AndroidUtilities.runOnUIThread(() -> { if (generation == serial) for (Runnable listener : listeners) listener.run(); });
    }
    /** 下载动作明确显示MB，避免用户误以为只请求小清单。 */
    private static String sizeLabel(long bytes) { return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1000000.0); }
}
