package com.butang.codextop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 所有缓存均为独立合成文件；调用真实Budget算法，故障只注入元数据/删除系统边界。 */
public final class TranscriptCacheBudgetTest {
    private static int groups,assertions;
    private static final String UUID="00000000-0000-4000-8000-000000000001";
    private static final TranscriptCacheBudget.CacheKey SAVED=key('f','f');

    /** 分场景目录互不共享，只清理由本专项创建的临时文件。 */
    public static void main(String[] args)throws Exception {
        Path work=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(work);Path root=Files.createTempDirectory(work,"retention-");
        try{
            mixedInventory(root.resolve("inventory"));
            globalEvictionAndPins(root.resolve("global"));
            insufficientIsNoDelete(root.resolve("shortage"));
            stableTieAndOrphans(root.resolve("order"));
            fullPreflightFailure(root.resolve("preflight"));
            manifestFailurePreservesBodies(root.resolve("manifest-failure"));
            bodyFailureHasNoDanglingManifest(root.resolve("body-failure"));
            staticLinksAndUnexpectedFiles(root.resolve("unsafe"));
            changedFileStopsBeforeDeletion(root.resolve("changed"));
            emptyMissingAndInvalid(root.resolve("empty"));
            System.out.println("TranscriptCacheBudget: groups="+groups+" assertions="+assertions+" PASS; metadata-only IO interface");
        }finally{deleteTree(root);}
    }

    /** 新旧v2、导入备份、两代索引、正文与已存在pending都跨scope共享计量，不读取文件内容。 */
    private static void mixedInventory(Path root)throws Exception {
        Files.createDirectories(root);TranscriptCacheBudget.CacheKey a=key('a','1'),b=key('b','1');
        put(root,a,".json",11,10);put(root,a,".json.imported",13,11);put(root,a,".window",17,12);put(root,a,".window.previous",19,9);
        put(root,a,".tmp",23,99);put(root,a,"-"+UUID+".pending",29,99);put(root,a,".previous-"+UUID+".pending",31,99);put(root,a,"-123456.pending",37,99);
        body(root,a,hash('2')+".blob",41);body(root,a,".pending-"+UUID+".tmp",43);put(root,b,".json",47,20);
        Path outbox=Files.createDirectories(root.resolve("outbox"));Files.write(outbox.resolve("preserved"),new byte[400]);Files.write(scope(root,a).resolve("unrelated-note"),new byte[300]);
        ObservedIo io=new ObservedIo();TranscriptCacheBudget budget=new TranscriptCacheBudget(root,1000,io);TranscriptCacheBudget.Inventory inventory=budget.inventory();
        check(inventory.totalBytes==311&&inventory.conversations.size()==2,"mixed global formats not counted exactly once");
        TranscriptCacheBudget.Usage usage=null;for(TranscriptCacheBudget.Usage value:inventory.conversations)if(value.key.equals(a))usage=value;
        check(usage!=null&&usage.bytes==264&&usage.files==10&&usage.lastSavedMillis==12,"pending changed success-save timestamp or format count");
        TranscriptCacheBudget.Result result=budget.ensureCapacity(SAVED,689,Collections.emptySet());check(result.beforeBytes==311&&result.remainingBytes==311&&result.evicted.isEmpty()&&io.deletes.isEmpty(),"exact global capacity unexpectedly trimmed");
        check(Files.size(outbox.resolve("preserved"))==400&&Files.size(scope(root,a).resolve("unrelated-note"))==300,"non-cache sibling was touched");groups++;
    }

    /** 当前saved即使未显式pin也保持；活历史的完整scope+remote pin不会串到另一账号同名会话。 */
    private static void globalEvictionAndPins(Path root)throws Exception {
        Files.createDirectories(root);TranscriptCacheBudget.CacheKey saved=key('a','1'),live=key('b','1'),old=key('c','1');
        put(root,saved,".json",30,1);put(root,live,".window",10,2);put(root,live,".window.previous",10,1);body(root,live,hash('3')+".blob",20);
        put(root,old,".json.imported",20,3);put(root,old,".window",20,3);body(root,old,hash('4')+".blob",20);
        ObservedIo io=new ObservedIo();TranscriptCacheBudget budget=new TranscriptCacheBudget(root,100,io);TranscriptCacheBudget.Result result=budget.ensureCapacity(saved,20,Collections.singleton(live));
        check(result.beforeBytes==130&&result.remainingBytes==70&&result.reservedAdditionalBytes==20&&result.evicted.size()==1&&result.evicted.get(0).equals(old),"saved/live protection or crossscope plan incorrect");
        check(Files.exists(file(root,saved,".json"))&&Files.exists(file(root,live,".window.previous"))&&Files.exists(bodyPath(root,live,hash('3')+".blob")),"protected source/current/previous/body removed");
        check(!Files.exists(file(root,old,".window"))&&!Files.exists(file(root,old,".json.imported"))&&!Files.exists(file(root,old,".bodies")),"old entire conversation not retired");
        check(lastMetadataBeforeBody(io.deletes),"body deletion happened before all metadata");groups++;
    }

