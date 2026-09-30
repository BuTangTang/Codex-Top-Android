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
   if(reopened.get("attachment-id").submissionUncertain)throw new AssertionError("旧记录错误恢复未知派发");
   store.markSubmissionUncertain("attachment-id",true);
   if(!reopened.get("attachment-id").submissionUncertain)throw new AssertionError("未知派发未跨重启保留");
   store.put(upload); store.rememberUploaded("attachment-id",java.util.List.of(verified));
   if(!reopened.get("attachment-id").submissionUncertain || reopened.get("attachment-id").uploaded.size()!=1)
       throw new AssertionError("复用原件或上传prefix丢失未知派发状态");
   store.markSubmissionUncertain("attachment-id",false);
   if(reopened.get("attachment-id").submissionUncertain || !reopened.get("attachment-id").uploaded.get(0).path.equals(verified.path))
       throw new AssertionError("明确未派发恢复错误覆盖上传身份");
   store.remove("attachment-id");
   if(!Files.exists(localFile))throw new AssertionError("移除待发描述误删原件");
   // 第二件暂存失败后重新打开同一owner，完整选择与第一件已验证原件均必须存在。
   var first=root.resolve("first.bin"); Files.write(first,new byte[]{1,2,3});
   var second=root.resolve("second.bin"); Files.write(second,new byte[]{4,5});
   var firstChoice=new OutboxStore.Selection(first.toString(),"first.bin","file",null);
   var secondChoice=new OutboxStore.Selection(second.toString(),"second.bin","file",null);
   var selection=OutboxStore.Item.selected("selected-batch","thread","两件附件",-50,1003,java.util.List.of(firstChoice,secondChoice));
   store.put(selection);
   if(reopened.get("selected-batch").attachmentCount()!=2 || reopened.get("selected-batch").isPrepared()
      || firstChoice.toJson().has("sha256") || firstChoice.toJson().has("sizeBytes"))throw new AssertionError("选择未持久化或伪造字节证明");
   var files=root.resolve("staged").toFile();
   var stagedFirst=AttachmentFiles.stage(first.toFile(),firstChoice.name,firstChoice.kind,firstChoice.mimeType,files,"send\nselected-batch");
   store.rememberStaged("selected-batch",java.util.List.of(stagedFirst));
   Files.delete(second);
   try{AttachmentFiles.stage(second.toFile(),secondChoice.name,secondChoice.kind,secondChoice.mimeType,files,"send\nselected-batch:attachment:1");
       throw new AssertionError("缺失第二原件未失败");}catch(java.io.IOException expected){}
   var failed=new OutboxStore(root.toFile(),"server","account","machine").get("selected-batch");
   if(failed==null || failed.attachmentCount()!=2 || failed.attachments.size()!=1 || failed.isPrepared()
      || !failed.selections.equals(selection.selections) || !failed.attachments.get(0).equals(stagedFirst))
       throw new AssertionError("第二件stage失败导致重开丢整批或已暂存prefix");
   Files.delete(first);
   store.put(selection);
   if(!reopened.get("selected-batch").attachments.get(0).equals(stagedFirst)
      || !Files.exists(java.nio.file.Path.of(stagedFirst.localPath)))throw new AssertionError("选择重试清掉已暂存原件");
   try{store.put(OutboxStore.Item.selected("selected-batch","thread","两件附件",-50,1003,
       java.util.List.of(firstChoice,new OutboxStore.Selection(root.resolve("changed.bin").toString(),"second.bin","file",null))));
       throw new AssertionError("同批次替换原选择");}catch(java.io.IOException expected){}
   try{store.rememberStaged("selected-batch",java.util.List.of());throw new AssertionError("暂存prefix回退");}catch(java.io.IOException expected){}
   try{store.rememberStaged("selected-batch",java.util.List.of(new DesktopAttachment.Pending(stagedFirst.localPath,
       stagedFirst.name,stagedFirst.kind,stagedFirst.mimeType,stagedFirst.sizeBytes,"b".repeat(64))));
       throw new AssertionError("同身份改变已暂存字节");}catch(java.io.IOException expected){}
   try{store.markSubmissionUncertain("selected-batch",true);throw new AssertionError("未暂存完整就进入发送");}catch(IllegalArgumentException expected){}
   try{store.rememberUploaded("selected-batch",java.util.List.of(stagedFirst.uploaded("/synthetic/first.bin",stagedFirst.sizeBytes,stagedFirst.sha256)));
       throw new AssertionError("未暂存完整就进入上传");}catch(IllegalArgumentException expected){}
   Files.write(second,new byte[]{4,5});
   var stagedSecond=AttachmentFiles.stage(second.toFile(),secondChoice.name,secondChoice.kind,secondChoice.mimeType,files,"send\nselected-batch:attachment:1");
   store.rememberStaged("selected-batch",java.util.List.of(stagedFirst,stagedSecond));
   var prepared=reopened.get("selected-batch");
   if(!prepared.isPrepared() || prepared.attachments.size()!=2 || !prepared.attachments.get(0).equals(stagedFirst))
       throw new AssertionError("复用已暂存prefix后不能继续原批次");
   store.rememberUploaded("selected-batch",java.util.List.of(stagedFirst.uploaded("/synthetic/first.bin",stagedFirst.sizeBytes,stagedFirst.sha256),
       stagedSecond.uploaded("/synthetic/second.bin",stagedSecond.sizeBytes,stagedSecond.sha256)));
   store.markSubmissionUncertain("selected-batch",true);
   if(!reopened.get("selected-batch").submissionUncertain || reopened.get("selected-batch").selections.size()!=2)
       throw new AssertionError("派发后丢失原选择");
   store.put(OutboxStore.Item.selected("missing-selection","thread","",-52,1004,
       java.util.List.of(new OutboxStore.Selection(null,"缺失图片","image",null))));
   if(reopened.get("missing-selection").attachmentCount()!=1 || reopened.get("missing-selection").selections.get(0).localPath!=null)
       throw new AssertionError("缺失原路径的选择消失或伪造路径");
   try{new OutboxStore.Selection("content://synthetic/file","file","file",null);throw new AssertionError("持久化内容URI");}
   catch(IllegalArgumentException expected){}
   System.out.println("OutboxStore: 重启恢复、编号幂等、选择与暂存prefix恢复、原件冲突拒绝、未知派发保持、账号电脑对话隔离通过");
  } finally {try(var paths=Files.walk(root)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))Files.delete(path);}}
 }
}
