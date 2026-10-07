package com.butang.codextop;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 私有接入候选：原历史队列调用的跨scope缓存预算helper，无后台线程、正文读取或独立同步owner。
 * 唯一串行writer和可信应用私有root是前提；Runtime必须传全量活会话pins，当前saved自动保护。
 * 本片只淘汰整会话，不回收活动会话跨代blob，不把pinnedEpochs当正文可达性证明。
 */
public final class TranscriptCacheBudget {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    private final Path root;
    private final long maxBytes;
    private final MetadataIo io;

    /** 只持有缓存根，不在预检阶段创建目录或读取正文。 */
    public TranscriptCacheBudget(Path root) { this(root, MAX_BYTES, new NioMetadataIo()); }

    /** 合成场景用小预算复用同一淘汰owner，不改变正式64MiB额度。 */
    TranscriptCacheBudget(Path root,long maxBytes,MetadataIo io) {
        if(root==null||io==null||maxBytes<1||maxBytes>MAX_BYTES)throw new IllegalArgumentException("invalid cache budget configuration");
        this.root=root.toAbsolutePath().normalize();this.maxBytes=maxBytes;this.io=io;
    }

    /** 完整枚举所有合法scope的文件元数据；不会读取任何正文或校验body哈希。 */
    public Inventory inventory() throws IOException { return scan().inventory(); }

    /**
     * 写前预留本次额外物理峰值。预检全局目录并算足够可回收量后才删，空间不够时零删除。
     * saved与所有活根整会话pins保持；调用后必须在同一串行历史操作内立即执行已估算的写入。
     */
    public Result ensureCapacity(CacheKey saved,long additionalPeakBytes,Set<CacheKey> loadedPins)throws IOException {
        if(saved==null||loadedPins==null||additionalPeakBytes<0)throw new IOException("invalid cache capacity request");
        HashSet<CacheKey> protectedKeys=new HashSet<>(loadedPins);
        if(protectedKeys.contains(null))throw new IOException("invalid live conversation pin");
        protectedKeys.add(saved);
        Scan scan=scan();
        if(additionalPeakBytes>maxBytes)throw new CapacityException(scan.totalBytes,additionalPeakBytes,protectedBytes(scan,protectedKeys));
        long allowedExisting=maxBytes-additionalPeakBytes;
        if(scan.totalBytes<=allowedExisting)return new Result(scan.totalBytes,scan.totalBytes,additionalPeakBytes,Collections.emptyList());
        long required=scan.totalBytes-allowedExisting,eligible=0;
        ArrayList<Conversation> candidates=new ArrayList<>();
        for(Conversation conversation:scan.conversations.values())if(!protectedKeys.contains(conversation.key)){
            eligible=add(eligible,conversation.bytes);candidates.add(conversation);
        }
        if(eligible<required)throw new CapacityException(scan.totalBytes,additionalPeakBytes,protectedBytes(scan,protectedKeys));
        candidates.sort(Comparator.comparingLong((Conversation c)->c.savedAt).thenComparing(c->c.key.scopeHash).thenComparing(c->c.key.remoteHash));
        ArrayList<Conversation> planned=new ArrayList<>();long reclaimed=0;
        for(Conversation candidate:candidates){if(reclaimed>=required)break;planned.add(candidate);reclaimed=add(reclaimed,candidate.bytes);}
        // 所有计划对象先统一复核；不能删了一部分后才发现后一个scope无权限/结构变化。
        verifyRoot(scan);
        for(Conversation conversation:planned)verifyConversation(conversation);
        ArrayList<CacheKey> evicted=new ArrayList<>();
        for(Conversation conversation:planned){evict(conversation);evicted.add(conversation.key);}
        return new Result(scan.totalBytes,scan.totalBytes-reclaimed,additionalPeakBytes,evicted);
    }

    /** 统计当前保护项实际文件字节，供容量失败诊断；不会投影任务正文。 */
    private static long protectedBytes(Scan scan,Set<CacheKey> keys)throws IOException {
        long bytes=0;for(Conversation conversation:scan.conversations.values())if(keys.contains(conversation.key))bytes=add(bytes,conversation.bytes);return bytes;
    }