    /** 即使未pin会话可腾出部分空间，不能足额时必须完全不删除；pending物理峰值也计入。 */
    private static void insufficientIsNoDelete(Path root)throws Exception {
        Files.createDirectories(root);TranscriptCacheBudget.CacheKey saved=key('a','1'),old=key('b','2');put(root,saved,".window",60,1);body(root,saved,hash('3')+".blob",20);put(root,saved,".previous-"+UUID+".pending",10,99);put(root,old,".json",20,2);
        ObservedIo io=new ObservedIo();TranscriptCacheBudget budget=new TranscriptCacheBudget(root,100,io);
        try{budget.ensureCapacity(saved,15,Collections.emptySet());throw new AssertionError("capacity failure expected");}
        catch(TranscriptCacheBudget.CapacityException expected){check(expected.existingBytes==110&&expected.additionalBytes==15&&expected.protectedBytes==90,"capacity diagnostics did not include pending");}
        check(io.deletes.isEmpty()&&budget.inventory().totalBytes==110&&Files.exists(file(root,old,".json")),"insufficient capacity caused partial eviction");groups++;
    }

    /** 无manifest的完整孤块最先释放；保存时间相同按完整scope/remote稳定排序。 */
    private static void stableTieAndOrphans(Path root)throws Exception {
        Files.createDirectories(root);TranscriptCacheBudget.CacheKey orphan=key('f','1'),first=key('a','2'),later=key('b','2');body(root,orphan,hash('9')+".blob",10);put(root,first,".json",20,100);put(root,later,".json",20,100);
        TranscriptCacheBudget budget=new TranscriptCacheBudget(root,100,new ObservedIo());TranscriptCacheBudget.Result result=budget.ensureCapacity(SAVED,80,Collections.emptySet());
        check(result.evicted.equals(Arrays.asList(orphan,first))&&result.remainingBytes==20&&Files.exists(file(root,later,".json")),"orphan/tie ordering changed");groups++;
    }

    /** 后续scope枚举错误必须阻止所有删除；不能看到前几条已够容量就提前结束预检。 */
    private static void fullPreflightFailure(Path root)throws Exception {
        Files.createDirectories(root);TranscriptCacheBudget.CacheKey a=key('a','1'),b=key('b','1');put(root,a,".json",50,1);put(root,b,".json",50,2);
        ObservedIo io=new ObservedIo();io.failList=scope(root,b);TranscriptCacheBudget budget=new TranscriptCacheBudget(root,50,io);expectIo(()->budget.ensureCapacity(SAVED,1,Collections.emptySet()));
        check(io.deletes.isEmpty()&&Files.exists(file(root,a,".json"))&&Files.exists(file(root,b,".json")),"preflight failure deleted earlier candidates");groups++;
    }

    /** previous删除失败后，current可已撤但所有正文保持；剩余previous永不引用已删块。 */
    private static void manifestFailurePreservesBodies(Path root)throws Exception {
        Files.createDirectories(root);TranscriptCacheBudget.CacheKey old=key('a','1');put(root,old,".window",10,1);put(root,old,".window.previous",10,1);put(root,old,".json",10,1);body(root,old,hash('2')+".blob",40);
        ObservedIo io=new ObservedIo();io.failDelete=file(root,old,".window.previous");TranscriptCacheBudget budget=new TranscriptCacheBudget(root,100,io);expectIo(()->budget.ensureCapacity(SAVED,100,Collections.emptySet()));
        check(!Files.exists(file(root,old,".window"))&&Files.exists(file(root,old,".window.previous"))&&Files.exists(file(root,old,".json"))&&Files.exists(bodyPath(root,old,hash('2')+".blob")),"metadata failure left dangling previous or deleted legacy");
        check(io.deletes.size()==1&&io.deletes.get(0).equals(file(root,old,".window")),"continued deletion after previous failure");groups++;
    }

