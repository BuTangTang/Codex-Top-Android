package com.butang.codextop;
import java.nio.file.Files;
public final class OutboxStoreTest {
 public static void main(String[] args) throws Exception {
  var root=Files.createTempDirectory("codextop-outbox-test");
  try {
   var store=new OutboxStore(root.toFile(),"server","account","machine");
   var item=new OutboxStore.Item("original-send-id","thread","未送达测试",-42,1000);
   store.put(item); store.put(item);
   var reopened=new OutboxStore(root.toFile(),"server","account","machine");
   var rows=reopened.list("thread");
   if(rows.size()!=1 || rows.get(0).messageId!=-42 || !rows.get(0).text.equals(item.text)
      || !rows.get(0).localId.equals(item.localId))throw new AssertionError("恢复丢失身份或正文");
   try {store.put(new OutboxStore.Item(item.localId,"thread","另一条",-42,1000));throw new AssertionError("同编号覆盖");}
   catch(java.io.IOException expected){}
   if(!new OutboxStore(root.toFile(),"server","other","machine").list("thread").isEmpty()
      || !new OutboxStore(root.toFile(),"server","account","other").list("thread").isEmpty()
      || !store.list("other").isEmpty())throw new AssertionError("串用");
   store.remove(item.localId);
   if(!reopened.list("thread").isEmpty())throw new AssertionError("回显未清理");
   var localFile=root.resolve("sample.png"); Files.write(localFile,new byte[]{1,2,3});
   var pending=new DesktopAttachment.Pending(localFile.toString(),"sample.png","image","image/png",3,"a".repeat(64));
   var upload=new OutboxStore.Item("attachment-id","thread",null,-43,1001,java.util.List.of(pending));
   store.put(upload); store.put(upload);
   var attachmentRows=new OutboxStore(root.toFile(),"server","account","machine").list("thread");
   if(attachmentRows.size()!=1 || !attachmentRows.get(0).text.isEmpty()
      || !attachmentRows.get(0).attachments.equals(java.util.List.of(pending)))throw new AssertionError("纯附件待发重开丢失原件");
   var changed=new DesktopAttachment.Pending(localFile.toString(),"sample.png","image","image/png",3,"b".repeat(64));
   try {store.put(new OutboxStore.Item("attachment-id","thread","",-43,1001,java.util.List.of(changed)));throw new AssertionError("同localId覆盖不同附件内容");}
   catch(java.io.IOException expected){}
   try {store.put(new OutboxStore.Item("attachment-id","thread","新说明",-43,1001,java.util.List.of(pending)));throw new AssertionError("同localId覆写附件说明");}
   catch(java.io.IOException expected){}
   try {new OutboxStore.Item("empty","thread","",-44,1002);throw new AssertionError("空文字空附件被接受");}
   catch(IllegalArgumentException expected){}
   if(!new OutboxStore(root.toFile(),"other-server","account","machine").list("thread").isEmpty())throw new AssertionError("附件跨服务串用");
   var verified=pending.uploaded("/synthetic/computer/upload/sample.png",3,"a".repeat(64));
   store.rememberUploaded("attachment-id",java.util.List.of(verified));
   var verifiedReopened=reopened.get("attachment-id");
   if(verifiedReopened.uploaded.size()!=1 || !verifiedReopened.uploaded.get(0).path.equals(verified.path)
      || !verifiedReopened.attachments.equals(upload.attachments))throw new AssertionError("重试丢失上传路径或改写原件身份");
   store.put(upload);
   if(store.get("attachment-id").uploaded.size()!=1)throw new AssertionError("旧构造重试清掉已上传引用");
   try{store.rememberUploaded("attachment-id",java.util.List.of());throw new AssertionError("上传prefix回退");}
   catch(java.io.IOException expected){}
   try{store.rememberUploaded("attachment-id",java.util.List.of(pending.uploaded("/synthetic/changed/sample.png",3,"a".repeat(64))));throw new AssertionError("同localId替换电脑路径");}
   catch(java.io.IOException expected){}
   try{new OutboxStore.Item("bad-upload","thread","",-43,1001,java.util.List.of(pending),
       java.util.List.of(new DesktopAttachment("sample.png","image","/synthetic/sample.png","image/png",3L,"b".repeat(64),null,null)));
       throw new AssertionError("上传引用未验证原件摘要");}
   catch(IllegalArgumentException expected){}
   store.remove("attachment-id");
   if(!Files.exists(localFile))throw new AssertionError("移除待发描述误删原件");
   System.out.println("OutboxStore: 重启恢复、编号幂等、冲突拒绝、账号电脑对话隔离、回显清理通过");
  } finally {try(var paths=Files.walk(root)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))Files.delete(path);}}
 }
}
