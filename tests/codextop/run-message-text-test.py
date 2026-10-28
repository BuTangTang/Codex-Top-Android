#!/usr/bin/env python3
"""Compile the real projection with the existing parser; substitute only Telegram entity DTOs."""
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
with tempfile.TemporaryDirectory(prefix='codex-message-text-test-') as directory:
    temporary = Path(directory)
    stub = temporary / 'org/telegram/tgnet/TLRPC.java'
    stub.parent.mkdir(parents=True)
    stub.write_text('package org.telegram.tgnet; public class TLRPC {'
                    'public static class Message { public String message; public boolean out; public int flags; '
                    'public java.util.ArrayList<MessageEntity> entities=new java.util.ArrayList<>(); }'
                    'public static class TL_message extends Message {}'
                    'public static class MessageEntity { public int offset,length; public String url,language; }'
                    + ''.join('public static class TL_messageEntity' + kind + ' extends MessageEntity {}'
                              for kind in ['Bold', 'Italic', 'Blockquote', 'Code', 'Pre', 'TextUrl']) + '}')
    for jar in jars:
        subprocess.run([str(java_bin / 'javac'), '-cp', str(jar), '-d', directory, str(stub),
                        str(root / 'TMessagesProj/src/main/java/com/butang/codextop/CodexMessageText.java'),
                        str(root / 'tests/codextop/CodexMessageTextTest.java')], check=True)
        subprocess.run([str(java_bin / 'java'), '-cp', directory + os.pathsep + str(jar),
                        'com.butang.codextop.CodexMessageTextTest'], check=True)
    source = (root / 'TMessagesProj/src/main/java/com/butang/codextop/CodexRuntime.java').read_text()
    history = source.split('private static TLRPC.TL_message historyMessage(', 1)[1].split('return message;', 1)[0]
    assert history.index('message.message = row.message.text;') < history.index('CodexMessageText.apply(message);')
    assert history.index('message.out = row.message.outgoing;') < history.index('CodexMessageText.apply(message);')
    pending = source.split('private static TLRPC.TL_message pendingMessage(', 1)[1].split('return message;', 1)[0]
    assert 'CodexMessageText' not in pending, 'Outbox retry must retain the original text'
print('History wiring and outbox isolation passed')