    /** 全部索引撤销后body删除失败只会留下孤块；下一轮能继续收回，不再有可见manifest断refs。 */
    private static void bodyFailureHasNoDanglingManifest(Path root)throws Exception {
        Files.createDirectories(root);TranscriptCacheBudget.CacheKey old=key('a','1');put(root,old,".window",10,1);put(root,old,".window.previous",10,1);body(root,old,hash('2')+".blob",20);body(root,old,hash('3')+".blob",30);
        ObservedIo io=new ObservedIo();io.failAnyBody=true;TranscriptCacheBudget budget=new TranscriptCacheBudget(root,100,io);expectIo(()->budget.ensureCapacity(SAVED,100,Collections.emptySet()));
        check(!Files.exists(file(root,old,".window"))&&!Files.exists(file(root,old,".window.previous"))&&Files.exists(bodyPath(root,old,hash('2')+".blob"))&&Files.exists(bodyPath(root,old,hash('3')+".blob")),"body failure retained a manifest or deleted wrong stage");
        io.failAnyBody=false;TranscriptCacheBudget.Result result=budget.ensureCapacity(SAVED,100,Collections.emptySet());check(result.beforeBytes==50&&result.remainingBytes==0&&result.evicted.equals(Collections.singletonList(old))&&!Files.exists(file(root,old,".bodies")),"retry did not reclaim only unreferenced remainder");groups++;
    }

    /** scope/body软链、body未知文件均完整预检失败，不跟随到缓存外，也不先删除安全候选。 */
    private static void staticLinksAndUnexpectedFiles(Path root)throws Exception {
        Files.createDirectories(root);Path outside=Files.createDirectory(root.resolve("outside"));Files.write(outside.resolve("preserved"),new byte[20]);Path linked=root.resolve(hash('a'));Files.createSymbolicLink(linked,outside);
        ObservedIo io=new ObservedIo();TranscriptCacheBudget budget=new TranscriptCacheBudget(root,100,io);expectIo(()->budget.ensureCapacity(SAVED,100,Collections.emptySet()));check(io.deletes.isEmpty()&&Files.exists(outside.resolve("preserved")),"scope link followed");Files.delete(linked);
        TranscriptCacheBudget.CacheKey old=key('b','1');put(root,old,".window",10,1);Path directory=Files.createDirectories(file(root,old,".bodies"));Path bodyLink=directory.resolve(hash('c')+".blob");Files.createSymbolicLink(bodyLink,outside.resolve("preserved"));expectIo(()->budget.ensureCapacity(SAVED,100,Collections.emptySet()));check(io.deletes.isEmpty()&&Files.exists(file(root,old,".window")),"body link preflight partially evicted");Files.delete(bodyLink);
        Files.write(directory.resolve("unknown-file"),new byte[1]);expectIo(()->budget.ensureCapacity(SAVED,100,Collections.emptySet()));check(io.deletes.isEmpty()&&Files.exists(outside.resolve("preserved")),"unknown body ignored or unsafe deletion occurred");groups++;
    }

    /** 第一轮扫描后文件被换掉，在任何候选删除前的统一复核发现变化。 */
    private static void changedFileStopsBeforeDeletion(Path root)throws Exception {
        Files.createDirectories(root);TranscriptCacheBudget.CacheKey a=key('a','1'),b=key('b','1');put(root,a,".json",40,1);put(root,b,".json",40,2);
        ObservedIo io=new ObservedIo();io.changeOnSecondStat=file(root,b,".json");TranscriptCacheBudget budget=new TranscriptCacheBudget(root,100,io);expectIo(()->budget.ensureCapacity(SAVED,100,Collections.emptySet()));
        check(io.deletes.isEmpty()&&Files.exists(file(root,a,".json"))&&Files.size(file(root,b,".json"))==41,"changed later candidate caused partial deletion");groups++;
    }

