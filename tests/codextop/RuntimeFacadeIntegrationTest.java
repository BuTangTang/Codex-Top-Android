package com.butang.codextop;
import java.nio.file.Path;
/** 执行真实Runtime保存、索引门面与全局容量owner，不替代回收或待发算法。 */
public final class RuntimeFacadeIntegrationTest {
    /** 前像可仅指定两参保存方法，其余源码与本组断言完全相同。 */
    public static void main(String[] args)throws Exception {
        Path runtime=args.length==0?Path.of("TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java"):Path.of(args[0]);
        RuntimeLatestSegmentTest.runScenarios(runtime, CASES,"protectedRootsAndOutbox();System.out.println(\"RuntimeFacadeIntegration: scenarios=1 failures=0\");");
    }
    private static final String CASES="""
        /** 容量按真实物理量设限；已载根不可淘汰，解除pin后才按原预算写入并确认回显。 */
        static void protectedRootsAndOutbox()throws Exception {
            TranscriptWindow currentRoot=cache("A,B");reset(currentRoot);try{
                StringJoiner olderIds=new StringJoiner(",");for(int i=0;i<100;i++)olderIds.add("P"+i);
                TranscriptWindow other=cache(olderIds.toString());
                store.write("other-synthetic",other,Arrays.asList(currentRoot,other));histories.put(43L,other);
                Path cacheRoot=directory.resolve("history");long bytes=new TranscriptCacheBudget(cacheRoot).inventory().totalBytes;
                store=new TranscriptStore(cacheRoot.toFile(),"synthetic-server","synthetic-account","synthetic-machine",bytes+1);
                JsonObject extra=page("",false,null,"next-tail");extra.getAsJsonArray("items").add(item("C","echoed-later"));currentRoot.append(extra);
                outbox.put(new OutboxStore.Item("echoed-later","synthetic-remote","synthetic pending",-21,1));outbox.markSubmissionUncertain("echoed-later",true);
                check(!saveHistory(42L,"synthetic-remote",currentRoot),"live root evicted: save should reject insufficient protected capacity");
                check(store.read("other-synthetic").size()==100&&outbox.get("echoed-later")!=null,"capacity failure destroyed loaded root or confirmed pending");
                check(store.read("synthetic-remote").size()==2,"rejected write advanced committed manifest");
                histories.remove(43L);check(saveHistory(42L,"synthetic-remote",currentRoot),"released unrelated cache not reclaimed by real budget");
                check(store.read("synthetic-remote").size()==3&&outbox.get("echoed-later")==null,"successful committed echo did not confirm pending");
                check(store.read("other-synthetic").isEmpty(),"released cache did not use original eviction path");
                check(current.requests.isEmpty(),"local saving unexpectedly sent RPC");
                System.out.println("PASS actual Runtime all-root pins and save-before-outbox with real facade budget");
            }finally{cleanup();}
        }
    """;
}
