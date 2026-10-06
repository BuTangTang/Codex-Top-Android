package com.butang.codextop;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

/** 直接运行生产上传和真实分块加密，以可控 ACK 耗时检查选块、顺序及失败边界。 */
public final class AttachmentUploadSizingTest {
    private static int assertions, cases, failures;

    /** 每个场景独立保存合成文件；旧实现缺少时钟入口时仍执行其真实上传以取得行为 RED。 */
    public static void main(String[] args) throws Exception {
        run("fast_ramp_and_server_cap", () -> {
            try (Fixture f = new Fixture(800000, 256000, 100_000_000L)) {
                f.upload(); f.prefix(8192, 32768, 131072, 256000, 256000); f.complete();
                check(f.clock.reads == 10, "末块不应读取用于估速的时钟");
            }
        });
        run("slow_shrink_and_floor", () -> {
            try (Fixture f = new Fixture(20000, 256000, 6_000_000_000L)) {
                f.upload(); f.prefix(8192, 2048, 1024, 1024); f.complete();
            }
        });
        run("slowdown_after_fast_ack", () -> {
            try (Fixture f = new Fixture(300000, 256000, 100_000_000L, 12_000_000_000L, 1_500_000_000L)) {
                f.upload(); f.prefix(8192, 32768, 4096, 4096); f.complete();
            }
        });
        run("fractional_duration_uses_nanos", () -> {
            try (Fixture f = new Fixture(20000, 256000, 2_400_000_000L, 1_500_000_000L)) {
                f.upload(); f.prefix(8192, 5120, 5120); f.complete();
            }
        });
        run("one_byte_server_limit", () -> {
            try (Fixture f = new Fixture(3, 1, 9_000_000_000L)) {
                f.upload(); f.exact(1, 1, 1); f.complete();
            }
        });
        run("server_limit_below_floor", () -> {
            try (Fixture f = new Fixture(1600, 777, 9_000_000_000L)) {
                f.upload(); f.exact(777, 777, 46); f.complete();
            }
        });
        run("server_limit_below_first_probe", () -> {
            try (Fixture f = new Fixture(4500, 2000, 100_000_000L)) {
                f.upload(); f.exact(2000, 2000, 500); f.complete();
            }
        });
        run("final_short_chunk_not_measured", () -> {
            try (Fixture f = new Fixture(10000, 256000, 1_500_000_000L)) {
                f.upload(); f.exact(8192, 1808); f.complete();
                check(f.clock.reads == 2, "最后短块不应改变速度样本");
            }
        });
        run("small_file_has_no_estimate", () -> {
            try (Fixture f = new Fixture(100, 256000, 1)) {
                f.upload(); f.exact(100); f.complete();
                check(f.clock.reads == 0, "无需下一块时不读取时钟");
            }
        });
        run("empty_file_keeps_finalize", () -> {
            try (Fixture f = new Fixture(0, 256000, 1)) {
                f.upload(); f.exact(); f.complete();
                check(f.clock.reads == 0, "空文件不读取上传时钟");
            }
        });
        run("clock_zero_is_conservative", () -> clockFallback(0));
        run("clock_backwards_is_conservative", () -> clockFallback(-10));
        run("clock_start_exception_is_conservative", () -> clockThrows(1));
        run("clock_end_exception_is_conservative", () -> clockThrows(2));
        run("clock_fault_after_growth_reduces_probe", () -> {
            try (Fixture f = new Fixture(80000, 256000, 100_000_000L)) {
                f.clock.failRead = 4;
                f.upload(); f.prefix(8192, 32768, 8192); f.complete();
            }
        });
        run("nano_time_wrap_keeps_positive_elapsed", () -> {
            try (Fixture f = new Fixture(16000, 256000, 3_000_000_000L)) {
                f.clock.now = Long.MAX_VALUE - 1_000_000_000L;
                f.upload(); f.prefix(8192, 4096); f.complete();
            }
        });
        run("huge_duration_clamps_without_overflow", () -> {
            try (Fixture f = new Fixture(12000, 1048576, Long.MAX_VALUE)) {
                f.clock.now = 0;
                f.upload(); f.prefix(8192, 1024); f.complete();
            }
        });
        run("one_mib_growth_arithmetic_stays_bounded", () -> {
            try (Fixture f = new Fixture(2700000, 1048576, 1)) {
                f.upload(); f.prefix(8192, 32768, 131072, 524288, 1048576); f.complete();
            }
        });
        run("ack_rejection_stops_without_retry", () -> failure(false, false));
        run("ack_unknown_exception_stops_without_retry", () -> failure(true, false));
        run("final_hash_mismatch_aborts", () -> failure(false, true));
        run("cancellation_after_ack_preserves_interrupt", () -> {
            try (Fixture f = new Fixture(50000, 256000, 100_000_000L)) {
                f.cancelAfterProgress = true;
                expectFailure(f::upload);
                check(Thread.currentThread().isInterrupted(), "取消标记丢失");
                f.exact(8192);
                check(f.aborts == 1 && f.finalizes == 0, "取消后仍继续请求或未 abort");
            } finally { Thread.interrupted(); }
        });
        run("cancellation_before_upload_dispatches_nothing", () -> {
            try (Fixture f = new Fixture(100, 256000, 1)) {
                Thread.currentThread().interrupt(); expectFailure(f::upload);
                check(f.inits == 0 && f.aborts == 0 && f.lengths.isEmpty(), "提前取消仍派发 RPC");
            } finally { Thread.interrupted(); }
        });
        System.out.println("AttachmentUploadSizing cases=" + cases + " assertions=" + assertions + " failures=" + failures);
        if (failures != 0) throw new AssertionError("上传选块行为检查失败: " + failures);
    }

