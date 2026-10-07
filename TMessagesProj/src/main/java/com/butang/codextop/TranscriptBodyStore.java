package com.butang.codextop;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 私有原型：可信应用scope内的不可变正文块。仅同一串行owner访问，输入数组在write返回前不可改动。
 * 不拥有manifest、回收或后台线程；不覆盖旧块。NOFOLLOW及目录身份检查拒绝静态链接与已观察到的置换，
 * 但本机JDK无SecureDirectoryStream，不能保证恶意并发父目录置换的TOCTOU安全；生产接入前须核平台边界。
 */
public final class TranscriptBodyStore {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final int TARGET_BLOCK_BYTES = 64 * 1024;
    private static final int SEGMENT_BYTES = 8 * 1024;
    private final Path directory;
    private final Object directoryKey;
    private final long maxBytes;
    private final Io io;
    private long storedBytes, storedBlobCount;

    /** 使用既有可信私有目录，不隐式创建或穿过符号链接目录。 */
    public TranscriptBodyStore(Path directory) throws IOException { this(directory, MAX_BYTES, new NioIo()); }

    /** 测试复用原预算逻辑及真实文件IO，仅替换channel/move系统边界以注入确定失败。 */
    TranscriptBodyStore(Path directory, long maxBytes, Io io) throws IOException {
        if (directory == null || io == null || maxBytes < 1 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("invalid body store configuration");
        this.directory = directory.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.io = io;
        checkDirectoryChain(this.directory);
        BasicFileAttributes attributes = attributes(this.directory);
        if (!attributes.isDirectory() || attributes.fileKey() == null) throw new IOException("body directory identity unavailable");
        this.directoryKey = attributes.fileKey();
        // 只读元数据计量现有块；单writer启动时清自己的未发布临时块，未知文件和软链仍拒绝。
        try (DirectoryStream<Path> files = Files.newDirectoryStream(this.directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (unpublishedName(name)) {
                    BasicFileAttributes pending = attributes(file);
                    if (!pending.isRegularFile() || pending.isSymbolicLink() || pending.size() > MAX_BYTES)
                        throw new IOException("invalid unpublished body temporary");
                    // 同scope仅允许唯一串行owner；进程退出遗留的未发布块不可能被任何Ref引用。
                    checkScope(); Files.delete(file); continue;
                }
                if (!name.endsWith(".blob") || !validHash(name.substring(0, name.length() - 5)))
                    throw new IOException("unrecognized file in body scope");
                BasicFileAttributes value = attributes(file);
                if (!value.isRegularFile() || value.isSymbolicLink() || value.size() < 1 || value.size() > maxBytes)
                    throw new IOException("invalid existing body block");
                if (value.size() > maxBytes - storedBytes) throw new IOException("body scope exceeds capacity");
                storedBytes += value.size(); storedBlobCount++;
            }
        }
        checkScope();
    }

    /** 成功发布块的物理字节；不包含manifest，不把重复引用再次计数。单owner之外的变更不被支持。 */
    public long storedBytes() { return storedBytes; }

    /** 已存在或本owner成功发布的块数，用来评估小行打块的inode成本。 */
    public long storedBlobCount() { return storedBlobCount; }

    /** 只计划本次新正文的块引用和保守追加峰值，不写文件也不读取旧正文。 */
    static final class Plan {
        final List<Ref> refs;
        final long additionalBytes;
        Plan(List<Ref> refs, long additionalBytes) {
            this.refs = Collections.unmodifiableList(refs); this.additionalBytes = additionalBytes;
        }
    }

    /** 与write使用相同打块边界；本轮相同块只计一次，既有块仍须由write核SHA后复用。 */
    Plan plan(List<byte[]> rows) throws IOException {
        if (rows == null) throw new IOException("rows are missing");
        checkScope();
        long batch = 0, additional = 0;
        for (byte[] row : rows) {
            if (row == null || row.length == 0 || row.length > maxBytes - batch)
                throw new IOException("invalid body batch");
            batch += row.length;
        }
        ArrayList<Ref> refs = new ArrayList<>();
        Set<String> counted = new HashSet<>();
        for (int first = 0; first < rows.size();) {
            int end = first + 1; long length = rows.get(first).length;
            while (length <= TARGET_BLOCK_BYTES && end < rows.size()
                    && rows.get(end).length <= TARGET_BLOCK_BYTES - length) length += rows.get(end++).length;
            MessageDigest digest = sha256();
            for (int i = first; i < end; i++) digest.update(rows.get(i));
            String hash = hex(digest.digest());
            Path path = directory.resolve(hash + ".blob");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (regularBlock(path).size() != length) throw new IOException("existing body block length mismatch");
            } else if (counted.add(hash)) additional += length;
            long offset = 0;
            for (int i = first; i < end; i++) {
                byte[] row = rows.get(i);
                refs.add(new Ref(hash, offset, row.length, hex(sha256().digest(row)))); offset += row.length;
            }
            first = end;
        }
        checkScope(); return new Plan(refs, additional);
    }

