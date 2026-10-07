package com.butang.codextop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** 真实私有文件系统与原型owner，IO替身只计量/故障注入，正文全部是合成字节。 */
public final class TranscriptBodyStoreTest {
    private static int groups, assertions;
    private static Path workspace;

    /** 每个场景独立目录，清理只限本测试创建的数据。 */
    public static void main(String[] args)throws Exception{
        if (args[0].equals("crash-child")) { crashChild(Paths.get(args[1])); return; }
        workspace=Paths.get(args[0]).toAbsolutePath();Files.createDirectories(workspace);
        Path root=Files.createTempDirectory(workspace,"body-store-test-");
        try{
            packingAndPreciseRead(root.resolve("packing"));
            largeUnicodeAttachment(root.resolve("large"));
            duplicateReuseAndCorruption(root.resolve("dedup"));
            boundedReferences(root.resolve("refs"));
            totalCapacity(root.resolve("capacity"));
            writeFailures(root.resolve("write-fail"));
            readFailures(root.resolve("read-fail"));
            partialBatchFailure(root.resolve("batch-fail"));
            symlinkAndDirectoryChanges(root.resolve("paths"));
            immutableResult(root.resolve("immutable"));
            crashRecovery(root.resolve("crash"));
            System.out.println("TranscriptBodyStore: groups="+groups+" assertions="+assertions+" PASS");
        }finally{deleteTree(root);}
    }

    /** 200行合成JSON只产生少量64KiB块，读一行恰好读它的长度，不访问同块邻行或其他块。 */
    private static void packingAndPreciseRead(Path root)throws Exception{
        Files.createDirectories(root); CountingIo io=new CountingIo();TranscriptBodyStore store=new TranscriptBodyStore(root,500000,io);
        ArrayList<byte[]> rows=new ArrayList<>();long total=0;
        for(int i=0;i<200;i++){byte[] row=utf8("{\"id\":\"row-"+i+"\",\"raw\":{\"role\":\"agent\",\"content\":{\"type\":\"text\",\"text\":\""+repeat("合成🌌",100)+"\"}}}");rows.add(row);total+=row.length;}
        List<TranscriptBodyStore.Ref> refs=store.write(rows);
        check(refs.size()==200&&store.storedBlobCount()<10,"small rows were not packed");
        check(store.storedBytes()==total&&io.writtenBytes==total,"physical accounting or written bytes incorrect");
        long blobs=store.storedBlobCount();io.reset();
        int selected=121;TranscriptBodyStore.Body body=store.read(refs.get(selected));
        check(Arrays.equals(rows.get(selected),body.toByteArray()),"selected row mismatch");
        check(io.readBytes==rows.get(selected).length&&io.readPaths.size()==1&&io.openReads==1,"single Ref read scanned another body");
        check(io.maxReadRequest<=8192,"read allocated/requested above 8KiB segment");
        check(io.writtenBytes==0&&io.moves==0,"read mutated body store");
        io.reset();TranscriptBodyStore reopened=new TranscriptBodyStore(root,500000,io);
        check(io.readBytes==0&&io.openReads==0&&reopened.storedBytes()==total&&reopened.storedBlobCount()==blobs,"reopen read body instead of only metadata");
        System.out.println("METRIC packing rows=200 blobs="+blobs+" storedBytes="+total+" selectedRowBytes="+rows.get(selected).length+" reopenBodyBytes=0");groups++;
    }

