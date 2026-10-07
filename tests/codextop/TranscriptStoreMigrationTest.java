package com.butang.codextop;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 只用独立临时合成缓存验证升级、旧根和失败边界。 */
public final class TranscriptStoreMigrationTest {
    static int checks;
    static Path base;
    public static void main(String[] args) throws Exception {
        base = Files.createTempDirectory("history-migration-").toRealPath();
        try {
            migrationAndRollback(); rootAndBrokenIndex(); changedLegacyAndInterruptedMigration(); capacity();
            System.out.println("TranscriptStoreMigration PASS checks=" + checks);
        } finally { try (java.util.stream.Stream<Path> paths = Files.walk(base)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path); } }
    }
    static TranscriptStore store(Path root) { return new TranscriptStore(root.toFile(), "s", "a", "m"); }
    static Path file(Path root, String remote, String suffix) { return root.resolve(TranscriptStore.digest("s\na\nm")).resolve(TranscriptStore.digest(remote)+suffix); }
    static TranscriptWindow fixture(int from, int to) throws Exception { TranscriptWindow w=new TranscriptWindow(); w.prepend(page(from,to,true)); return w; }
    static void legacy(Path root,String remote,TranscriptWindow w)throws Exception {
        Path path=file(root,remote,".json"); Files.createDirectories(path.getParent()); Files.write(path,w.snapshot().toString().getBytes(StandardCharsets.UTF_8));
    }
    static void migrationAndRollback()throws Exception {
        Path root=base.resolve("migration"); TranscriptWindow original=fixture(0,2000); JsonObject expected=original.snapshot(); legacy(root,"r",original);
        byte[] old=Files.readAllBytes(file(root,"r",".json")); TranscriptStore store=store(root);
        TranscriptWindow loaded=store.read("r"); check(loaded.snapshot().equals(expected),"legacy read lost content");
        check(Arrays.equals(old,Files.readAllBytes(file(root,"r",".json")))&&!Files.exists(file(root,"r",".window")),"read forced migration write");
        Path preExport=base.resolve("legacy-export"); store.exportForRollback("r",loaded,preExport);
        check(Arrays.equals(old,Files.readAllBytes(file(root,"r",".json")))&&!Files.exists(file(root,"r",".json.imported")),"legacy export mutated live cache");
        store.write("r",loaded);
        check(!Files.exists(file(root,"r",".json"))&&Arrays.equals(old,Files.readAllBytes(file(root,"r",".json.imported"))),"legacy backup changed");
        TranscriptWindow lazy=store(root).read("r");
        check(lazy.size()==2000&&lazy.exportIndexedSegments().get(0).rows.get(0).body.resident()==null,"reopen eager or lost identities");
        check(lazy.before(0,30).size()==30&&lazy.snapshot().equals(expected),"window or full export changed");
        byte[] first=Files.readAllBytes(file(root,"r",".window")); lazy.append(delta(2000)); store.write("r",lazy);
        check(Arrays.equals(first,Files.readAllBytes(file(root,"r",".window.previous"))),"previous is not old committed index");
        Path exported=base.resolve("rollback"); byte[] before=Files.readAllBytes(file(root,"r",".window")); store.exportForRollback("r",lazy,exported);
        check(Arrays.equals(before,Files.readAllBytes(file(root,"r",".window"))),"rollback export mutated live index");
        check(TranscriptWindow.restore(JsonParser.parseString(new String(Files.readAllBytes(file(exported,"r",".json")),StandardCharsets.UTF_8)).getAsJsonObject()).snapshot().equals(lazy.snapshot()),"rollback omitted new records");
        Path previous=file(root,"r",".window.previous"); byte[] protectedPrevious=Files.readAllBytes(previous); Files.delete(file(root,"r",".window"));
        io(()->store.read("r"),"missing current used older legacy backup");
        io(()->store.write("r",new TranscriptWindow()),"missing current overwritten from empty root");
        check(Arrays.equals(protectedPrevious,Files.readAllBytes(previous)),"missing current damaged previous");
    }
    static void rootAndBrokenIndex()throws Exception {
        Path root=base.resolve("roots"); TranscriptStore store=store(root); TranscriptWindow old=store.read("r"); old.prepend(page(0,10,true)); store.write("r",old);
        TranscriptWindow current=old.acceptLatest(page(30,35,true)); store.write("r",current); byte[] newest=Files.readAllBytes(file(root,"r",".window"));
        io(()->store.write("r",old),"old root overwrote current generation");
        io(()->old.acceptLatest(page(50,55,true)),"archived root stole token");
        io(()->store.write("r",new TranscriptWindow()),"fresh fallback overwritten cache");
        check(Arrays.equals(newest,Files.readAllBytes(file(root,"r",".window"))),"rejected stale writes changed index");
        byte[] bad=newest.clone(); bad[bad.length-1]^=1; Files.write(file(root,"r",".window"),bad);
        io(()->store.read("r"),"corrupt index turned empty"); io(()->store.write("r",new TranscriptWindow()),"corrupt index overwritten");
        check(Arrays.equals(bad,Files.readAllBytes(file(root,"r",".window"))),"corrupt original not retained");
    }
    static void changedLegacyAndInterruptedMigration()throws Exception {
        Path root=base.resolve("changed"); legacy(root,"r",fixture(0,10)); TranscriptStore store=store(root); TranscriptWindow loaded=store.read("r");
        legacy(root,"r",fixture(0,11)); byte[] changed=Files.readAllBytes(file(root,"r",".json"));
        io(()->store.write("r",loaded),"changed legacy replaced by stale import"); check(Arrays.equals(changed,Files.readAllBytes(file(root,"r",".json"))),"stale import changed original");
        TranscriptWindow reloaded=store.read("r"); TranscriptPersistenceToken token=reloaded.persistenceToken();
        IndexedTranscriptStore indexed=new IndexedTranscriptStore(root,"s","a","m");
        indexed.write("r",indexed.resume("r",reloaded,null,token.legacySha256()));
        check(Files.exists(file(root,"r",".json"))&&Files.exists(file(root,"r",".window")),"did not simulate committed-index crash window");
        TranscriptWindow recovered=store(root).read("r");
        check(recovered.size()==11&&!Files.exists(file(root,"r",".json"))&&Files.exists(file(root,"r",".json.imported")),"receipt did not recover same legacy");
        legacy(root,"r",fixture(0,12)); byte[] index=Files.readAllBytes(file(root,"r",".window"));
        io(()->store.read("r"),"foreign old APK write ignored"); io(()->store.write("r",recovered),"foreign old APK write overwritten");
        check(Arrays.equals(index,Files.readAllBytes(file(root,"r",".window"))),"conflict damaged index");
    }
    static void capacity()throws Exception {
        Path root=base.resolve("capacity"); legacy(root,"r",fixture(0,100)); byte[] old=Files.readAllBytes(file(root,"r",".json"));
        TranscriptStore tight=new TranscriptStore(root.toFile(),"s","a","m",old.length+100); TranscriptWindow window=tight.read("r");
        io(()->tight.write("r",window),"migration ignored old+new peak budget");
        check(Arrays.equals(old,Files.readAllBytes(file(root,"r",".json")))&&!Files.exists(file(root,"r",".window")),"capacity rejection changed legacy");
        Path bodies=file(root,"r",".bodies"); if(Files.exists(bodies))try(java.util.stream.Stream<Path> paths=Files.list(bodies)){check(paths.count()==0,"capacity failure wrote body bytes");}
        Path freshRoot=base.resolve("fresh"); TranscriptStore fresh=store(freshRoot); TranscriptWindow orphan=fresh.read("r");
        Path dir=file(freshRoot,"r",".bodies"); Files.createDirectories(dir); new TranscriptBodyStore(dir).write(Collections.singletonList("{\"uncommitted\":1}".getBytes(StandardCharsets.UTF_8)));
        TranscriptWindow retry=fresh.read("r"); retry.prepend(page(0,2,true)); fresh.write("r",retry); check(fresh.read("r").size()==2,"uncommitted orphan blocked recovery");
    }
    static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    interface Throwing{void run()throws Exception;}
    static void io(Throwing action,String text)throws Exception{checks++;try{action.run();throw new AssertionError(text);}catch(IOException expected){}}
    private static JsonObject page(int from, int to, boolean more) {
        JsonObject page = new JsonObject(); JsonArray items = new JsonArray();
        for (int i = from; i < to; i++) items.add(item(i));
        page.add("items", items); page.addProperty("hasMore", more); page.addProperty("historyAvailability", "available");
        page.addProperty("nextCursor", more ? "older-" + from : null); page.addProperty("tailCursor", "tail-" + to); return page;
    }

    /** 单条增量沿原tail协议，并允许构造localId重复回显。 */
    private static JsonObject delta(int id) {
        JsonObject value = new JsonObject(); JsonArray items = new JsonArray(); items.add(item(id));
        value.add("items", items); value.addProperty("nextCursor", "delta-" + id); return value;
    }

    /** 正文足够长以区别有限范围读与读取全部blob；用户回显身份只在用户行出现。 */
    private static JsonObject item(int id) {
        JsonObject item = new JsonObject(); item.addProperty("id", "row-" + id); item.addProperty("localId", id % 3 == 0 ? "local-" + id : null);
        item.addProperty("createdAtMs", 1700000000000L + id); JsonObject raw = new JsonObject(), content = new JsonObject();
        raw.addProperty("role", id % 3 == 0 ? "user" : "agent"); content.addProperty("type", "text");
        char[] fill = new char[256]; Arrays.fill(fill, 'x'); content.addProperty("text", "合成正文\n" + id + new String(fill));
        raw.add("content", content); item.add("raw", raw); return item;
    }

}
