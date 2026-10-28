#!/usr/bin/env python3
"""编译真实显示转换及已有解析依赖，仅替代 Telegram 实体数据类型。"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
jars = sorted(cache.glob('*/commonmark/*/*/commonmark-*.jar'))
if not jars:
    raise SystemExit('Existing Gradle CommonMark jar is required; this test does not download dependencies.')
java_bin = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17')) / 'bin'
tables = sorted(cache.glob('*/commonmark-ext-gfm-tables/*/*/commonmark-ext-gfm-tables-*.jar'))
if not tables:
    raise SystemExit('需要现有 Gradle GFM tables 依赖；此测试不下载依赖。')
with tempfile.TemporaryDirectory(prefix='codex-message-text-test-') as directory:
    executed = 0
    temporary = Path(directory)
    stub = temporary / 'org/telegram/tgnet/TLRPC.java'
    stub.parent.mkdir(parents=True)
    stub.write_text('package org.telegram.tgnet; public class TLRPC {'
                    'public static class Message { public String message; public boolean out; public int flags,id,date; '
                    'public long dialog_id; public TL_peerUser peer_id,from_id; public Object media; '
                    'public java.util.HashMap<String,String> params; '
                    'public java.util.ArrayList<MessageEntity> entities=new java.util.ArrayList<>(); }'
                    'public static class TL_message extends Message {}'
                    'public static class TL_peerUser { public long user_id; } public static class TL_messageMediaEmpty {}'
                    'public static class MessageEntity { public int offset,length; public String url,language; }'
                    + ''.join('public static class TL_messageEntity' + kind + ' extends MessageEntity {}'
                              for kind in ['Bold', 'Italic', 'Blockquote', 'Code', 'Pre', 'TextUrl']) + '}')
    for jar in jars:
        # 扩展与核心必须同版；Gradle 缓存还可能有其他项目的不同版本，不跨版本拼装。
        matching_tables = [table for table in tables if table.parents[1].name == jar.parents[1].name]
        if not matching_tables:
            print('跳过无同版 tables 扩展的缓存核心：' + jar.name)
            continue
        parser_cp = os.pathsep.join([str(jar), str(matching_tables[-1])])
        subprocess.run([str(java_bin / 'javac'), '-cp', parser_cp, '-d', directory, str(stub),
                        str(root / 'TMessagesProj/src/main/java/com/butang/codextop/CodexMessageText.java'),
                        str(root / 'tests/codextop/CodexMessageTextTest.java')], check=True)
        subprocess.run([str(java_bin / 'java'), '-cp', directory + os.pathsep + parser_cp,
                        'com.butang.codextop.CodexMessageTextTest'], check=True)
        executed += 1
    assert executed > 0, '必须至少执行一组实际配对依赖，不能全部跳过后通过'
    source = (root / 'TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java').read_text()
    history = source.split('private static TLRPC.TL_message historyMessage(', 1)[1].split('return message;', 1)[0]
    assert history.index('message.message = row.message.text;') < history.index('CodexMessageText.apply(message);')
    assert history.index('message.out = row.message.outgoing;') < history.index('CodexMessageText.apply(message);')
    pending = source.split('private static TLRPC.TL_message pendingMessage(', 1)[1].split('return message;', 1)[0]
    assert 'CodexMessageText' not in pending, 'Outbox retry must retain the original text'
    # 执行真实 historyMessage，平台缓存／附件应用只替代为可观察边界，所有资格条件均来自生产方法。
    probe = temporary / 'SourceAttachmentProjectionTest.java'
    probe.write_text('''package com.butang.codextop;
import java.io.File; import java.util.*; import org.telegram.tgnet.TLRPC;
public final class SourceAttachmentProjectionTest {
    static File cache; static int applications;
    static File attachmentDirectory(long id){return new File("unused-synthetic");}
    static File cachedAttachment(File directory,String localId,DesktopAttachment value){return cache;}
    static final class AttachmentMessages {static void apply(TLRPC.TL_message message,DesktopAttachment value,File file){applications++;}}
    static final class DesktopAttachment {String name,kind,path,sha256;Long sizeBytes;}
    static final class TranscriptWindow {static final class Entry {int id=7;String epoch="synthetic-epoch";Body message=new Body();}}
    static final class Body {long createdAtMs=1000;String text,id="source-id",localId="local-id";boolean outgoing;List<DesktopAttachment> attachments=new ArrayList<>();}
''' + 'private static TLRPC.TL_message historyMessage(' + history + 'return message;\n}\n' + '''
    static void scenario(String text,boolean outgoing,String kind,String name,Long size,String hash,boolean cached,boolean expected)throws Exception {
        TranscriptWindow.Entry row=new TranscriptWindow.Entry();row.message.text=text;row.message.outgoing=outgoing;
        DesktopAttachment a=new DesktopAttachment();a.path="/tmp/a.swift:50";a.kind=kind;a.name=name;a.sizeBytes=size;a.sha256=hash;row.message.attachments.add(a);
        cache=cached?File.createTempFile("codex-source-ready-",".tmp"):null;applications=0;
        try {
            TLRPC.TL_message message=historyMessage(42,row);
            if((applications>0)!=expected)throw new AssertionError("附件显示资格错误："+kind+"/"+outgoing+"/"+name+"/"+cached);
            if(message.id!=7 || !"source-id".equals(message.params.get("codexSourceId")) || !"local-id".equals(message.params.get("codexLocalId"))
                    || !"synthetic-epoch".equals(message.params.get("codexHistoryEpoch")) || !row.message.text.equals(text) || row.message.attachments.size()!=1)
                throw new AssertionError("投影不得改写原历史或身份");
        } finally {if(cache!=null && !cache.delete())throw new AssertionError("清理合成缓存失败");cache=null;}
    }
    public static void main(String[] args)throws Exception {
        String link="[源码](/tmp/a.swift:50)";
        scenario(link,false,"file","a.swift:50",null,null,false,false);
        scenario(link,true,"file","a.swift:50",null,null,false,true);
        scenario(link,false,"image","a.swift:50",null,null,false,true);
        scenario(link,false,"video","a.swift:50",null,null,false,true);
        scenario(link,false,"file","a.swift:50",0L,null,false,true);
        scenario(link,false,"file","a.swift:50",null,"verified-hash",false,true);
        scenario(link,false,"file","explicit-name",null,null,false,true);
        scenario(link,false,"file","a.swift:50",null,null,true,true);
        scenario("显式附件",false,"file","a.swift:50",null,null,false,true);
        scenario("`"+link+"`",false,"file","a.swift:50",null,null,false,true);
        scenario("!"+link,false,"file","a.swift:50",null,null,false,true);
        System.out.println("真实historyMessage旧缓存附件投影：11场景通过");
    }
}
''')
    subprocess.run([str(java_bin / 'javac'), '-cp', directory + os.pathsep + parser_cp,
                    '-d', directory, str(probe)], check=True)
    subprocess.run([str(java_bin / 'java'), '-cp', directory + os.pathsep + parser_cp,
                    'com.butang.codextop.SourceAttachmentProjectionTest'], check=True)
print('History wiring and outbox isolation passed')