    /**
     * 连续小行按64KiB目标打包，大于目标的单行独立成块，合法大正文仍受原64MiB总预算保护。
     * 所有块成功后才返回不可变引用列表；中途失败不会交付任何Ref，先前已发布的完整块可能成为待回收孤块。
     */
    public List<Ref> write(List<byte[]> canonicalRows) throws IOException {
        if (canonicalRows == null) throw new IOException("rows are missing");
        checkScope();
        long batchBytes = 0;
        // 先验证整个输入，空行不是规范JSON；按实际数组长度而非外部声明长度控制总量。
        for (byte[] row : canonicalRows) {
            if (row == null || row.length == 0) throw new IOException("empty body row");
            if (row.length > maxBytes - batchBytes) throw new IOException("body batch exceeds capacity");
            batchBytes += row.length;
        }
        ArrayList<Ref> refs = new ArrayList<>();
        int first = 0;
        while (first < canonicalRows.size()) {
            int end = first + 1;
            long blockBytes = canonicalRows.get(first).length;
            if (blockBytes <= TARGET_BLOCK_BYTES) {
                while (end < canonicalRows.size() && canonicalRows.get(end).length <= TARGET_BLOCK_BYTES - blockBytes) {
                    blockBytes += canonicalRows.get(end).length; end++;
                }
            }
            writeBlock(canonicalRows, first, end, blockBytes, refs);
            first = end;
        }
        checkScope();
        return Collections.unmodifiableList(refs);
    }