    /** 缺失root不被创建，追加峰值过大和无效pin明确失败，零字节缓存也保持准确。 */
    private static void emptyMissingAndInvalid(Path root)throws Exception {
        ObservedIo io=new ObservedIo();TranscriptCacheBudget budget=new TranscriptCacheBudget(root,100,io);check(budget.inventory().totalBytes==0&&!Files.exists(root),"preflight created missing cache root");
        check(budget.ensureCapacity(SAVED,100,Collections.emptySet()).remainingBytes==0&&!Files.exists(root),"empty exact reservation mutated root");expectIo(()->budget.ensureCapacity(SAVED,101,Collections.emptySet()));expectIo(()->budget.ensureCapacity(SAVED,-1,Collections.emptySet()));expectIo(()->budget.ensureCapacity(null,0,Collections.emptySet()));Set<TranscriptCacheBudget.CacheKey> bad=new HashSet<>();bad.add(null);expectIo(()->budget.ensureCapacity(SAVED,0,bad));check(io.deletes.isEmpty(),"invalid request deleted files");groups++;
    }

    /** 只有stat/list/delete接口，所有数据内容仍由真实FS保存，本helper没有正文读取路径。 */
    private static final class ObservedIo extends TranscriptCacheBudget.NioMetadataIo {
        final List<Path> deletes=new ArrayList<>();Path failList,failDelete,changeOnSecondStat;boolean failAnyBody;int changedStats;
        /** 在指定文件第二次元数据观察前制造真实大小变化，模拟过时计划。 */
        @Override public BasicFileAttributes stat(Path path)throws IOException{if(path.equals(changeOnSecondStat)&&++changedStats==2)Files.write(path,new byte[41]);return super.stat(path);}
        /** 指定目录列举失败必须由原预算owner传播。 */
        @Override public List<Path> list(Path path)throws IOException{if(path.equals(failList))throw new IOException("synthetic list failure");return super.list(path);}
        /** 真实删除前注入失败；成功删除按调用顺序记录。 */
        @Override public void delete(Path path)throws IOException{if(path.equals(failDelete)||failAnyBody&&path.toString().endsWith(".blob"))throw new IOException("synthetic delete failure");super.delete(path);deletes.add(path);}
    }

    /** 检查只在全部可引用文件撤销后才进入body阶段。 */
    private static boolean lastMetadataBeforeBody(List<Path> paths){boolean bodies=false;for(Path path:paths){String name=path.getFileName().toString();if(name.endsWith(".blob")||name.endsWith(".bodies"))bodies=true;else if(bodies)return false;}return true;}
    /** 生成固定合法hash归属，不含真实账号或会话。 */
    private static TranscriptCacheBudget.CacheKey key(char scope,char remote){return new TranscriptCacheBudget.CacheKey(hash(scope),hash(remote));}
    /** Java8固定摘要形状样例。 */
    private static String hash(char value){char[] text=new char[64];Arrays.fill(text,value);return new String(text);}
    /** 合成分区路径，不隐式创建。 */
    private static Path scope(Path root,TranscriptCacheBudget.CacheKey key){return root.resolve(key.scopeHash);}
    /** 合成会话文件路径。 */
    private static Path file(Path root,TranscriptCacheBudget.CacheKey key,String suffix){return scope(root,key).resolve(key.remoteHash+suffix);}
    /** 合成正文块路径。 */
    private static Path bodyPath(Path root,TranscriptCacheBudget.CacheKey key,String name){return file(root,key,".bodies").resolve(name);}
    /** 真实字节文件按稳定保存时间创建，不解析其内容。 */
    private static void put(Path root,TranscriptCacheBudget.CacheKey key,String suffix,int size,long saved)throws Exception{Path path=file(root,key,suffix);Files.createDirectories(path.getParent());Files.write(path,new byte[size]);Files.setLastModifiedTime(path,FileTime.fromMillis(saved));}
    /** 正文或未发布临时块都使用真实文件长度计量。 */
    private static void body(Path root,TranscriptCacheBudget.CacheKey key,String name,int size)throws Exception{Path path=bodyPath(root,key,name);Files.createDirectories(path.getParent());Files.write(path,new byte[size]);}
    /** 可抛错动作适配真实API。 */
    private interface Action{void run()throws Exception;}
    /** 失败必须明确为IOException，不把缺失当空缓存继续删除。 */
    private static void expectIo(Action action)throws Exception{try{action.run();throw new AssertionError("IOException expected");}catch(IOException expected){assertions++;}}
    /** 只删除本测试新建的目录，不跟随软链。 */
    private static void deleteTree(Path root)throws Exception{try(Stream<Path> paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);}}
    /** 逐项保留行为断言。 */
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