    /** 大于目标的合法单行独立成块，中文跨8KiB段、附件封套和原JSON字节完全保持。 */
    private static void largeUnicodeAttachment(Path root)throws Exception{
        Files.createDirectories(root);CountingIo io=new CountingIo();TranscriptBodyStore store=new TranscriptBodyStore(root,500000,io);
        String json="{\"id\":\"synthetic-attachment\",\"localId\":\"pending\",\"raw\":{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\""+repeat("边界中文🌌\\n\\\"",7000)+"\"},\"meta\":{\"attachments\":[{\"name\":\"合成🌌.png\",\"kind\":\"image\",\"path\":\"/synthetic/图片.png\",\"mimeType\":\"image/png\",\"sizeBytes\":9223372036854775807,\"sha256\":\""+repeat("a",64)+"\"}]}}}";
        byte[] bytes=utf8(json);check(bytes.length>TranscriptBodyStore.TARGET_BLOCK_BYTES,"large fixture too small");
        TranscriptBodyStore.Ref ref=store.write(Collections.singletonList(bytes)).get(0);io.reset();TranscriptBodyStore.Body body=store.read(ref);
        StringBuilder decoded=new StringBuilder();try(Reader reader=body.reader()){char[] chars=new char[113];int n;while((n=reader.read(chars))!=-1)decoded.append(chars,0,n);}
        check(decoded.toString().equals(json)&&Arrays.equals(body.toByteArray(),bytes),"Unicode, attachment JSON or escaping changed");
        ByteArrayOutputStream output=new ByteArrayOutputStream();body.writeTo(output);check(Arrays.equals(bytes,output.toByteArray()),"verified Body writeTo changed bytes");
        check(io.readBytes==bytes.length&&io.maxReadRequest<=8192&&store.storedBlobCount()==1,"large row not range-bounded or unexpectedly split into files");
        groups++;
    }

    /** 同样一批refs不重写正文；已有地址必须整块重验，邻行损坏不会被exists或选中行SHA掩盖。 */
    private static void duplicateReuseAndCorruption(Path root)throws Exception{
        Files.createDirectories(root);CountingIo io=new CountingIo();TranscriptBodyStore store=new TranscriptBodyStore(root,100000,io);
        List<byte[]> rows=Arrays.asList(utf8("{\"text\":\"first中文\"}"),utf8("{\"text\":\"second🌌\"}"));
        List<TranscriptBodyStore.Ref> initial=store.write(rows);long bytes=store.storedBytes();io.reset();List<TranscriptBodyStore.Ref> repeated=store.write(rows);
        for(int i=0;i<initial.size();i++)sameRef(initial.get(i),repeated.get(i));
        check(io.writtenBytes==0&&io.moves==0&&io.readBytes==bytes&&store.storedBytes()==bytes&&store.storedBlobCount()==1,"duplicate write copied or skipped full validation");
        Path file=root.resolve(initial.get(0).blobSha256+".blob");byte[] corrupt=Files.readAllBytes(file);corrupt[corrupt.length-2]^=1;Files.write(file,corrupt);
        // 第一行未受影响，单行读没有偷偷读/验证同块别的正文。
        io.reset();check(Arrays.equals(store.read(initial.get(0)).toByteArray(),rows.get(0))&&io.readBytes==rows.get(0).length,"single row unexpectedly required full block read");
        io.reset();expectIo(()->store.write(rows));check(io.writtenBytes==0&&io.moves==0&&Arrays.equals(Files.readAllBytes(file),corrupt),"corrupt existing blob was trusted or replaced");
        io.reset();expectIo(()->store.read(initial.get(1)));check(io.readBytes==rows.get(1).length,"corrupt row verification read outside range");groups++;
    }