    /** 不可信的零值或倒退耗时不能把下一块放大。 */
    private static void clockFallback(long duration) throws Exception {
        try (Fixture f = new Fixture(25000, 256000, duration)) {
            f.upload(); f.prefix(8192, 8192, 8192); f.complete();
        }
    }

    /** 时钟自身失败只丢弃本次速度样本，不改变已经明确成功的块。 */
    private static void clockThrows(int read) throws Exception {
        try (Fixture f = new Fixture(25000, 256000, 100_000_000L)) {
            f.clock.failRead = read;
            f.upload(); f.prefix(8192, 8192); f.complete();
        }
    }

    /** 拒绝、未知异常或错误终态摘要均不允许重发块或伪造完成。 */
    private static void failure(boolean unknown, boolean hash) throws Exception {
        try (Fixture f = new Fixture(100000, 256000, 100_000_000L)) {
            f.failChunk = hash ? -1 : 1; f.unknownFailure = unknown; f.badHash = hash;
            expectFailure(f::upload);
            check(f.aborts == 1, "失败未释放传输");
            check(f.finalizes == (hash ? 1 : 0), "失败后错误 finalize 次数");
            if (!hash) {
                f.exact(8192, 32768);
                check(f.progress.equals(Arrays.asList(0L, 8192L)), "未确认块推进了进度");
                check(f.clock.reads == 3, "失败 ACK 后仍采样耗时");
            }
        }
    }

    /** 每例使用独立的真实加密接收方和合成 RPC，并检查原协议字段未增加。 */
    private static final class Fixture implements AttachmentTransfer.Rpc, AutoCloseable {
        final byte[] source;
        final File file;
        final int limit;
        final long[] durations;
        final Clock clock = new Clock();
        final BulkTransferCrypto.Recipient recipient = BulkTransferCrypto.createRecipient();
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        final List<Integer> lengths = new ArrayList<>();
        final List<Long> progress = new ArrayList<>();
        int inits, finalizes, aborts, nextIndex, failChunk = -1;
        boolean unknownFailure, badHash, cancelAfterProgress, inCall;
        AttachmentTransfer.Result result;

        /** 合成内容不含真实附件、账号或会话数据。 */
        Fixture(int size, int limit, long... durations) throws Exception {
            this.limit = limit; this.durations = durations;
            source = new byte[size];
            for (int i = 0; i < size; i++) source[i] = (byte) (i * 73 + 29);
            file = Files.createTempFile("codextop-upload-sizing-", ".bin").toFile();
            Files.write(file.toPath(), source);
        }

        /** 新构造只注入时钟；基线无该入口时回退原公开构造，RED 仍来自真实上传行为。 */
        void upload() throws Exception {
            AttachmentTransfer.Progress listener = (done, total) -> {
                check(total == source.length, "进度总量变化");
                long acknowledged = received.size();
                check(done == acknowledged, "进度不等于服务端已确认字节");
                check(progress.isEmpty() || done >= progress.get(progress.size() - 1), "进度回退");
                progress.add(done);
                if (cancelAfterProgress && done > 0) Thread.currentThread().interrupt();
            };
            AttachmentTransfer transfer;
            try {
                Constructor<AttachmentTransfer> ctor = AttachmentTransfer.class.getDeclaredConstructor(
                        AttachmentTransfer.Rpc.class, AttachmentTransfer.Progress.class, LongSupplier.class);
                transfer = ctor.newInstance(this, listener, clock);
            } catch (NoSuchMethodException oldSource) { transfer = new AttachmentTransfer(this, listener); }
            result = transfer.upload(file, "file", "synthetic-message", "/synthetic-workspace");
        }