    /** 按旧hash分区定位，已识别的新旧格式全计量；无关兄弟目录与文件不在正文预算内。 */
    private Scan scan()throws IOException {
        BasicFileAttributes rootAttributes=directoryChain(root,true);
        Scan scan=new Scan(rootAttributes);
        if(rootAttributes==null)return scan;
        for(Path scope:io.list(root)){
            String scopeHash=scope.getFileName().toString();if(!hash(scopeHash))continue;
            BasicFileAttributes scopeAttributes=directory(scope);
            for(Path path:io.list(scope)){
                String name=path.getFileName().toString();Named named=classify(name);if(named==null)continue;
                CacheKey key=new CacheKey(scopeHash,named.remoteHash);
                Conversation conversation=scan.conversations.get(key);
                if(conversation==null){conversation=new Conversation(key,scope,stamp(scopeAttributes));scan.conversations.put(key,conversation);}
                if(named.kind==Kind.BODIES){
                    BasicFileAttributes bodyAttributes=directory(path);
                    if(conversation.bodyDirectory!=null)throw new IOException("duplicate body directory");
                    conversation.bodyDirectory=path;conversation.bodyDirectoryStamp=stamp(bodyAttributes);
                    for(Path body:io.list(path)){
                        String child=body.getFileName().toString();
                        boolean blob=child.endsWith(".blob")&&hash(child.substring(0,child.length()-5));
                        boolean pending=bodyPending(child);
                        if(!blob&&!pending)throw new IOException("unrecognized body cache entry");
                        FileRecord file=regular(body,pending?Kind.BODY_PENDING:Kind.BODY);
                        conversation.add(file);conversation.bodyFiles.put(child,file);
                    }
                }else{
                    FileRecord file=regular(path,named.kind);conversation.add(file);conversation.metadataFiles.put(name,file);
                    if(named.kind==Kind.LEGACY||named.kind==Kind.IMPORTED||named.kind==Kind.CURRENT||named.kind==Kind.PREVIOUS)
                        conversation.savedAt=Math.max(conversation.savedAt,file.stamp.modifiedMillis);
                }
            }
        }
        for(Conversation conversation:scan.conversations.values())scan.totalBytes=add(scan.totalBytes,conversation.bytes);
        verifyRoot(scan);return scan;
    }

    /** 验证已捕获root身份，缺失根必须仍缺失，不让晚出现的缓存绕过预检计量。 */
    private void verifyRoot(Scan scan)throws IOException {
        BasicFileAttributes current=directoryChain(root,true);
        if(scan.rootStamp==null){if(current!=null)throw new IOException("cache root appeared during preflight");}
        else if(current==null||!scan.rootStamp.sameIdentity(stamp(current)))throw new IOException("cache root changed during preflight");
    }

    /** 只重核计划会话的元数据及完整文件集合，不读其他账号正文。 */
    private void verifyConversation(Conversation expected)throws IOException {
        if(!expected.scopeStamp.sameIdentity(stamp(directory(expected.scope))))throw new IOException("cache scope changed during preflight");
        HashSet<String> actualMetadata=new HashSet<>();Path actualBodies=null;
        for(Path path:io.list(expected.scope)){
            Named name=classify(path.getFileName().toString());if(name==null||!name.remoteHash.equals(expected.key.remoteHash))continue;
            if(name.kind==Kind.BODIES)actualBodies=path;else actualMetadata.add(path.getFileName().toString());
        }
        if(!actualMetadata.equals(expected.metadataFiles.keySet())||!Objects.equals(actualBodies,expected.bodyDirectory))throw new IOException("cache conversation changed during preflight");
        for(FileRecord file:expected.metadataFiles.values())verifyFile(file);
        if(expected.bodyDirectory!=null){
            if(!expected.bodyDirectoryStamp.sameIdentity(stamp(directory(expected.bodyDirectory))))throw new IOException("body directory changed during preflight");
            HashSet<String> names=new HashSet<>();for(Path body:io.list(expected.bodyDirectory))names.add(body.getFileName().toString());
            if(!names.equals(expected.bodyFiles.keySet()))throw new IOException("body set changed during preflight");
            for(FileRecord file:expected.bodyFiles.values())verifyFile(file);
        }
    }