    /** 哈希和长整型攻击值在分配/IO前拒绝，真实小文件上伪造64MiB范围同样不读body。 */
    private static void boundedReferences(Path root)throws Exception{
        Files.createDirectories(root);CountingIo io=new CountingIo();TranscriptBodyStore store=new TranscriptBodyStore(root,TranscriptBodyStore.MAX_BYTES,io);
        TranscriptBodyStore.Ref good=store.write(Collections.singletonList(utf8("{}"))).get(0);
        List<TranscriptBodyStore.Ref> invalid=Arrays.asList(new TranscriptBodyStore.Ref("../escape",0,1,good.rowSha256),new TranscriptBodyStore.Ref(good.blobSha256.toUpperCase(),0,1,good.rowSha256),new TranscriptBodyStore.Ref(good.blobSha256,-1,1,good.rowSha256),new TranscriptBodyStore.Ref(good.blobSha256,0,0,good.rowSha256),new TranscriptBodyStore.Ref(good.blobSha256,Long.MAX_VALUE,1,good.rowSha256),new TranscriptBodyStore.Ref(good.blobSha256,1,Long.MAX_VALUE,good.rowSha256),new TranscriptBodyStore.Ref(good.blobSha256,0,TranscriptBodyStore.MAX_BYTES+1,good.rowSha256),new TranscriptBodyStore.Ref(good.blobSha256,0,TranscriptBodyStore.MAX_BYTES,good.rowSha256),new TranscriptBodyStore.Ref(good.blobSha256,2,1,good.rowSha256),new TranscriptBodyStore.Ref(good.blobSha256,0,1,"bad"));
        io.reset();for(TranscriptBodyStore.Ref ref:invalid)expectIo(()->store.read(ref));expectIo(()->store.read(null));
        check(io.openReads==0&&io.readBytes==0&&io.openWrites==0,"malicious Ref opened body or allocated via read");groups++;
    }

    /** 空、超预算批次在任何写入前拒绝，精确预算可用；重复引用不扩大物理用量，新块不能突破总量。 */
    private static void totalCapacity(Path root)throws Exception{
        Files.createDirectories(root);CountingIo io=new CountingIo();TranscriptBodyStore store=new TranscriptBodyStore(root,100,io);
        expectIo(()->store.write(null));expectIo(()->store.write(Collections.singletonList(new byte[0])));expectIo(()->store.write(Collections.singletonList(new byte[101])));
        check(io.openWrites==0&&store.storedBytes()==0,"invalid batch wrote a temporary");
        byte[] exact=new byte[100];Arrays.fill(exact,(byte)'x');List<TranscriptBodyStore.Ref> ref=store.write(Collections.singletonList(exact));store.write(Collections.singletonList(exact));
        check(store.storedBytes()==100&&store.storedBlobCount()==1&&store.read(ref.get(0)).length==100,"exact capacity/reuse failed");
        expectIo(()->store.write(Collections.singletonList(utf8("{}"))));check(store.storedBytes()==100&&blobCount(root)==1,"capacity exceeded");groups++;
    }

    /** open、部分write、close与原子move失败都不会留下正式块或交付refs，临时文件清理。 */
    private static void writeFailures(Path root)throws Exception{
        Files.createDirectories(root);
        for(String failure:new String[]{"open","write","close","move"}){
            Path scope=Files.createDirectory(root.resolve(failure));CountingIo io=new CountingIo();TranscriptBodyStore store=new TranscriptBodyStore(scope,100000,io);
            if(failure.equals("open"))io.failWriteOpen=true;if(failure.equals("write"))io.failWriteAfter=4096;if(failure.equals("close"))io.failWriteClose=true;if(failure.equals("move"))io.failMoveAt=1;
            expectIo(()->store.write(Collections.singletonList(utf8(repeat("synthetic",2500)))));
            check(store.storedBytes()==0&&store.storedBlobCount()==0&&entryCount(scope)==0,"failed block published or temp not cleaned: "+failure);
        }groups++;
    }

    /** open、半途read、close失败不能返回Body，既有块字节与计量不变。 */
    private static void readFailures(Path root)throws Exception{
        Files.createDirectories(root);
        for(String failure:new String[]{"open","read","close"}){
            Path scope=Files.createDirectory(root.resolve(failure));CountingIo io=new CountingIo();TranscriptBodyStore store=new TranscriptBodyStore(scope,100000,io);byte[] bytes=utf8(repeat("合成x",5000));TranscriptBodyStore.Ref ref=store.write(Collections.singletonList(bytes)).get(0);io.reset();
            if(failure.equals("open"))io.failReadOpen=true;if(failure.equals("read"))io.failReadAfter=8192;if(failure.equals("close"))io.failReadClose=true;
            expectIo(()->store.read(ref));check(Arrays.equals(Files.readAllBytes(scope.resolve(ref.blobSha256+".blob")),bytes)&&store.storedBytes()==bytes.length,"read failure mutated block");
        }groups++;
    }