        /** 解密真实生产密文，按真实协议的连续序号追加；故障只作用在当前明确选定的 ACK。 */
        @Override public JsonObject call(String method, JsonObject params) throws Exception {
            check(!inCall, "上传出现并行或递归 RPC"); inCall = true;
            try {
                JsonObject response = new JsonObject(); response.addProperty("success", true);
                if (method.endsWith("upload.init")) {
                    check(++inits == 1, "重复 init");
                    check(params.keySet().equals(java.util.Set.of("t", "messageLocalId", "fileName", "sizeBytes",
                            "workspaceRootPath", "uploadLocation", "vcsIgnoreStrategy", "vcsIgnoreWritesEnabled")), "init 字段改变");
                    check(params.get("sizeBytes").getAsLong() == source.length, "文件声明长度改变");
                    response.addProperty("uploadId", "synthetic-upload"); response.addProperty("chunkSizeBytes", limit);
                    response.addProperty("recipientPublicKeyBase64", recipient.publicKeyBase64);
                } else if (method.endsWith("upload.chunk")) {
                    check(params.keySet().equals(java.util.Set.of("uploadId", "index", "payloadBase64", "encryptedDataKeyEnvelopeBase64")), "chunk 字段改变");
                    int index = params.get("index").getAsInt();
                    check(index == nextIndex++, "序号重复或跳跃");
                    byte[] bytes = BulkTransferCrypto.decrypt(params.get("uploadId").getAsString(), index,
                            params.get("payloadBase64").getAsString(), params.get("encryptedDataKeyEnvelopeBase64").getAsString(), recipient);
                    check(bytes.length > 0 && bytes.length <= limit, "分块超出服务端上限");
                    lengths.add(bytes.length);
                    if (index == failChunk) {
                        if (unknownFailure) throw new IOException("synthetic_unknown_ack");
                        response.addProperty("success", false); response.addProperty("error", "synthetic_rejection"); return response;
                    }
                    received.write(bytes);
                    clock.now += durations[Math.min(index, durations.length - 1)];
                } else if (method.endsWith("upload.finalize")) {
                    check(params.keySet().equals(java.util.Set.of("uploadId")), "finalize 字段改变");
                    finalizes++;
                    check(Arrays.equals(received.toByteArray(), source), "最终追加字节不一致");
                    response.addProperty("sizeBytes", received.size()); response.addProperty("path", "/synthetic-result/file.bin");
                    response.addProperty("sha256", badHash ? "0".repeat(64) : hash(received.toByteArray()));
                } else if (method.endsWith("upload.abort")) { aborts++; }
                else throw new AssertionError("增加了未知 RPC");
                return response;
            } finally { inCall = false; }
        }

        /** 全链路完成必须保持字节、摘要、大小、进度及 finalize/abort 终态。 */
        void complete() throws Exception {
            check(result != null && result.sizeBytes == source.length && result.sha256.equals(hash(source)), "最终结果变化");
            check(Arrays.equals(received.toByteArray(), source), "上传内容变化");
            check(finalizes == 1 && aborts == 0 && inits == 1, "完成后调用计数异常");
            check(progress.get(progress.size() - 1) == source.length, "进度未完成");
        }

        /** 只断言有意义的已确认分块序列，剩余末块另由完整字节核对。 */
        void prefix(int... expected) {
            check(lengths.size() >= expected.length, "块数不足: " + lengths);
            for (int i = 0; i < expected.length; i++) check(lengths.get(i) == expected[i], "第 " + i + " 块预期 " + expected[i] + " 实际 " + lengths.get(i));
        }

        /** 短文件与失败场景还必须没有额外块请求。 */
        void exact(int... expected) { prefix(expected); check(lengths.size() == expected.length, "多出分块: " + lengths); }

        /** 每例释放独立临时文件与合成密钥。 */
        @Override public void close() throws IOException { recipient.close(); Files.deleteIfExists(file.toPath()); }
    }

    /** 只有 RPC 明确返回时才推进合成耗时，不用真实 sleep 或替换生产加密实现。 */
    private static final class Clock implements LongSupplier {
        long now = 1_000_000_000L;
        int reads, failRead = -1;
        /** 可定点模拟起点/终点读取失败，观察生产方法是否保守继续。 */
        @Override public long getAsLong() {
            if (++reads == failRead) throw new IllegalStateException("synthetic_clock_failure");
            return now;
        }
    }

    /** 独立计算合成内容摘要，不使用生产上传的摘要方法。 */
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    /** 所有失败例必须实际抛出原 IOException，不能把断言失败当作成功。 */
    private static void expectFailure(Attempt action) throws Exception {
        try { action.run(); throw new AssertionError("预期失败未发生"); } catch (IOException expected) { }
    }

    /** 汇总全部场景，保留旧实现的完整行为失败而非只看编译结果。 */
    private static void run(String name, Attempt action) throws Exception {
        cases++;
        try { action.run(); System.out.println("PASS " + name); }
        catch (AssertionError failure) { failures++; System.out.println("FAIL " + name + ": " + failure.getMessage()); }
    }

    /** 每条检查计数便于独立重跑核对。 */
    private static void check(boolean condition, String reason) { assertions++; if (!condition) throw new AssertionError(reason); }

    /** 场景只允许同步调用，不创建额外 owner 或工作线程。 */
    private interface Attempt { void run() throws Exception; }
}
