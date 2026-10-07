# 历史缓存与按需正文回归

在仓库根运行：

```sh
python3 tests/codextop/run_transcript_history_tests.py
```

需要现有 JDK 17+ 、Gson 2.11.0 和 JavaParser 3.25.4。脚本使用 `JAVA_HOME`/`PATH` 以及 `GSON_JAR`/`JAVAPARSER_JAR`/现有 Gradle 缓存，不联网下载。也可通过 `--java-home`、`--gson-jar`、`--javaparser-jar` 指定。正式纯 Java owner 以 Java 8 编译；测试入口中的文本块需要 JDK 17。`--output` 可指定新的空目录，否则结果保留在系统临时目录。不要提交编译产物、运行日志或合成数据目录。

所有输入是独立临时目录内的合成数据。脚本不调用 ADB，不打开应用，不读取真实账号/聊天记录，不构建 APK。

## 覆盖

| 测试 | 范围 |
| --- | --- |
| TranscriptWindowTest / TranscriptSegmentsTest | 原编号、游标、去重、分段、接桥、归档与 pin 语义 |
| TranscriptLazyWindowTest | 完整轻索引、有限正文查询、元数据不读正文、正文 IO 失败 |
| TranscriptBodyStoreTest | 64 KiB 打块、范围读取、SHA 校验、去重、容量、失败/真实进程退出恢复与静态链接拒绝 |
| IndexedTranscriptStoreTest | 当前实际索引格式、近期30条/旧向50条、游标0正文IO、单条增量、提交失败和代次保护 |
| TranscriptPersistenceTokenTest | 可写根身份、共享代次、换根/旧段、撤销、缺失证明及迁移来源 |
| TranscriptCacheBudgetTest | 跨scope总量、saved/活根保护、全量预检、manifest先撤/body后删及失败恢复 |
| TranscriptStoreMigrationTest | v2到当前格式迁移、原v2保留、旧版本写入冲突、坏索引/旧根拒绝、独立回退导出 |
| TranscriptStoreTest | 原冷启/身份/来源隔离、共享预算、待发保护 |
| TranscriptStoreStreamingReadTest | 冻结95真实旧读取oracle、75个接受/拒绝场景、8类实际流IO故障不得靠重开掩盖 |
| TranscriptStoreStreamingTest | 原v2精确序列化和显式独立回退导出；不是常态v2保存测试 |
| RuntimeLatestSegmentTest | 原最新页、并发/迟到、书签、待发回显与接受边界 |
| RuntimeDialogPreviewTest | 原有摘要业务场景与惰性正文IO/过期归属保护 |
| RuntimeLazyHistoryTest | 实际Runtime近期窗口、坏旧正文、选段/书签元数据、迟到与错误终态 |
| RuntimeFacadeIntegrationTest | 实际Runtime保存→真实门面/预算→成功提交后才确认待发 |

`fixtures/transcript-v2/` 是95版两个完整真实源，仅供独立类加载器oracle使用，不加入应用编译。哈希由脚本校验：

- TranscriptStore.java：`552ded22495b0a45468319920ef6a636d6f855e1a50e77f891c53e21c0dc4164`
- TranscriptWindow.java：`6c189d5b57fdaca79f446bea5f22e6c366966f70aa2432328f46b35d359f9e80`

Runtime方法提取测试独立加载自己的真实owner；入口将模型类与测试类输出分离，避免父classloader遮蔽临时epoch观察器。

原v2的流式写入预算/中途失败/原子move三项现在调用真实 `exportForRollback`，目标必须独立于正式缓存。新格式常态写入的有限正文IO由 Body/Index/Migration/Runtime 专项验证，不把旧格式导出结果当作新保存行为。

此入口不验证 APK、Android 文件系统差异、实际设备耗时、冷启动三秒或长期活会话的精确 blob 回收。预算采用整会话 pin；Runtime 必须传完整活根。