    /** 第二块发布失败时整个write不返回Refs；先前完整孤块保留并计量，重试去重后补齐，不覆盖。 */
    private static void partialBatchFailure(Path root)throws Exception{
        Files.createDirectories(root);CountingIo io=new CountingIo();TranscriptBodyStore store=new TranscriptBodyStore(root,150000,io);
        List<byte[]> rows=Arrays.asList(utf8(repeat("a",40000)),utf8(repeat("b",40000)),utf8(repeat("c",40000)));io.failMoveAt=2;
        expectIo(()->store.write(rows));check(store.storedBytes()==40000&&store.storedBlobCount()==1&&entryCount(root)==1,"partial failure lost accounting or left partial block");
        io.reset();List<TranscriptBodyStore.Ref> refs=store.write(rows);check(refs.size()==3&&io.readBytes==40000&&io.writtenBytes==80000&&store.storedBytes()==120000&&store.storedBlobCount()==3,"retry failed to reuse committed orphan");groups++;
    }

    /** 静态目录/块软链、scope身份替换及不明文件均明确拒绝；不触碰链接目标。 */
    private static void symlinkAndDirectoryChanges(Path root)throws Exception{
        Files.createDirectories(root);Path actual=Files.createDirectory(root.resolve("actual")),link=root.resolve("link");Files.createSymbolicLink(link,actual);
        expectIo(()->new TranscriptBodyStore(link));Path nested=Files.createDirectory(actual.resolve("nested"));expectIo(()->new TranscriptBodyStore(link.resolve("nested")));Files.delete(nested);
        CountingIo io=new CountingIo();TranscriptBodyStore store=new TranscriptBodyStore(actual,1000,io);byte[] row=utf8("{\"text\":\"safe\"}");TranscriptBodyStore.Ref ref=store.write(Collections.singletonList(row)).get(0);Path block=actual.resolve(ref.blobSha256+".blob"),outside=root.resolve("outside");Files.write(outside,row);Files.delete(block);Files.createSymbolicLink(block,outside);io.reset();
        expectIo(()->store.read(ref));expectIo(()->store.write(Collections.singletonList(row)));check(io.openReads==0&&io.openWrites==0&&Arrays.equals(Files.readAllBytes(outside),row),"symlink followed or target altered");
        Files.delete(block);Files.write(block,row);
        Path moved=root.resolve("moved");Files.move(actual,moved);Files.createDirectory(actual);expectIo(()->store.read(ref));expectIo(()->store.write(Collections.singletonList(row)));check(Files.exists(moved.resolve(ref.blobSha256+".blob"))&&entryCount(actual)==0,"replaced scope got modified");
        Path unknown=Files.createDirectory(root.resolve("unknown"));Files.write(unknown.resolve("unexpected"),new byte[]{1});expectIo(()->new TranscriptBodyStore(unknown));groups++;
    }

    /** 返回列表与Body内部字节不能被调用者修改，两个Reader独立定位且关闭互不污染。 */
    private static void immutableResult(Path root)throws Exception{
        Files.createDirectories(root);TranscriptBodyStore store=new TranscriptBodyStore(root);List<TranscriptBodyStore.Ref> refs=store.write(Collections.singletonList(utf8("中文🌌")));
        try{refs.clear();throw new AssertionError("mutable ref list");}catch(UnsupportedOperationException expected){assertions++;}
        TranscriptBodyStore.Body body=store.read(refs.get(0));byte[] copy=body.toByteArray();copy[0]=0;check(new String(body.toByteArray(),StandardCharsets.UTF_8).equals("中文🌌"),"Body exposed mutable data");
        Reader one=body.reader(),two=body.reader();check(one.read()==two.read(),"reader state shared");one.close();check(two.read()=='文',"closing one reader changed another");two.close();groups++;
    }