    /** 先撤全部manifest/v2/索引pending，任一失败立即停止；它们全部消失后才允许删正文。 */
    private void evict(Conversation conversation)throws IOException {
        verifyConversation(conversation);
        ArrayList<FileRecord> metadata=new ArrayList<>(conversation.metadataFiles.values());
        metadata.sort(Comparator.comparingInt((FileRecord f)->f.kind.ordinal()).thenComparing(f->f.path.getFileName().toString()));
        for(FileRecord file:metadata){verifyFile(file);io.delete(file.path);}
        // 防止失败/意外变更后仍有任何可识别manifest或索引临时文件能引用待删除正文。
        for(Path path:io.list(conversation.scope)){
            Named name=classify(path.getFileName().toString());
            if(name!=null&&name.remoteHash.equals(conversation.key.remoteHash)&&name.kind!=Kind.BODIES)
                throw new IOException("cache metadata remains after retirement");
        }
        if(conversation.bodyDirectory!=null){
            if(!conversation.bodyDirectoryStamp.sameIdentity(stamp(directory(conversation.bodyDirectory))))throw new IOException("body directory changed during retirement");
            for(FileRecord file:conversation.bodyFiles.values()){verifyFile(file);io.delete(file.path);}
            io.delete(conversation.bodyDirectory);
        }
    }

    /** 删除前以普通文件、fileKey、长度与mtime核对原快照，拒绝软链和被换掉的内容。 */
    private void verifyFile(FileRecord expected)throws IOException {
        FileRecord actual=regular(expected.path,expected.kind);
        if(!expected.stamp.sameFile(actual.stamp))throw new IOException("cache file changed during preflight");
    }

    /** 元数据类型检查保持NOFOLLOW，文件字节数来自真实大小而不是index声明。 */
    private FileRecord regular(Path path,Kind kind)throws IOException {
        BasicFileAttributes attributes=io.stat(path);
        if(!attributes.isRegularFile()||attributes.isSymbolicLink()||attributes.fileKey()==null||attributes.size()<0)throw new IOException("cache file is not a regular owned file");
        return new FileRecord(path,kind,stamp(attributes));
    }

    /** 只允许真实目录，不跟随静态符号链接。 */
    private BasicFileAttributes directory(Path path)throws IOException {
        BasicFileAttributes attributes=io.stat(path);
        if(!attributes.isDirectory()||attributes.isSymbolicLink()||attributes.fileKey()==null)throw new IOException("cache directory is not an owned directory");
        return attributes;
    }

    /** 预检root祖先链；未创建的缓存根可视为空，已存在的软链或非目录则拒绝。 */
    private BasicFileAttributes directoryChain(Path path,boolean allowMissing)throws IOException {
        Path current=path.getRoot();if(current==null)throw new IOException("cache root is not absolute");BasicFileAttributes result=null;
        for(Path component:path){current=current.resolve(component);try{result=directory(current);}catch(NoSuchFileException missing){if(allowMissing)return null;throw missing;}}
        return result;
    }

    /** 合法旧v2、新索引/上一代、导入v2与已知临时名都归入同一会话额度。 */
    private static Named classify(String name) {
        if(name.length()<65||!hash(name.substring(0,64)))return null;
        String key=name.substring(0,64),suffix=name.substring(64);Kind kind;
        if(suffix.equals(".json"))kind=Kind.LEGACY;
        else if(suffix.equals(".json.imported"))kind=Kind.IMPORTED;
        else if(suffix.equals(".window"))kind=Kind.CURRENT;
        else if(suffix.equals(".window.previous"))kind=Kind.PREVIOUS;
        else if(suffix.equals(".bodies"))kind=Kind.BODIES;
        else if(suffix.equals(".tmp")||suffix.equals(".window.pending")||suffix.equals(".window.previous.pending")||indexPending(suffix))kind=Kind.INDEX_PENDING;
        else return null;
        return new Named(key,kind);
    }

    /** 兼容现有Files.createTempFile数字后缀及新UUID pending/previous-pending，不接受任意路径。 */
    private static boolean indexPending(String suffix) {
        String middle;
        if(suffix.startsWith(".previous-")&&suffix.endsWith(".pending"))middle=suffix.substring(10,suffix.length()-8);
        else if(suffix.startsWith("-")&&suffix.endsWith(".pending"))middle=suffix.substring(1,suffix.length()-8);
        else return false;
        if(uuid(middle))return true;
        if(middle.isEmpty()||middle.length()>24)return false;
        for(int i=0;i<middle.length();i++)if(middle.charAt(i)<'0'||middle.charAt(i)>'9')return false;
        return true;
    }

