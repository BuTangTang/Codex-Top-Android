package com.butang.codextop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/** 冻结旧Store/Window在独立loader中作oracle；两边都读取真实临时文件，比较完整投影与拒绝边界。 */
public final class TranscriptStoreStreamingReadTest {
    private static int cases, accepted, rejected;
    private static final String EPOCH = "00000000-0000-4000-8000-000000000001";
    private static final String SERVER = "synthetic-server", ACCOUNT = "synthetic-account", MACHINE = "synthetic-machine";

    /** Java8实际源与本测试同编译；baseline参数必须是改动前冻结的两个完整源，不读取真实缓存。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要冻结旧Store/Window源目录");
        Path temp = Files.createTempDirectory("codex-stream-read-").toRealPath();
        try (URLClassLoader old = frozenLoader(Paths.get(args[0]), temp.resolve("old-classes"))) {
            Path cache = temp.resolve("cache");
            byte[] sentinel = "synthetic-pending-and-draft".getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(cache); Files.write(cache.resolve("outbox-and-draft"), sentinel);
            runCases(old, cache);
            verifyPhysicalIoFailures(Paths.get(args[0]), temp.resolve("io-faults"));
            check(Arrays.equals(sentinel, Files.readAllBytes(cache.resolve("outbox-and-draft"))), "read touched pending/draft sibling");
            try (Stream<Path> files = Files.walk(cache)) {
                check(files.noneMatch(p -> p.toString().endsWith(".tmp")), "read wrote temporary file");
            }
            System.out.println("TranscriptStoreStreamingRead: cases=" + cases + " accepted=" + accepted + " rejected=" + rejected + " failures=0; frozen original Store oracle, Java8 actual sources");
        } finally {
            try (Stream<Path> files = Files.walk(temp)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
            }
        }
    }

    /** 单独编译冻结原两源，依赖仍为同一版本的只读模型与Gson；parent=null隔离新版类。 */
    private static URLClassLoader frozenLoader(Path baseline, Path classes) throws Exception {
        Files.createDirectories(classes);
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-encoding", "UTF-8",
                "-cp", System.getProperty("java.class.path"), "-d", classes.toString(),
                baseline.resolve("TranscriptWindow.java").toString(), baseline.resolve("TranscriptStore.java").toString());
        check(code == 0, "frozen original sources did not compile");
        ArrayList<URL> urls = new ArrayList<>(); urls.add(classes.toUri().toURL());
        for (String item : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            urls.add(Paths.get(item).toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), null);
    }

    /** 只替换Files.open系统边界，原Store算法完整编译；首次真实IO错误不能被第二次成功掩盖。 */
    private static void verifyPhysicalIoFailures(Path baseline, Path root) throws Exception {
        Files.createDirectories(root);
        try (URLClassLoader original = faultLoader(baseline, root.resolve("old"));
                URLClassLoader current = faultLoader(Paths.get("TMessagesProj/src/main/java/com/butang/codextop"), root.resolve("new"))) {
            JsonObject body = parse(snapshot()); body.getAsJsonArray("rows").add(row(1,"physical-io",repeat("synthetic",4000),null,1));
            String canonical = body.toString();
            String noncanonical = "{\"unknown\":false,"+canonical.substring(1);
            String invalidFirstVersion = "{\"version\":3,"+canonical.substring(1);
            String[] modes={"open","read","late-read","close","read-close","close","close","close"};
            String[] inputs={canonical,canonical,canonical,canonical,canonical,noncanonical,invalidFirstVersion,canonical.substring(0,canonical.length()-1)};
            for(int i=0;i<modes.length;i++){
                for(ClassLoader loader:new ClassLoader[]{original,current}){
                    boolean failed=faultRead(loader,root.resolve("data"),modes[i],inputs[i]);
                    Class<?> boundary=loader.loadClass("com.butang.codextop.ReadFaultBoundary");
                    check(failed&&boundary.getField("opens").getInt(null)==1,"physical IO was hidden by reopen: "+modes[i]+" case="+i);
                }
            }
            check(!faultRead(current,root.resolve("data"),"none",noncanonical),"compatible layout no longer restores");
            check(current.loadClass("com.butang.codextop.ReadFaultBoundary").getField("opens").getInt(null)==2,"compatibility fallback must happen exactly once");
        }
        System.out.println("TranscriptStoreStreamingRead: 8 physical open/body/close fault cases reject without reopen; layout fallback once PASS");
    }

    /** 同包临时副本只把文件打开交给故障InputStream；不改窗口、字段验证或catch内部逻辑。 */
    private static URLClassLoader faultLoader(Path sources,Path output)throws Exception{
        Files.createDirectories(output);Path source=output.resolve("src"),classes=output.resolve("classes");Files.createDirectories(source);Files.createDirectories(classes);
        // 原95与新薄门面分别冻结真实依赖；注入只替换readLegacy两次打开，不替换格式/异常逻辑。
        ArrayList<String> compileFiles=new ArrayList<>();
        // 仅编译持久化的实际纯Java依赖；正式包目录也含Android/UI owner，不能整目录扩编。
        for(String name:new String[]{"TranscriptStore","TranscriptWindow","TranscriptText","DesktopAttachment",
                "IndexedTranscriptStore","TranscriptBodyStore","TranscriptCacheBudget","TranscriptPersistenceToken"}){
            Path file=sources.resolve(name+".java");
            if(!Files.exists(file))continue; // 冻结95 oracle只有两个完整owner，其余依赖沿相同classpath。
            Files.copy(file,source.resolve(file.getFileName()));compileFiles.add(source.resolve(file.getFileName()).toString());
        }
        Path storePath=source.resolve("TranscriptStore.java");String store=new String(Files.readAllBytes(storePath),StandardCharsets.UTF_8);
        String oldBoundary="Files.newInputStream(file.toPath())",newBoundary="Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)";
        if(store.contains(oldBoundary))store=store.replace(oldBoundary,"ReadFaultBoundary.open(file.toPath())");
        else{
            int begin=store.indexOf("private static Legacy readLegacy("),end=store.indexOf("private static final class Legacy",begin);
            check(begin>=0&&end>begin,"readLegacy boundary missing");String method=store.substring(begin,end);
            check(method.split(java.util.regex.Pattern.quote(newBoundary),-1).length-1==2,"readLegacy stream open count changed");
            store=store.substring(0,begin)+method.replace(newBoundary,"ReadFaultBoundary.open(file)")+store.substring(end);
        }
        Files.write(storePath,store.getBytes(StandardCharsets.UTF_8));
        String boundary="package com.butang.codextop;import java.io.*;import java.nio.file.*;public final class ReadFaultBoundary{public static int opens,closes;public static String mode;"
                +"public static InputStream open(Path path)throws IOException{final int index=++opens;if(index==1&&mode.equals(\"open\"))throw new IOException(\"synthetic open failure\");return new FilterInputStream(Files.newInputStream(path)){int bytes;"
                +"public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}"
                +"public int read(byte[] b,int o,int n)throws IOException{if(index==1&&(mode.equals(\"read\")||mode.equals(\"read-close\")||mode.equals(\"late-read\")&&bytes>=4096))throw new IOException(\"synthetic read failure\");int read=in.read(b,o,Math.min(n,512));if(read>0)bytes+=read;return read;}"
                +"public void close()throws IOException{closes++;super.close();if(index==1&&(mode.equals(\"close\")||mode.equals(\"read-close\")))throw new IOException(\"synthetic close failure\");}};}}";
        Files.write(source.resolve("ReadFaultBoundary.java"),boundary.getBytes(StandardCharsets.UTF_8));
        ArrayList<String> compilerArgs=new ArrayList<>(Arrays.asList("--release","8","-encoding","UTF-8","-cp",System.getProperty("java.class.path"),"-d",classes.toString()));
        compilerArgs.addAll(compileFiles);compilerArgs.add(source.resolve("ReadFaultBoundary.java").toString());
        int code=ToolProvider.getSystemJavaCompiler().run(null,null,null,compilerArgs.toArray(new String[0]));
        check(code==0,"physical IO fixture did not compile");ArrayList<URL> urls=new ArrayList<>();urls.add(classes.toUri().toURL());
        for(String item:System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))urls.add(Paths.get(item).toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]),null);
    }

    /** 故障只属于一次打开的流；检查失败读取没有修改原缓存，也不交付半窗口。 */
    private static boolean faultRead(ClassLoader loader,Path root,String mode,String input)throws Exception{
        Path file=root.resolve(TranscriptStore.digest(SERVER+"\n"+ACCOUNT+"\n"+MACHINE)).resolve(TranscriptStore.digest("thread")+".json");Files.createDirectories(file.getParent());byte[] bytes=input.getBytes(StandardCharsets.UTF_8);Files.write(file,bytes);
        Class<?> boundary=loader.loadClass("com.butang.codextop.ReadFaultBoundary");boundary.getField("mode").set(null,mode);boundary.getField("opens").setInt(null,0);boundary.getField("closes").setInt(null,0);
        Object store=loader.loadClass("com.butang.codextop.TranscriptStore").getConstructor(File.class,String.class,String.class,String.class).newInstance(root.toFile(),SERVER,ACCOUNT,MACHINE);boolean failed=false;
        try{store.getClass().getMethod("read",String.class).invoke(store,"thread");}catch(InvocationTargetException error){if(!(error.getCause() instanceof IOException))throw error;failed=true;}
        check(Arrays.equals(bytes,Files.readAllBytes(file)),"physical IO failure changed cache");return failed;
    }

    /** 合法快照、原宽松JSON、非规范字段和坏档都由同一旧真实读法决定接受/拒绝。 */
    private static void runCases(ClassLoader old, Path cache) throws Exception {
        String plain = snapshot();
        compare(old, cache, "empty-v2", plain, false);
        JsonObject full = parse(plain);
        full.getAsJsonArray("rows").add(row(5, "later", "汉🌌 <>&='\"\\\n\u2028\u2029", null, Long.MAX_VALUE));
        full.getAsJsonArray("rows").add(row(1, "earlier", repeat("x", 8191) + "🌌尾\uD83D", "local-1", Long.MIN_VALUE));
        JsonObject archive = parse(plain); archive.remove("archive"); archive.addProperty("epoch", "00000000-0000-4000-8000-000000000002");
        archive.getAsJsonArray("rows").add(row(7, "old", "archived", null, 1)); full.getAsJsonArray("archive").add(archive);
        compare(old, cache, "canonical-unsorted-rows-archive-unicode-long", full.toString(), false);
        JsonObject attachment = row(2, "attachment", "", "local-a", 2);
        attachment.getAsJsonObject("item").getAsJsonObject("raw").add("meta", JsonParser.parseString(
                "{\"happier\":{\"kind\":\"attachments.v1\",\"payload\":{\"attachments\":[{\"name\":\"合成🌌.png\",\"kind\":\"image\",\"path\":\"/synthetic/a.png\",\"mimeType\":\"image/png\",\"sizeBytes\":9223372036854775807,\"sha256\":\"" + repeat("a",64) + "\"}]}}}"));
        JsonObject media = parse(plain); media.getAsJsonArray("rows").add(attachment);
        compare(old, cache, "attachment-complete-envelope", media.toString(), false);
        attachment.getAsJsonObject("item").getAsJsonObject("raw").add("meta", JsonParser.parseString("{\"happier\":{\"kind\":\"attachments.v1\",\"payload\":{\"attachments\":[{\"name\":\"missing\",\"kind\":\"file\",\"availability\":\"unavailable\",\"reason\":\"unknown\"}]}}}"));
        compare(old, cache, "attachment-unavailable-null-fields", media.toString(), false);
        for (String extra : new String[]{"", ",\"epoch\":null,\"archive\":false", ",\"archive\":[null,{\"bad\":1}],\"extra\":{\"ignored\":true}"}) {
            String v1 = plain.replace("\"version\":2,\"epoch\":\"" + EPOCH + "\",", "\"version\":1,").replace(",\"archive\":[]", "");
            compare(old, cache, "v1-ignored-fields", v1.substring(0,v1.length()-1)+extra+"}", true);
        }
        JsonObject local = parse(plain);
        local.getAsJsonArray("rows").add(row(5,"first","first", "same-local",1));
        local.getAsJsonArray("rows").add(row(2,"skipped","skip", "same-local",2));
        compare(old, cache, "local-dedup-file-order-source-alias", local.toString(), false);
        local.getAsJsonArray("rows").add(row(3,"skipped","kept later", "another-local",3));
        compare(old, cache, "local-dedup-file-order-with-skipped-source", local.toString(), false);
        JsonObject reversed = new JsonObject(); ArrayList<Map.Entry<String,JsonElement>> fields = new ArrayList<>(full.entrySet());
        for (int i=fields.size()-1;i>=0;i--) reversed.add(fields.get(i).getKey(), fields.get(i).getValue());
        compare(old, cache, "reverse-field-order", reversed.toString(), false);
        for (String duplicate : new String[]{"\"rows\":null,", "\"oldest\":null,", "\"version\":7,", "\"epoch\":null,", "\"archive\":null,"})
            compare(old, cache, "duplicate-earlier-invalid-overwritten", "{"+duplicate+plain.substring(1), false);
        compare(old, cache, "duplicate-final-rows-replace", plain.substring(0,plain.length()-1)+",\"rows\":["+row(1,"last","last-wins",null,1)+"]}", false);
        String duplicateRow = "{\"number\":null," + row(1,"row-duplicate","last row fields",null,1).toString().substring(1);
        compare(old,cache,"duplicate-fields-within-row",plain.replace("\"rows\":[]","\"rows\":["+duplicateRow+"]"),false);
        compare(old, cache, "unknown-root-field", plain.substring(0,plain.length()-1)+",\"future\":{\"ignored\":[1,2]}}", false);
        for (String value : new String[]{"1.0", "1e0", "4294967297", "[1]", "\"1\"", "\"1.0\"", "null", "{}"})
            compare(old, cache, "oldest-original-number-coercion", plain.replace("\"oldest\":1", "\"oldest\":"+value), false);
        for (String value : new String[]{"2.9", "4294967298", "[2]", "\"2\"", "\"2.9\"", "3"})
            compare(old, cache, "version-original-number-coercion", plain.replace("\"version\":2", "\"version\":"+value), false);
        for (String value : new String[]{"[\"x\"]", "9", "true", "[]", "[null]"})
            compare(old, cache, "cursor-original-scalar-coercion", plain.replace("\"cursor\":null", "\"cursor\":"+value), false);
        for (String value : new String[]{"\"true\"", "0", "[true]", "\"other\"", "null"})
            compare(old, cache, "boolean-original-coercion", plain.replace("\"loaded\":false", "\"loaded\":"+value), false);
        for (String value : new String[]{"1.0", "[1]", "4294967297", "\"1.0\""}) {
            JsonObject base = parse(plain); base.getAsJsonArray("rows").add(row(1,"number","text",null,1));
            compare(old, cache, "row-number-coercion", base.toString().replace("\"number\":1", "\"number\":"+value), false);
        }
        for (String prefix : new String[]{"\uFEFF \n", "/* prefix */", ")]}'\n"}) compare(old,cache,"lenient-prefix",prefix+plain,false);
        compare(old,cache,"single-quotes",plain.replace('"','\''),false);
        compare(old,cache,"unquoted-field-and-equals",plain.replace("\"version\":2","version=2"),false);
        compare(old,cache,"lenient-inner-comment",plain.replace("\"rows\":[]","\"rows\":/*rows*/[]"),false);
        for (String suffix : new String[]{"\n\t ", "{}", "null", "true", "/* tail */", "// tail", "garbage"}) compare(old,cache,"complete-tail-check",plain+suffix,false);
        for (String bad : new String[]{"", "null", "[]", "false", "{}", plain.substring(0,plain.length()-1), plain.replace("\"archive\":[]", "\"archive\":null"),
                plain.replace("\"epoch\":\""+EPOCH+"\"", "\"epoch\":\"bad\"")}) compare(old,cache,"invalid-root-and-tail",bad,false);
        JsonObject nested = parse(plain); nested.getAsJsonArray("archive").add(parse(plain)); compare(old,cache,"nested-archive-rejected",nested.toString(),false);
        JsonObject duplicateEpoch = parse(plain); JsonObject same = parse(plain); same.remove("archive"); duplicateEpoch.getAsJsonArray("archive").add(same); compare(old,cache,"duplicate-epoch",duplicateEpoch.toString(),false);
        for (int kind=0;kind<4;kind++) {
            JsonObject bad=parse(plain);bad.getAsJsonArray("rows").add(row(1,"id","text",null,1));
            if(kind==0)bad.getAsJsonArray("rows").add(row(2,"id","duplicate-source",null,2));
            if(kind==1)bad.getAsJsonArray("rows").add(row(1,"other","duplicate-number",null,2));
            if(kind==2)bad.getAsJsonArray("rows").add(row(0,"other","below-oldest",null,2));
            if(kind==3)bad.getAsJsonArray("rows").add(row(2,"other","",null,2));
            compare(old,cache,"bad-row-no-partial-window",bad.toString(),false);
        }
        JsonObject malformed=parse(plain);malformed.getAsJsonArray("rows").add(row(1,"utf8","marker",null,1));
        byte[] bytes=malformed.toString().getBytes(StandardCharsets.UTF_8);int marker=malformed.toString().indexOf("marker");bytes[marker]=(byte)0xc3;bytes[marker+1]=0x28;
        compareBytes(old,cache,"bad-UTF8-replacement",bytes,false);
        JsonObject large=parse(plain);for(int i=1;i<=2400;i++)large.getAsJsonArray("rows").add(row(i,"source-"+i,repeat("合成🌌",80),i%2==0?"local-"+i:null,i));
        compare(old,cache,"many-rows-cross-reader-buffers",large.toString(),false);
        // 直接执行实际新入口，规范软件缓存不能靠整树兼容回退使oracle碰巧通过。
        for (String canonical : new String[]{plain, full.toString(), media.toString(), large.toString()}) {
            try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(canonical))) {
                TranscriptWindow streamed = TranscriptWindow.readSnapshot(reader);
                check(reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT, "canonical stream did not consume full value");
                check(streamed.snapshot().equals(TranscriptWindow.restore(parse(canonical)).snapshot()), "canonical stream needed fallback or changed model");
            }
        }
        String canonicalV1=plain.replace("\"version\":2,\"epoch\":\""+EPOCH+"\",","\"version\":1,").replace(",\"archive\":[]","");
        try(com.google.gson.stream.JsonReader reader=new com.google.gson.stream.JsonReader(new java.io.StringReader(canonicalV1))){
            JsonObject streamed=TranscriptWindow.readSnapshot(reader).snapshot(), restored=TranscriptWindow.restore(parse(canonicalV1)).snapshot();
            streamed.remove("epoch");restored.remove("epoch");check(streamed.equals(restored)&&reader.peek()==com.google.gson.stream.JsonToken.END_DOCUMENT,"canonical v1 needed fallback");
        }
    }

    /** 固定软件规范字段顺序，所有预期都仍交独立旧读法而不调用新版snapshot造oracle。 */
    private static String snapshot() {
        return "{\"version\":2,\"epoch\":\""+EPOCH+"\",\"oldest\":1,\"cursor\":null,\"tailCursor\":\"\",\"hasMore\":true,\"loaded\":false,\"complete\":false,\"rows\":[],\"archive\":[]}";
    }

    /** 只构造合成原始行，投影与身份校验均由两边真实模型执行。 */
    private static JsonObject row(int number,String id,String text,String local,long time) {
        JsonObject row=new JsonObject();row.addProperty("number",number);
        JsonObject item=new JsonObject();item.addProperty("id",id);item.addProperty("localId",local);item.addProperty("createdAtMs",time);
        JsonObject raw=new JsonObject();raw.addProperty("role",local==null?"agent":"user");JsonObject content=new JsonObject();content.addProperty("type","text");content.addProperty("text",text);raw.add("content",content);item.add("raw",raw);row.add("item",item);return row;
    }

    /** 每个文件先旧读后新读；v1仅消去本来随机的epoch，其余模型字段全部比较。 */
    private static void compare(ClassLoader old,Path cache,String name,String text,boolean v1)throws Exception { compareBytes(old,cache,name,text.getBytes(StandardCharsets.UTF_8),v1); }

    /** 确认完整成功/失败及磁盘无副作用，坏档不能以部分窗口伪装成功。 */
    private static void compareBytes(ClassLoader old,Path cache,String name,byte[] bytes,boolean v1)throws Exception {
        Path file=cache.resolve(TranscriptStore.digest(SERVER+"\n"+ACCOUNT+"\n"+MACHINE)).resolve(TranscriptStore.digest("thread")+".json");
        Files.createDirectories(file.getParent());Files.write(file,bytes);
        Object store=old.loadClass("com.butang.codextop.TranscriptStore").getConstructor(File.class,String.class,String.class,String.class).newInstance(cache.toFile(),SERVER,ACCOUNT,MACHINE);
        JsonObject expected=null,actual=null;Object oldWindow=null;TranscriptWindow newWindow=null;boolean oldFail=false,newFail=false;
        try {oldWindow=store.getClass().getMethod("read",String.class).invoke(store,"thread");expected=parse(oldWindow.getClass().getMethod("snapshot").invoke(oldWindow).toString());}
        catch(InvocationTargetException error){if(!(error.getCause() instanceof IOException))throw error;oldFail=true;}
        try {newWindow=new TranscriptStore(cache.toFile(),SERVER,ACCOUNT,MACHINE).read("thread");actual=newWindow.snapshot();}catch(IOException error){newFail=true;}
        check(oldFail==newFail,"acceptance changed: "+name);
        if(!oldFail){
            if(v1){expected.remove("epoch");actual.remove("epoch");}check(expected.equals(actual),"complete model changed: "+name);
            // snapshot不包含被localId去重行的source别名；真实查找也必须与原窗口一致。
            for(String[] query:new String[][]{{"skipped",null},{"first",null},{null,"same-local"},{"old",null},{"source-2400",null}}){
                Object oldEntry=oldWindow.getClass().getMethod("findSource",String.class,String.class).invoke(oldWindow,query[0],query[1]);
                TranscriptWindow.Entry newEntry=newWindow.findSource(query[0],query[1]);
                check((oldEntry==null)==(newEntry==null),"source alias presence changed: "+name);
                if(oldEntry!=null)check(oldEntry.getClass().getField("id").getInt(oldEntry)==newEntry.id,"source alias number changed: "+name);
                Object oldSegment=oldWindow.getClass().getMethod("findSegment",String.class,String.class).invoke(oldWindow,query[0],query[1]);
                TranscriptWindow newSegment=newWindow.findSegment(query[0],query[1]);
                check((oldSegment==null)==(newSegment==null),"archived source lookup changed: "+name);
                if(oldSegment!=null&&!v1)check(oldSegment.getClass().getMethod("epoch").invoke(oldSegment).equals(newSegment.epoch()),"archived source identity changed: "+name);
            }
            accepted++;
        }else rejected++;
        check(Arrays.equals(bytes,Files.readAllBytes(file)),"read changed cache: "+name);cases++;
    }

    /** 夹具解析不会代替被测文件读取或决定接受边界。 */
    private static JsonObject parse(String text){return JsonParser.parseString(text).getAsJsonObject();}
    /** 有界构造合成正文，不读取任何本地会话。 */
    private static String repeat(String text,int count){StringBuilder value=new StringBuilder();for(int i=0;i<count;i++)value.append(text);return value.toString();}
    /** 固定断言不输出合成正文或本机完整数据路径。 */
    private static void check(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
}