    /** 预建部分/空临时块及真实进程halt遗留均不阻断已提交正文；只清自有精确名字。 */
    private static void crashRecovery(Path root)throws Exception{
        Files.createDirectories(root);TranscriptBodyStore store=new TranscriptBodyStore(root);byte[] stable=utf8("{\"stable\":\"中文已提交\"}");TranscriptBodyStore.Ref ref=store.write(Collections.singletonList(stable)).get(0);
        Path partial=root.resolve(".pending-00000000-0000-4000-8000-000000000001.tmp"),empty=root.resolve(".pending-00000000-0000-4000-8000-000000000002.tmp");Files.write(partial,utf8("partial-unpublished"));Files.write(empty,new byte[0]);
        CountingIo io=new CountingIo();TranscriptBodyStore reopened=new TranscriptBodyStore(root,100000,io);
        check(!Files.exists(partial)&&!Files.exists(empty)&&reopened.storedBytes()==stable.length&&reopened.storedBlobCount()==1&&io.readBytes==0,"startup did not retire exact unpublished temps without reading body");
        check(Arrays.equals(reopened.read(ref).toByteArray(),stable),"published row lost after partial temp recovery");
        String javaExecutable=Paths.get(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(javaExecutable,"-Xmx64m","-cp",System.getProperty("java.class.path"),TranscriptBodyStoreTest.class.getName(),"crash-child",root.toString()).redirectErrorStream(true).redirectOutput(root.getParent().resolve("crash-child.log").toFile()).start();
        if(!child.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("synthetic crash child did not terminate");}
        check(child.exitValue()==23&&entryCount(root)==2&&blobCount(root)==1,"actual halt did not leave unpublished temporary");
        TranscriptBodyStore recovered=new TranscriptBodyStore(root);check(entryCount(root)==1&&recovered.storedBytes()==stable.length&&Arrays.equals(recovered.read(ref).toByteArray(),stable),"actual crash recovery blocked old body or published pending");
        Path unsafe=root.resolve(".pending-00000000-0000-4000-8000-000000000003.tmp"),outside=root.getParent().resolve("outside-pending");Files.write(outside,utf8("preserve"));Files.createSymbolicLink(unsafe,outside);expectIo(()->new TranscriptBodyStore(root));check(Files.isSymbolicLink(unsafe)&&new String(Files.readAllBytes(outside),StandardCharsets.UTF_8).equals("preserve"),"startup followed/deleted pending symlink");Files.delete(unsafe);
        Path unknown=root.resolve(".pending-not-a-uuid.tmp");Files.write(unknown,utf8("preserve"));expectIo(()->new TranscriptBodyStore(root));check(Files.exists(unknown)&&Files.exists(root.resolve(ref.blobSha256+".blob")),"unknown temporary or committed body deleted");groups++;
    }

    /** 独立子进程在真实临时块写完并close后、atomic move前强制退出，finally确实不会运行。 */
    private static void crashChild(Path root)throws Exception{
        TranscriptBodyStore.Io io=new TranscriptBodyStore.NioIo(){@Override public void atomicMove(Path temporary,Path target)throws IOException{Runtime.getRuntime().halt(23);}};
        TranscriptBodyStore store=new TranscriptBodyStore(root,100000,io);store.write(Collections.singletonList(utf8("{\"unpublished\":\"crash-only\"}")));throw new AssertionError("halt unexpectedly returned");
    }

    /** 包裹真实channel观察字节和IO故障，不替换原型打块、范围或哈希算法。 */
    private static final class CountingIo extends TranscriptBodyStore.NioIo {
        long readBytes,writtenBytes,failReadAfter=-1,failWriteAfter=-1;int openReads,openWrites,moves,maxReadRequest,failMoveAt;boolean failReadOpen,failWriteOpen,failReadClose,failWriteClose;Set<Path> readPaths=new HashSet<>();
        /** 每次新场景从干净计数和故障配置开始。 */
        void reset(){readBytes=writtenBytes=0;openReads=openWrites=moves=maxReadRequest=failMoveAt=0;failReadAfter=failWriteAfter=-1;failReadOpen=failWriteOpen=failReadClose=failWriteClose=false;readPaths.clear();}
        /** 真实open成功后才包裹channel，读写请求保持原owner给定的范围。 */
        @Override public SeekableByteChannel open(Path path,Set<OpenOption> options)throws IOException{
            final boolean write=options.contains(StandardOpenOption.WRITE);if(write){openWrites++;if(failWriteOpen)throw new IOException("synthetic write open");}else{openReads++;readPaths.add(path);if(failReadOpen)throw new IOException("synthetic read open");}
            final SeekableByteChannel channel=super.open(path,options);
            return new SeekableByteChannel(){
                /** 故障发生在真实读取之间，不伪造成功字节。 */
                public int read(ByteBuffer dst)throws IOException{maxReadRequest=Math.max(maxReadRequest,dst.remaining());if(failReadAfter>=0&&readBytes>=failReadAfter)throw new IOException("synthetic read");int n=channel.read(dst);if(n>0)readBytes+=n;return n;}
                /** 故障前最多写4KiB，以确定产生真实部分临时文件。 */
                public int write(ByteBuffer src)throws IOException{if(failWriteAfter>=0&&writtenBytes>=failWriteAfter)throw new IOException("synthetic write");int limit=src.limit();if(failWriteAfter>=0)src.limit(src.position()+Math.min(src.remaining(),4096));int n;try{n=channel.write(src);}finally{src.limit(limit);}if(n>0)writtenBytes+=n;return n;}
                /** 返回真实channel偏移。 */
                public long position()throws IOException{return channel.position();}
                /** 透传原owner设定的单条范围起点。 */
                public SeekableByteChannel position(long value)throws IOException{channel.position(value);return this;}
                /** 返回实际文件大小，测试不能用声明长度伪造文件。 */
                public long size()throws IOException{return channel.size();}
                /** 测试不主动截断文件。 */
                public SeekableByteChannel truncate(long size)throws IOException{channel.truncate(size);return this;}
                /** 关闭状态来自原channel。 */
                public boolean isOpen(){return channel.isOpen();}
                /** 即使注入close失败也先真正关闭，避免句柄泄漏。 */
                public void close()throws IOException{channel.close();if(write?failWriteClose:failReadClose)throw new IOException("synthetic close");}
            };
        }
        /** 在指定原子发布前失败，源临时文件仍存在，原owner负责清理。 */
        @Override public void atomicMove(Path temporary,Path target)throws IOException{moves++;if(moves==failMoveAt)throw new IOException("synthetic atomic move");super.atomicMove(temporary,target);}
    }

    /** 无返回值动作适配期望失败，异常必须由真实owner抛出IOException。 */
    private interface Operation{void run()throws Exception;}
    /** 拒绝操作不能交付返回值或吞掉IO失败。 */
    private static void expectIo(Operation action)throws Exception{try{action.run();throw new AssertionError("IOException expected");}catch(IOException expected){assertions++;}}
    /** 对照两个调用得到的完整不可变引用，不只核键。 */
    private static void sameRef(TranscriptBodyStore.Ref a,TranscriptBodyStore.Ref b){check(a.blobSha256.equals(b.blobSha256)&&a.rowSha256.equals(b.rowSha256)&&a.offset==b.offset&&a.length==b.length,"Ref changed on repeat write");}
    /** 统计正式块，不读取正文。 */
    private static long blobCount(Path path)throws Exception{try(Stream<Path> files=Files.list(path)){return files.filter(p->p.toString().endsWith(".blob")).count();}}
    /** 统计所有文件以发现泄漏临时文件。 */
    private static long entryCount(Path path)throws Exception{try(Stream<Path> files=Files.list(path)){return files.count();}}
    /** 合成内容按原UTF8编码。 */
    private static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}
    /** Java8合成文本工具。 */
    private static String repeat(String value,int count){StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(value);return result.toString();}
    /** 仅清理本次独立目录，不跟随符号链接。 */
    private static void deleteTree(Path root)throws Exception{try(Stream<Path> paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);}}
    /** 保留精确场景断言计数。 */
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
}
