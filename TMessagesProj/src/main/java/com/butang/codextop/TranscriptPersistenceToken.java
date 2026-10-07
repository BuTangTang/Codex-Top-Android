package com.butang.codextop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** 单一历史所属队列上的持久身份；只存根标记和代次，不拥有正文、窗口或额外历史表。 */
final class TranscriptPersistenceToken {
    enum Origin { MISSING, LEGACY, INDEXED }
    interface MissingFilesCheck { void verifyMissing() throws IOException; }

    private final Path familyPath;
    private Object currentRootIdentity;
    private String generation;
    private final String legacySha256;
    private Origin origin;
    private boolean revoked;

    private TranscriptPersistenceToken(TranscriptWindow root, Path familyPath, String generation,
            String legacySha256, Origin origin) throws IOException {
        this.familyPath = normalized(familyPath);
        this.currentRootIdentity = root.persistenceIdentity();
        this.generation = generation;
        this.legacySha256 = legacySha256;
        this.origin = origin;
    }

    static TranscriptPersistenceToken attachIndexed(TranscriptWindow root, Path familyPath,
            String generation) throws IOException {
        return attachIndexed(root, familyPath, generation, null);
    }

    static TranscriptPersistenceToken attachIndexed(TranscriptWindow root, Path familyPath,
            String generation, String importedLegacySha) throws IOException {
        requireGeneration(generation);
        if (importedLegacySha != null) requireDigest(importedLegacySha);
        return attach(root, familyPath, generation, importedLegacySha, Origin.INDEXED);
    }

    static TranscriptPersistenceToken attachLegacy(TranscriptWindow root, Path familyPath,
            String legacySha256) throws IOException {
        requireDigest(legacySha256);
        return attach(root, familyPath, null, legacySha256, Origin.LEGACY);
    }

    /** 回调必须实际确认本族所有提交头均不存在；IO失败不能解释为新历史。 */
    static TranscriptPersistenceToken attachMissing(TranscriptWindow root, Path familyPath,
            MissingFilesCheck check) throws IOException {
        normalized(familyPath);
        root.validatePersistenceFamilyUnbound();
        if (check == null) throw new IOException("缺少缓存不存在的核验");
        check.verifyMissing();
        return attach(root, familyPath, null, null, Origin.MISSING);
    }

    private static TranscriptPersistenceToken attach(TranscriptWindow root, Path familyPath,
            String generation, String legacySha256, Origin origin) throws IOException {
        root.validatePersistenceFamilyUnbound();
        TranscriptPersistenceToken token = new TranscriptPersistenceToken(root, familyPath,
                generation, legacySha256, origin);
        root.bindPersistenceFamily(token);
        return token;
    }

    static TranscriptPersistenceToken requireWritable(TranscriptWindow root, Path familyPath)
            throws IOException {
        TranscriptPersistenceToken token = root.persistenceToken();
        if (token == null) throw new IOException("缓存尚未绑定可写根");
        token.requireCurrent(root);
        if (!token.familyPath.equals(normalized(familyPath)))
            throw new IOException("缓存所属身份不一致");
        return token;
    }

    void requireCurrent(TranscriptWindow root) throws IOException {
        if (revoked || root.persistenceToken() != this
                || currentRootIdentity != root.persistenceIdentity())
            throw new IOException("缓存窗口已不再是当前可写根");
    }

    /** 已验证新页后才交接；旧根仍共享该token，但不能独立发布整份历史。 */
    void transferRoot(TranscriptWindow previous, TranscriptWindow next) throws IOException {
        requireCurrent(previous);
        if (next.persistenceToken() != null) throw new IOException("新缓存根已被绑定");
        next.bindPersistenceRoot(this);
        currentRootIdentity = next.persistenceIdentity();
    }

    Path familyPath() { return familyPath; }
    String generation() { return generation; }
    String legacySha256() { return legacySha256; }
    Origin origin() { return origin; }

    /** 仅在原子index发布成功后前进；失败或过期写入不得调用。 */
    void committed(TranscriptWindow root, Path familyPath, String expectedGeneration,
            String committedGeneration) throws IOException {
        if (requireWritable(root, familyPath) != this || !Objects.equals(generation, expectedGeneration))
            throw new IOException("缓存提交代次已变化");
        requireGeneration(committedGeneration);
        generation = committedGeneration;
        origin = Origin.INDEXED;
    }

    /** 显式降级导出完成后吊销旧队列中的可写根。 */
    void revoke(TranscriptWindow root, Path familyPath) throws IOException {
        if (requireWritable(root, familyPath) != this) throw new IOException("缓存令牌不一致");
        revoked = true;
    }

    private static Path normalized(Path path) throws IOException {
        if (path == null) throw new IOException("缺少缓存所属路径");
        return path.toAbsolutePath().normalize();
    }

    private static void requireGeneration(String generation) throws IOException {
        try {
            if (generation == null || !UUID.fromString(generation).toString().equals(generation))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new IOException("缓存代次格式无效", invalid); }
    }

    private static void requireDigest(String digest) throws IOException {
        if (digest == null || digest.length() != 64) throw new IOException("缓存迁移摘要格式无效");
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f'))
                throw new IOException("缓存迁移摘要格式无效");
        }
    }
}