    /** 正文writer唯一已知的未发布文件名，纳入额度但不当正式body引用。 */
    private static boolean bodyPending(String name) { return name.length()==49&&name.startsWith(".pending-")&&name.endsWith(".tmp")&&uuid(name.substring(9,45)); }

    /** 限定生成器当前使用的小写标准UUID v4，避免猜测未知文件归属。 */
    private static boolean uuid(String value) { try{UUID uuid=UUID.fromString(value);return uuid.version()==4&&uuid.variant()==2&&uuid.toString().equals(value);}catch(IllegalArgumentException error){return false;} }

    /** scope及会话键必须为原64位小写SHA，不能携带分隔符。 */
    private static boolean hash(String value) { if(value==null||value.length()!=64)return false;for(int i=0;i<value.length();i++){char c=value.charAt(i);if(!(c>='0'&&c<='9'||c>='a'&&c<='f'))return false;}return true; }

    /** 大小累加溢出不是可回收空间，按IO失败停止而不进入删除。 */
    private static long add(long left,long right)throws IOException { if(right<0||left>Long.MAX_VALUE-right)throw new IOException("cache byte count overflow");return left+right; }

    /** 只截取元数据用于预检，不读取哈希或正文。 */
    private static Stamp stamp(BasicFileAttributes value){return new Stamp(value.fileKey(),value.size(),value.lastModifiedTime());}

    /** Runtime传入已摘要化的完整归属键，与UI是否可见无关。 */
    public static final class CacheKey {
        public final String scopeHash,remoteHash;
        /** 验证路径键后保存；原账号、机器和会话原文不在本helper内。 */
        public CacheKey(String scopeHash,String remoteHash){if(!hash(scopeHash)||!hash(remoteHash))throw new IllegalArgumentException("invalid conversation cache key");this.scopeHash=scopeHash;this.remoteHash=remoteHash;}
        /** 完整scope+remote联合身份，不能跨账号误匹配pin。 */
        @Override public boolean equals(Object value){if(!(value instanceof CacheKey))return false;CacheKey key=(CacheKey)value;return scopeHash.equals(key.scopeHash)&&remoteHash.equals(key.remoteHash);}
        /** 与equals保持相同完整归属。 */
        @Override public int hashCode(){return 31*scopeHash.hashCode()+remoteHash.hashCode();}
    }

    /** 外部只取得汇总元数据，不能借此修改内部删除清单。 */
    public static final class Inventory {
        public final long totalBytes;public final List<Usage> conversations;
        /** 复制不可变用量视图。 */
        private Inventory(long totalBytes,List<Usage> conversations){this.totalBytes=totalBytes;this.conversations=Collections.unmodifiableList(new ArrayList<>(conversations));}
    }

    /** 单会话的新旧格式计量，pending也在真实物理字节中。 */
    public static final class Usage {
        public final CacheKey key;public final long bytes,lastSavedMillis;public final int files;
        /** 只暴露归属和计数，不泄露删除路径。 */
        private Usage(Conversation value){key=value.key;bytes=value.bytes;lastSavedMillis=value.savedAt;files=value.metadataFiles.size()+value.bodyFiles.size();}
    }

    /** 成功返回只表示当前空间已预留，不能代替之后写入、索引发布或设备验收。 */
    public static final class Result {
        public final long beforeBytes,remainingBytes,reservedAdditionalBytes;public final List<CacheKey> evicted;
        /** 返回此次实际完全淘汰的会话，不把失败的部分删除算成功。 */
        private Result(long before,long remaining,long additional,List<CacheKey> evicted){beforeBytes=before;remainingBytes=remaining;reservedAdditionalBytes=additional;this.evicted=Collections.unmodifiableList(new ArrayList<>(evicted));}
    }

    /** 无足够非保护空间时零删除；调用方保留旧缓存并拒绝本次持久化。 */
    public static final class CapacityException extends IOException {
        public final long existingBytes,additionalBytes,protectedBytes;
        /** 只记数值，不记录会话或账号正文。 */
        private CapacityException(long existing,long additional,long protectedBytes){super("protected cache leaves insufficient capacity");existingBytes=existing;additionalBytes=additional;this.protectedBytes=protectedBytes;}
    }