    /** 仅对这一块计算SHA；同名块完整流校验后复用，不能用exists当内容正确的证明。 */
    private void writeBlock(List<byte[]> rows, int first, int end, long blockBytes, List<Ref> refs) throws IOException {
        MessageDigest blockDigest = sha256();
        ArrayList<String> rowDigests = new ArrayList<>();
        for (int i = first; i < end; i++) {
            byte[] row = rows.get(i); blockDigest.update(row); rowDigests.add(hex(sha256().digest(row)));
        }
        String key = hex(blockDigest.digest());
        Path target = directory.resolve(key + ".blob");
        checkScope();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) verifyExisting(target, key, blockBytes);
        else {
            if (blockBytes > maxBytes - storedBytes) throw new IOException("body scope capacity exhausted");
            Path temporary = directory.resolve(".pending-" + UUID.randomUUID().toString() + ".tmp");
            boolean published = false;
            try {
                MessageDigest writtenDigest = sha256();
                // CREATE_NEW与NOFOLLOW拒绝既有名字/链接，关流成功之后才进行原子发布。
                try (SeekableByteChannel channel = io.open(temporary, options(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    for (int i = first; i < end; i++) {
                        byte[] row = rows.get(i);
                        ByteBuffer bytes = ByteBuffer.wrap(row);
                        while (bytes.hasRemaining()) {
                            int before = bytes.position();
                            int count = channel.write(bytes);
                            if (count <= 0) throw new IOException("body write made no progress");
                            writtenDigest.update(row, before, count);
                        }
                    }
                    if (channel.size() != blockBytes || !hex(writtenDigest.digest()).equals(key))
                        throw new IOException("body changed while writing");
                }
                checkScope();
                // 本原型要求唯一串行writer；并发写入同scope不是支持的合同。
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("body target appeared during write");
                io.atomicMove(temporary, target);
                published = true; storedBytes += blockBytes; storedBlobCount++;
                checkScope();
            } finally {
                // 目录身份变了则不按陈旧路径清理；原scope可能留下临时文件，交外部诊断/恢复处理。
                if (!published) { checkScope(); Files.deleteIfExists(temporary); }
            }
        }
        long offset = 0;
        for (int i = first; i < end; i++) {
            int length = rows.get(i).length;
            refs.add(new Ref(key, offset, length, rowDigests.get(i - first)));
            offset += length;
        }
    }

    /** 既有地址必须是相同长度的普通文件且整块SHA相符；固定8KiB缓冲，不整块分配。 */
    private void verifyExisting(Path path, String expectedHash, long expectedBytes) throws IOException {
        checkScope(); BasicFileAttributes before = regularBlock(path);
        if (before.size() != expectedBytes) throw new IOException("existing body block length mismatch");
        MessageDigest digest = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(SEGMENT_BYTES);
        long count = 0;
        try (SeekableByteChannel channel = io.open(path, options(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (channel.size() != expectedBytes) throw new IOException("existing body changed before validation");
            while (count < expectedBytes) {
                buffer.clear(); buffer.limit((int)Math.min(buffer.capacity(), expectedBytes - count));
                int read = channel.read(buffer);
                if (read <= 0) throw new IOException("existing body truncated");
                digest.update(buffer.array(), 0, read); count += read;
            }
            if (channel.size() != expectedBytes) throw new IOException("existing body changed during validation");
        }
        requireSameFile(before, regularBlock(path)); checkScope();
        if (!hex(digest.digest()).equals(expectedHash)) throw new IOException("existing body SHA mismatch");
    }

    /**
     * 只读取Ref指定范围，按8KiB段收集并核行SHA；缺字节、坏SHA、close失败均不交付Body。
     * 不为声明length直接分配整条数组；只在真实文件范围与64MiB上限内逐段恢复一条正文。
     */
    public Body read(Ref ref) throws IOException {
        validateRef(ref); checkScope();
        Path path = directory.resolve(ref.blobSha256 + ".blob");
        BasicFileAttributes before = regularBlock(path);
        requireRange(ref, before.size());
        ArrayList<byte[]> segments = new ArrayList<>();
        MessageDigest digest = sha256(); long remaining = ref.length;
        try (SeekableByteChannel channel = io.open(path, options(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            requireRange(ref, channel.size()); channel.position(ref.offset);
            while (remaining > 0) {
                int wanted = (int)Math.min(SEGMENT_BYTES, remaining);
                byte[] bytes = new byte[wanted]; ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    int count = channel.read(buffer);
                    if (count <= 0) throw new IOException("body row truncated");
                }
                digest.update(bytes); segments.add(bytes); remaining -= wanted;
            }
            requireRange(ref, channel.size());
        }
        requireSameFile(before, regularBlock(path)); checkScope();
        if (!hex(digest.digest()).equals(ref.rowSha256)) throw new IOException("body row SHA mismatch");
        return new Body(ref.length, segments);
    }

    /** Ref不含可执行路径；整数边界在接触文件和分配正文前检查。 */
    private void validateRef(Ref ref) throws IOException {
        if (ref == null || !validHash(ref.blobSha256) || !validHash(ref.rowSha256)
                || ref.offset < 0 || ref.length < 1 || ref.length > maxBytes || ref.offset > maxBytes - ref.length)
            throw new IOException("invalid body reference");
    }

    /** 以差值核范围，拒绝长整型溢出、伪造大长度和越界偏移。 */
    private void requireRange(Ref ref, long fileSize) throws IOException {
        if (fileSize < 1 || fileSize > maxBytes || ref.length > fileSize || ref.offset > fileSize - ref.length)
            throw new IOException("body reference is outside block");
    }

    /** 拒绝符号链接和非普通文件；不读取任何正文。 */
    private BasicFileAttributes regularBlock(Path path) throws IOException {
        BasicFileAttributes value = attributes(path);
        if (!value.isRegularFile() || value.isSymbolicLink() || value.fileKey() == null || value.size() < 1 || value.size() > maxBytes)
            throw new IOException("invalid body block");
        return value;
    }

    /** 比较同次读取前后文件身份、长度与mtime；不可变块在本owner正常操作中不会变化。 */
    private static void requireSameFile(BasicFileAttributes before, BasicFileAttributes after) throws IOException {
        if (!before.fileKey().equals(after.fileKey()) || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("body block changed during access");
    }

    /** 每次操作保留逐层NOFOLLOW及初始scope身份，静态链接或已观察到的目录替换立即失败。 */
    private void checkScope() throws IOException {
        checkDirectoryChain(directory);
        BasicFileAttributes current = attributes(directory);
        if (!current.isDirectory() || !directoryKey.equals(current.fileKey())) throw new IOException("body scope changed");
    }

    /** 只接受已存在的普通目录链；不使用toRealPath静默穿过符号链接。 */
    private static void checkDirectoryChain(Path directory) throws IOException {
        Path current = directory.getRoot();
        if (current == null) throw new IOException("body scope must be absolute");
        for (Path component : directory) {
            current = current.resolve(component); BasicFileAttributes value = attributes(current);
            if (!value.isDirectory() || value.isSymbolicLink()) throw new IOException("body scope contains a link or non-directory");
        }
    }

    /** 统一不跟随符号链接的元数据读取。 */
    private static BasicFileAttributes attributes(Path path) throws IOException { return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }

    /** 只认本writer生成的精确小写UUID v4临时名，不猜测或清理其他文件。 */
    private static boolean unpublishedName(String name) {
        if (!name.startsWith(".pending-") || !name.endsWith(".tmp") || name.length() != 49) return false;
        String value = name.substring(9, 45);
        try { UUID id = UUID.fromString(value); return id.version() == 4 && id.variant() == 2 && id.toString().equals(value); }
        catch (IllegalArgumentException error) { return false; }
    }

    /** 哈希键仅64个小写ASCII字符，不能携带分隔符、点路径或URL编码。 */
    private static boolean validHash(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) { char c=value.charAt(i); if (!(c>='0'&&c<='9'||c>='a'&&c<='f')) return false; }
        return true;
    }

    /** 使用原UTF-8字节之外的固定SHA算法，不引入其他摘要或依赖。 */
    private static MessageDigest sha256() throws IOException { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException error) { throw new IOException("SHA-256 unavailable",error); } }

    /** SHA字节到固定小写键，避免格式器中间对象。 */
    private static String hex(byte[] bytes) { String digits="0123456789abcdef"; char[] out=new char[bytes.length*2]; for(int i=0;i<bytes.length;i++){int b=bytes[i]&255;out[i*2]=digits.charAt(b>>>4);out[i*2+1]=digits.charAt(b&15);} return new String(out); }

    /** 收拢NIO参数，不泄露可变选项集合给调用者。 */
    private static Set<OpenOption> options(OpenOption... options) { return new HashSet<>(Arrays.asList(options)); }

    /** 不可变索引契约；构造不读文件，读取owner按不可信索引重新验证所有字段。 */
    public static final class Ref {
        public final String blobSha256, rowSha256;
        public final long offset, length;
        /** 只持有引用字段，供未来manifest独立序列化。 */
        public Ref(String blobSha256,long offset,long length,String rowSha256){this.blobSha256=blobSha256;this.offset=offset;this.length=length;this.rowSha256=rowSha256;}
    }

    /** 只有整条读取与SHA验证成功后才创建；内部字节段不可被调用者修改。 */
    public static final class Body {
        public final long length;
        private final List<byte[]> segments;
        /** 接收读取owner独有数组，无额外正文复制且不公开数组引用。 */
        private Body(long length,List<byte[]> segments){this.length=length;this.segments=Collections.unmodifiableList(new ArrayList<>(segments));}
        /** UTF-8解码保持InputStreamReader默认替换语义，跨段中文及代理对由同一解码器处理。 */
        public Reader reader(){return new InputStreamReader(new SegmentInputStream(segments),StandardCharsets.UTF_8);}
        /** 只写出已验证数据；输出端失败由调用者处理，不修改缓存。 */
        public void writeTo(OutputStream output)throws IOException{for(byte[] segment:segments)output.write(segment);}
        /** 显式请求整条副本；大正文应优先reader，避免额外一份完整数组。 */
        public byte[] toByteArray(){byte[] bytes=new byte[(int)length];int offset=0;for(byte[] segment:segments){System.arraycopy(segment,0,bytes,offset,segment.length);offset+=segment.length;}return bytes;}
    }

    /** 只遍历已验证内存段，不触发第二次磁盘读取或访问其他Ref。 */
    private static final class SegmentInputStream extends InputStream {
        private final List<byte[]> segments; private int segment,offset; private boolean closed;
        /** 每个Reader有独立位置，共享只读字节。 */
        SegmentInputStream(List<byte[]> segments){this.segments=segments;}
        /** 单字节读取按InputStream合同返回0–255。 */
        @Override public int read()throws IOException{if(closed)throw new IOException("body reader closed");if(segment>=segments.size())return -1;byte[] bytes=segments.get(segment);int value=bytes[offset++]&255;if(offset==bytes.length){segment++;offset=0;}return value;}
        /** 只拷当前段，解码器按需继续读取，避免合并整条大数组。 */
        @Override public int read(byte[] out,int start,int length)throws IOException{if(closed)throw new IOException("body reader closed");if(out==null)throw new NullPointerException();if(start<0||length<0||start>out.length-length)throw new IndexOutOfBoundsException();if(length==0)return 0;if(segment>=segments.size())return -1;byte[] bytes=segments.get(segment);int count=Math.min(length,bytes.length-offset);System.arraycopy(bytes,offset,out,start,count);offset+=count;if(offset==bytes.length){segment++;offset=0;}return count;}
        /** Reader关闭只影响自身位置，不修改Body或磁盘。 */
        @Override public void close(){closed=true;}
    }

    /** 仅用于真实文件系统边界注入；不替换打块、哈希、范围验证或发布算法。 */
    interface Io {
        SeekableByteChannel open(Path path,Set<OpenOption> options)throws IOException;
        void atomicMove(Path temporary,Path target)throws IOException;
    }

    /** 默认执行本机真实NIO，不回退为非原子移动。 */
    static class NioIo implements Io {
        /** NOFOLLOW由owner显式传入，同一channel用于读取/写入/关闭失败判定。 */
        @Override public SeekableByteChannel open(Path path,Set<OpenOption> options)throws IOException{return Files.newByteChannel(path,options);}
        /** 不支持ATOMIC_MOVE即抛错，不能把半块当成已发布正文。 */
        @Override public void atomicMove(Path temporary,Path target)throws IOException{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}
    }
}
