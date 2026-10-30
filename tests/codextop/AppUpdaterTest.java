import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 以真实更新owner执行网络/平台边界夹具；不连接服务器、不操作系统安装器。 */
public final class AppUpdaterTest {
    /** 只替代Android平台和URLConnection外边界，正式两类逐字编译为Java8后执行。 */
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]), work = Files.createTempDirectory("codextop-updater-owner-");
        Map<String, String> source = new LinkedHashMap<>();
        source.put("android/content/SharedPreferences.java", """
            package android.content; import java.util.*;
            public class SharedPreferences { private final Map<String,Object> v=new HashMap<>();
            public synchronized String getString(String k,String d){return (String)v.getOrDefault(k,d);}
            public synchronized long getLong(String k,long d){return ((Number)v.getOrDefault(k,d)).longValue();}
            public Editor edit(){return new Editor();} public class Editor { Map<String,Object> p=new HashMap<>();
            public Editor putString(String k,String v){p.put(k,v);return this;} public Editor putLong(String k,long v){p.put(k,v);return this;}
            public Editor remove(String k){p.put(k,null);return this;} public boolean commit(){synchronized(SharedPreferences.this){for(String k:p.keySet())if(p.get(k)==null)v.remove(k);else v.put(k,p.get(k));}return true;}
            public void apply(){commit();}}}
            """);
        source.put("android/content/Context.java", """
            package android.content; import java.io.*; import android.content.pm.*; import android.net.*;
            public class Context { public static final int MODE_PRIVATE=0; public static final String CONNECTIVITY_SERVICE="connectivity";
            public File files; public SharedPreferences prefs=new SharedPreferences(); public PackageManager pm=new PackageManager(); public ConnectivityManager network=new ConnectivityManager();
            public Context getApplicationContext(){return this;} public SharedPreferences getSharedPreferences(String n,int m){return prefs;} public File getFilesDir(){return files;}
            public String getPackageName(){return "com.butang.codextop.nativepreview.beta";} public PackageManager getPackageManager(){return pm;} public Object getSystemService(String s){return network;}}
            """);
        source.put("android/content/Intent.java", """
            package android.content; import android.net.Uri; public class Intent { public static final String ACTION_VIEW="view";public static final int FLAG_GRANT_READ_URI_PERMISSION=1;
            public final String action;public Intent(String a){action=a;} public Intent(String a,Uri u){action=a;} public Intent setDataAndType(Uri u,String t){return this;}public Intent addFlags(int f){return this;}}
            """);
        source.put("android/app/Activity.java", """
            package android.app; public class Activity extends android.content.Context { public java.util.List<String> actions=new java.util.ArrayList<>();public boolean isFinishing(){return false;}public boolean isDestroyed(){return false;}public void startActivity(android.content.Intent i){actions.add(i.action);}}
            """);
        source.put("android/content/pm/ApplicationInfo.java", "package android.content.pm; public class ApplicationInfo { public static final int FLAG_TEST_ONLY=256;public int flags,minSdkVersion=21; }");
        source.put("android/content/pm/Signature.java", "package android.content.pm;public class Signature {private byte[] v;public Signature(byte[] b){v=b;}public byte[] toByteArray(){return v;} }");
        source.put("android/content/pm/SigningInfo.java", "package android.content.pm;public class SigningInfo {public Signature[] values={new Signature(new byte[]{1})};public Signature[] getApkContentsSigners(){return values;} }");
        source.put("android/content/pm/PackageInfo.java", "package android.content.pm;public class PackageInfo {public String packageName=\"com.butang.codextop.nativepreview.beta\";public String versionName=\"1.0.1\";public int versionCode=71059;public ApplicationInfo applicationInfo=new ApplicationInfo();public Signature[] signatures={new Signature(new byte[]{1})};public SigningInfo signingInfo=new SigningInfo();public long getLongVersionCode(){return versionCode;} }");
        source.put("android/content/pm/PackageManager.java", """
            package android.content.pm;public class PackageManager {public static final int GET_SIGNING_CERTIFICATES=1,GET_SIGNATURES=2;public PackageInfo installed=new PackageInfo(),archive=new PackageInfo();public boolean allowed=true;
            public PackageManager(){archive.versionCode=71060;}public PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException{return installed;}public PackageInfo getPackageArchiveInfo(String p,int f){return archive;}public boolean canRequestPackageInstalls(){return allowed;}public static class NameNotFoundException extends Exception {}}
            """);
        source.put("android/net/NetworkInfo.java", "package android.net;public class NetworkInfo {public boolean isConnected(){return true;}public int getType(){return 1;}public boolean isRoaming(){return false;} }");
        source.put("android/net/ConnectivityManager.java", "package android.net;public class ConnectivityManager {public static final int TYPE_WIFI=1;public boolean metered;public NetworkInfo getActiveNetworkInfo(){return new NetworkInfo();}public boolean isActiveNetworkMetered(){return metered;} }");
        source.put("android/net/Uri.java", "package android.net;public class Uri {public static Uri parse(String s){return new Uri();} }");
        source.put("android/os/Build.java", "package android.os;public class Build {public static String[] SUPPORTED_ABIS={\"arm64-v8a\"};public static class VERSION {public static int SDK_INT=36;} }");
        source.put("android/provider/Settings.java", "package android.provider;public class Settings {public static final String ACTION_MANAGE_UNKNOWN_APP_SOURCES=\"permission\"; }");
        source.put("androidx/core/content/FileProvider.java", "package androidx.core.content;public class FileProvider {public static android.net.Uri getUriForFile(android.content.Context c,String a,java.io.File f){return new android.net.Uri();} }");
        source.put("org/telegram/messenger/ApplicationLoader.java", "package org.telegram.messenger;public class ApplicationLoader {public static android.content.Context applicationContext;} ");
        source.put("org/telegram/messenger/AndroidUtilities.java", "package org.telegram.messenger;public class AndroidUtilities {public static void runOnUIThread(Runnable r){r.run();} }");
        source.put("com/butang/codextop/OwnerScenarios.java", SCENARIOS);
        try {
            List<String> command = new ArrayList<>(List.of(System.getProperty("java.home")+"/bin/javac", "--release", "8", "-cp", args[1], "-d", work.resolve("classes").toString()));
            for (Map.Entry<String,String> entry:source.entrySet()){Path file=work.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());command.add(file.toString());}
            command.add(repo.resolve("TMessagesProj/src/main/java/com/butang/codextop/UpdateManifest.java").toString());
            command.add(repo.resolve("TMessagesProj/src/main/java/com/butang/codextop/CodexAppUpdater.java").toString());
            if(new ProcessBuilder(command).inheritIO().start().waitFor()!=0)throw new AssertionError("Java8正式源码编译失败");
            if(new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",work.resolve("classes")+":"+args[1],"com.butang.codextop.OwnerScenarios").inheritIO().start().waitFor()!=0)throw new AssertionError("真实owner场景失败");
        } finally {try(var paths=Files.walk(work)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);}}
    }
    private static final String SCENARIOS = """
        package com.butang.codextop;
        import android.content.Context;import android.app.Activity;import android.content.pm.*;import java.io.*;import java.net.*;import java.lang.reflect.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.util.*;import java.util.concurrent.*;
        public class OwnerScenarios {
            static final String SOURCE="https://example.test/updates/android/manifest.json";
            static final byte[] APK="hello-app-update".getBytes(StandardCharsets.UTF_8);
            static final Deque<Response> responses=new ArrayDeque<>();static final List<Response> requests=new ArrayList<>();static int checks;
            static class Response extends HttpURLConnection {
                int code; byte[] bytes;Map<String,String> headers=new HashMap<>(),sent=new HashMap<>();CountDownLatch entered,release;Runnable firstRead;boolean readOnce;
                Response(int status,byte[] value){super(null);code=status;bytes=value;headers.put("Content-Length",String.valueOf(value.length));headers.put("ETag","\\\"same\\\"");}
                public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}
                public int getResponseCode()throws IOException{if(entered!=null){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}}return code;}
                public void setRequestProperty(String k,String v){sent.put(k,v);} public String getHeaderField(String k){return headers.get(k);}
                public InputStream getInputStream(){return new ByteArrayInputStream(bytes){public synchronized int read(byte[] b,int o,int l){int count=super.read(b,o,Math.min(l,5));if(!readOnce){readOnce=true;if(firstRead!=null)firstRead.run();}return count;}};}
            }
            static Context context;static CodexAppUpdater owner;static File root;
            static void setup()throws Exception{responses.clear();requests.clear();context=new Context();root=Files.createTempDirectory("update-fixture-").toFile();context.files=root;
                org.telegram.messenger.ApplicationLoader.applicationContext=context;Field f=CodexAppUpdater.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,null);owner=CodexAppUpdater.getInstance();}
            static String json()throws Exception{return "{\\\"schema\\\":1,\\\"packageName\\\":\\\"com.butang.codextop.nativepreview.beta\\\",\\\"versionCode\\\":71060,\\\"versionName\\\":\\\"1.0.1\\\",\\\"minSdk\\\":21,\\\"abi\\\":\\\"arm64-v8a\\\",\\\"apkUrl\\\":\\\"https://example.test/updates/android/71060.apk\\\",\\\"sizeBytes\\\":"+APK.length+",\\\"sha256\\\":\\\""+UpdateManifest.hex(MessageDigest.getInstance("SHA-256").digest(APK))+"\\\"}";}
            static Response metadata()throws Exception{Response r=new Response(200,json().getBytes(StandardCharsets.UTF_8));responses.add(r);return r;}
            static Response apk(){Response r=new Response(200,APK);responses.add(r);return r;}
            static void idle()throws Exception{Field f=CodexAppUpdater.class.getDeclaredField("busy");f.setAccessible(true);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){synchronized(owner){if(!f.getBoolean(owner))return;}Thread.sleep(5);}throw new AssertionError("未收尾");}
            static void check(boolean value,String reason){if(!value)throw new AssertionError(reason+" state="+owner.getSnapshot().state+" requests="+requests.size());checks++;}
            static void cleanup()throws Exception{owner.onBackground();idle();try(java.util.stream.Stream<Path> p=Files.walk(root.toPath())){for(Path f:p.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(f);}}
            public static void main(String[]args)throws Exception{
                URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){protected URLConnection openConnection(URL u)throws IOException{Response r=responses.poll();if(r==null)throw new IOException("意外请求");requests.add(r);return r;}}:null);
                setup();try{metadata();apk();owner.onForeground(SOURCE);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.READY,"WiFi完整下载");check(requests.size()==2,"一次检查一次下载");
                    owner.onForeground(SOURCE);idle();check(requests.size()==2,"成功节流不重下");
                    Response cached=new Response(304,new byte[0]);responses.add(cached);owner.checkNow();idle();check(owner.getSnapshot().state==CodexAppUpdater.State.READY&&requests.size()==3,"304复用已校验文件");check(cached.sent.containsKey("If-None-Match"),"带条件清单请求");
                    Activity activity=new Activity();owner.install(activity);idle();check(activity.actions.equals(Arrays.asList("view")),"仅用户点击系统安装");
                    context.pm.allowed=false;activity.actions.clear();owner.install(activity);idle();check(activity.actions.equals(Arrays.asList("permission")),"系统未知来源授权");
                    context.pm.allowed=true;Files.write(new File(root,"cache/codex-updates/ready.apk").toPath(),new byte[APK.length]);activity.actions.clear();owner.install(activity);idle();check(activity.actions.isEmpty(),"安装前再次SHA校验");
                }finally{cleanup();}
                setup();try{context.network.metered=true;metadata();owner.onForeground(SOURCE);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.AVAILABLE&&requests.size()==1,"计费网络不自动下载");apk();owner.downloadNow();idle();check(owner.getSnapshot().state==CodexAppUpdater.State.READY,"用户明确下载可用移动流量");}finally{cleanup();}
                for(int mode=0;mode<5;mode++){setup();try{if(mode==0)context.pm.archive.signingInfo.values=new Signature[]{new Signature(new byte[]{2})};if(mode==1)context.pm.archive.packageName="other";if(mode==2)context.pm.archive.versionCode=71061;if(mode==3)context.pm.archive.applicationInfo.flags=ApplicationInfo.FLAG_TEST_ONLY;if(mode==4)context.pm.archive.versionName="wrong";metadata();apk();owner.onForeground(SOURCE);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.ERROR,"错证书包名版本testOnly拒绝"+mode);check(!new File(root,"cache/codex-updates/ready.apk").exists(),"拒绝包不标可安装");}finally{cleanup();}}
                setup();try{context.pm.installed.versionCode=71060;metadata();owner.onForeground(SOURCE);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.CURRENT&&requests.size()==1,"同版不下载");}finally{cleanup();}
                setup();try{Response r=metadata();r.entered=new CountDownLatch(1);r.release=new CountDownLatch(1);apk();owner.onForeground(SOURCE);check(r.entered.await(2,TimeUnit.SECONDS),"请求进入");owner.checkNow();owner.checkNow();r.release.countDown();idle();check(requests.size()==2,"并发检查单flight");}finally{cleanup();}
                setup();try{metadata();Response r=apk();r.firstRead=()->owner.onBackground();owner.onForeground(SOURCE);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.PAUSED,"退后台暂停");check(!new File(root,"cache/codex-updates/ready.apk").exists(),"半包不能安装");apk();owner.onForeground(SOURCE);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.READY,"回前台恢复");}finally{cleanup();}
                setup();try{metadata();Response r=apk();apk();r.firstRead=()->{owner.onBackground();owner.onForeground(SOURCE);};owner.onForeground(SOURCE);idle();Thread.sleep(30);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.READY,"快速前后台不误记失败退避");check(context.prefs.getLong("downloadFailure",0)==0,"生命周期取消没有下载失败戳");}finally{cleanup();}
                setup();try{context.network.metered=true;metadata();owner.onForeground(SOURCE);idle();File part=new File(root,"cache/codex-updates/download.part");Files.write(part.toPath(),Arrays.copyOf(APK,5));context.prefs.edit().putString("downloadEtag","\\\"same\\\"").commit();Response r=new Response(206,Arrays.copyOfRange(APK,5,APK.length));r.headers.put("Content-Range","bytes 5-"+(APK.length-1)+"/"+APK.length);responses.add(r);owner.downloadNow();idle();check(owner.getSnapshot().state==CodexAppUpdater.State.READY,"真实part续传成功");check("bytes=5-".equals(r.sent.get("Range")),"Range准确");}finally{cleanup();}
                setup();try{context.network.metered=true;metadata();owner.onForeground(SOURCE);idle();Files.write(new File(root,"cache/codex-updates/download.part").toPath(),Arrays.copyOf(APK,5));context.prefs.edit().putString("downloadEtag","\\\"same\\\"").commit();apk();owner.downloadNow();idle();check(owner.getSnapshot().state==CodexAppUpdater.State.READY,"200全量不拼接旧part");}finally{cleanup();}
                setup();try{Response old=metadata();old.entered=new CountDownLatch(1);old.release=new CountDownLatch(1);owner.onForeground(SOURCE);check(old.entered.await(2,TimeUnit.SECONDS),"旧配置请求进入");String other=SOURCE.replace("example.test","new.test");Response fresh=new Response(200,json().replace("example.test","new.test").getBytes(StandardCharsets.UTF_8));responses.add(fresh);apk();owner.onForeground(other);old.release.countDown();idle();Thread.sleep(30);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.READY&&requests.size()==3,"旧配置回包不覆盖新来源");check(other.equals(context.prefs.getString("source","")),"偏好只发布新来源");}finally{cleanup();}
                setup();try{metadata();Response r=new Response(200,Arrays.copyOf(APK,APK.length+1));r.headers.put("Content-Length",String.valueOf(APK.length));responses.add(r);owner.onForeground(SOURCE);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.ERROR,"流式超限拒绝");check(!new File(root,"cache/codex-updates/ready.apk").exists(),"超限没有ready");check(new File(root,"cache/codex-updates/download.part").length()<=APK.length,"磁盘写入不超过上限");}finally{cleanup();}
                setup();try{context.network.metered=true;metadata();owner.onForeground(SOURCE);idle();File part=new File(root,"cache/codex-updates/download.part");Files.write(part.toPath(),Arrays.copyOf(APK,5));context.prefs.edit().putString("downloadEtag","\\\"same\\\"").commit();Field singleton=CodexAppUpdater.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);owner=CodexAppUpdater.getInstance();Response r=new Response(206,Arrays.copyOfRange(APK,5,APK.length));r.headers.put("Content-Range","bytes 5-"+(APK.length-1)+"/"+APK.length);responses.add(r);owner.onForeground(SOURCE);idle();owner.downloadNow();idle();check(owner.getSnapshot().state==CodexAppUpdater.State.READY,"新owner从小描述和part恢复");}finally{cleanup();}
                setup();try{owner.onForeground("http://example.test/a.json");idle();check(owner.getSnapshot().state==CodexAppUpdater.State.ERROR&&requests.isEmpty(),"HTTP拒绝无请求");}finally{cleanup();}
                setup();try{Response r=metadata();r.bytes=(json()+" {}").getBytes(StandardCharsets.UTF_8);r.headers.put("Content-Length",String.valueOf(r.bytes.length));owner.onForeground(SOURCE);idle();check(owner.getSnapshot().state==CodexAppUpdater.State.ERROR&&requests.size()==1,"坏清单不下载");}finally{cleanup();}
                System.out.println("AppUpdaterTest PASS checks="+checks);
            }
        }
        """;
}