    /** 类型顺序让可引用正文的全部manifest在任何body前撤销。 */
    private enum Kind { CURRENT,PREVIOUS,LEGACY,IMPORTED,INDEX_PENDING,BODIES,BODY,BODY_PENDING }
    /** 单个受识别文件的归属及类别。 */
    private static final class Named {
        final String remoteHash; final Kind kind;
        /** 分类结果不暴露路径，也不触发IO。 */
        Named(String remoteHash,Kind kind){this.remoteHash=remoteHash;this.kind=kind;}
    }
    /** 单次文件元数据，不承担内容完整性校验职责。 */
    private static final class Stamp {
        final Object key; final long size,modifiedMillis; final FileTime modifiedTime;
        /** 保留文件系统提供的完整mtime精度，毫秒只用于沿旧保存时间排序。 */
        Stamp(Object key,long size,FileTime modifiedTime){this.key=key;this.size=size;this.modifiedTime=modifiedTime;this.modifiedMillis=modifiedTime.toMillis();}
        /** 目录身份不随子文件正常增删而变化。 */
        boolean sameIdentity(Stamp other){return key.equals(other.key);}
        /** 文件预检比较完整mtime，避免同毫秒内的可观察变化被截断掩盖。 */
        boolean sameFile(Stamp other){return sameIdentity(other)&&size==other.size&&modifiedTime.equals(other.modifiedTime);}
    }
    /** 删除计划中的真实文件及预检快照。 */
    private static final class FileRecord {
        final Path path; final Kind kind; final Stamp stamp;
        /** 仅登记已通过NOFOLLOW检查的文件。 */
        FileRecord(Path path,Kind kind,Stamp stamp){this.path=path;this.kind=kind;this.stamp=stamp;}
    }
    /** 同一会话所有格式共用预算，元数据与body删除有明确阶段顺序。 */
    private static final class Conversation {
        final CacheKey key;final Path scope;final Stamp scopeStamp;long bytes,savedAt=Long.MIN_VALUE;Path bodyDirectory;Stamp bodyDirectoryStamp;
        final Map<String,FileRecord> metadataFiles=new LinkedHashMap<>(),bodyFiles=new LinkedHashMap<>();
        /** 完整scope归属用于全局pin与稳定排序。 */
        Conversation(CacheKey key,Path scope,Stamp scopeStamp){this.key=key;this.scope=scope;this.scopeStamp=scopeStamp;}
        /** 所有格式共用溢出受检的真实文件大小计数。 */
        void add(FileRecord file)throws IOException{bytes=TranscriptCacheBudget.add(bytes,file.stamp.size);}
    }
    /** 一轮全局枚举与其root身份，用于不够空间时不执行任何删除。 */
    private static final class Scan {
        final Stamp rootStamp;final Map<CacheKey,Conversation> conversations=new HashMap<>();long totalBytes;
        /** 缺失根表示尚未创建的空缓存，预检不创建它。 */
        Scan(BasicFileAttributes root){rootStamp=root==null?null:stamp(root);}
        /** 只生成不可变计数视图，不泄露可执行删除清单。 */
        Inventory inventory(){ArrayList<Usage> values=new ArrayList<>();for(Conversation value:conversations.values())values.add(new Usage(value));return new Inventory(totalBytes,values);}
    }

    /** 可用IO只包含元数据与删除，没有正文open/read/hash接口；测试只在此边界注入失败。 */
    interface MetadataIo {BasicFileAttributes stat(Path path)throws IOException;List<Path> list(Path path)throws IOException;void delete(Path path)throws IOException;}

    /** 生产候选默认执行真实NOFOLLOW元数据和删除；不安装自定义FS或后台扫描器。 */
    static class NioMetadataIo implements MetadataIo {
        /** 保持路径最终节点不跟随链接。 */
        @Override public BasicFileAttributes stat(Path path)throws IOException{return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        /** 列表在当前调用内关闭，不保留目录句柄或开启监视线程。 */
        @Override public List<Path> list(Path path)throws IOException{ArrayList<Path> result=new ArrayList<>();try(DirectoryStream<Path> files=Files.newDirectoryStream(path)){for(Path file:files)result.add(file);}return result;}
        /** 删除失败原样抛出，禁止继续把有引用正文当孤块。 */
        @Override public void delete(Path path)throws IOException{Files.delete(path);}
    }
}
