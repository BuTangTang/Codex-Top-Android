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
   failureCodes(root);
   acceptedSubmission(root);
   System.out.println("OutboxStore: 重启恢复、编号幂等、选择与暂存prefix恢复、固定失败原因兼容、原子写失败、未知派发保持、账号电脑对话隔离通过");
  } finally {try(var paths=Files.walk(root)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))Files.delete(path);}}
 }
 /** 固定解释与原记录同存；旧值或坏值不能损坏批次，也不能参与发送身份比较。 */
 private static void failureCodes(java.nio.file.Path root) throws Exception {
  var store=new OutboxStore(root.toFile(),"failure-server","account","machine");
  var reopened=new OutboxStore(root.toFile(),"failure-server","account","machine");
  var path=root.resolve("failure.bin");Files.write(path,new byte[]{1,2,3});
  var choice=new OutboxStore.Selection(path.toString(),"failure.bin","file",null);
  var item=OutboxStore.Item.selected("failure-batch","thread","原说明",-70,1100,java.util.List.of(choice));
  store.put(item);
  if(reopened.get(item.localId).failureCode!=null)throw new AssertionError("旧v1缺失原因错误恢复");
  store.markFailureCode(item.localId,OutboxStore.FAILURE_FILE_TOO_LARGE);
  store.put(item);
  var pending=AttachmentFiles.stage(path.toFile(),choice.name,choice.kind,choice.mimeType,root.resolve("failure-staged").toFile(),"failure-batch");
  store.rememberStaged(item.localId,java.util.List.of(pending));
  var uploaded=pending.uploaded("/synthetic/failure.bin",pending.sizeBytes,pending.sha256);
  store.rememberUploaded(item.localId,java.util.List.of(uploaded));
  var saved=reopened.get(item.localId);
  if(!OutboxStore.FAILURE_FILE_TOO_LARGE.equals(saved.failureCode)||saved.uploaded.size()!=1||!saved.attachments.equals(java.util.List.of(pending))
     ||!saved.selections.equals(item.selections)||!saved.text.equals(item.text)||saved.messageId!=item.messageId||saved.date!=item.date)
      throw new AssertionError("原mutator或旧构造丢原因或改变原身份");
  if(reopened.list("thread").size()!=1||!OutboxStore.FAILURE_FILE_TOO_LARGE.equals(reopened.list("thread").get(0).failureCode))
      throw new AssertionError("固定原因不能通过原列表恢复");
  for(var isolated:new OutboxStore[]{new OutboxStore(root.toFile(),"failure-other-server","account","machine"),
      new OutboxStore(root.toFile(),"failure-server","other-account","machine"),new OutboxStore(root.toFile(),"failure-server","account","other-machine")})
      if(isolated.get(item.localId)!=null)throw new AssertionError("失败原因跨账号电脑服务串用");
  if(!store.list("other-thread").isEmpty())throw new AssertionError("失败原因跨对话串用");
  try{store.markFailureCode(item.localId,"arbitrary server text");throw new AssertionError("任意原因可持久化");}
  catch(IllegalArgumentException expected){}
  if(!OutboxStore.FAILURE_FILE_TOO_LARGE.equals(reopened.get(item.localId).failureCode))throw new AssertionError("拒绝未知原因损坏原记录");
  var fileMethod=OutboxStore.class.getDeclaredMethod("file",String.class);fileMethod.setAccessible(true);
  var record=((java.io.File)fileMethod.invoke(store,item.localId)).toPath();
  var raw=com.google.gson.JsonParser.parseString(Files.readString(record)).getAsJsonObject();
  for(String malformed:new String[]{"null","{}","[]","42","true","\"unknown_code\""}){
   var value=raw.deepCopy();value.add("failureCode",com.google.gson.JsonParser.parseString(malformed));Files.writeString(record,value.toString());
   var restored=reopened.get(item.localId);
   if(restored.failureCode!=null||!restored.selections.equals(item.selections)||restored.attachments.size()!=1||restored.uploaded.size()!=1)
       throw new AssertionError("畸形可选原因损坏原批次");
  }
  Files.writeString(record,raw.toString());store.markSubmissionUncertain(item.localId,true);
  store.markFailureCode(item.localId,OutboxStore.FAILURE_FILE_TOO_LARGE);store.rememberStaged(item.localId,java.util.List.of(pending));
  store.rememberUploaded(item.localId,java.util.List.of(uploaded));
  if(!reopened.get(item.localId).submissionUncertain||reopened.get(item.localId).failureCode!=null)throw new AssertionError("未知结果保留旧原因");
  var uncertain=com.google.gson.JsonParser.parseString(Files.readString(record)).getAsJsonObject();
  uncertain.addProperty("failureCode",OutboxStore.FAILURE_FILE_TOO_LARGE);Files.writeString(record,uncertain.toString());
  if(reopened.get(item.localId).failureCode!=null)throw new AssertionError("旧记录矛盾字段误标未知结果");
  store.markSubmissionUncertain(item.localId,false);
  if(reopened.get(item.localId).failureCode!=null)throw new AssertionError("恢复可发送状态复活旧原因");
  store.markFailureCode(item.localId,OutboxStore.FAILURE_FILE_TOO_LARGE);
  var blocked=java.nio.file.Path.of(record+".tmp");Files.createDirectories(blocked);Files.write(blocked.resolve("block"),new byte[]{1});
  try{store.markFailureCode(item.localId,null);throw new AssertionError("真实原子写阻挡未失败");}catch(java.io.IOException expected){}
  if(!OutboxStore.FAILURE_FILE_TOO_LARGE.equals(reopened.get(item.localId).failureCode)||reopened.get(item.localId).uploaded.size()!=1)
      throw new AssertionError("清原因写失败损坏有效原记录");
  Files.delete(blocked.resolve("block"));Files.delete(blocked);store.markFailureCode(item.localId,null);
  if(reopened.get(item.localId).failureCode!=null)throw new AssertionError("重试清除未持久化");
  store.remove(item.localId);
  try{store.markFailureCode(item.localId,OutboxStore.FAILURE_FILE_TOO_LARGE);throw new AssertionError("原因写入复活已删除记录");}catch(java.io.IOException expected){}
  if(Files.exists(record)||!Files.exists(path))throw new AssertionError("解释字段复活记录或误删文件");
 }
 /** 真实成功ACK同原记录持久化；旧格式兼容且后续旧写入不能降级接受事实。 */
 private static void acceptedSubmission(java.nio.file.Path root) throws Exception {
  var store=new OutboxStore(root.toFile(),"accepted-server","account","machine");
  var reopened=new OutboxStore(root.toFile(),"accepted-server","account","machine");
  var item=new OutboxStore.Item("ack-message","thread","synthetic",-80,1200);
  store.put(item);
  if(reopened.get(item.localId).submissionAccepted)throw new AssertionError("旧v1记录伪造接受事实");
  store.markSubmissionUncertain(item.localId,true);store.markSubmissionAccepted(item.localId);
  var accepted=reopened.get(item.localId);
  if(!accepted.submissionAccepted||accepted.submissionUncertain||accepted.failureCode!=null
      ||!accepted.localId.equals(item.localId)||accepted.messageId!=item.messageId||!accepted.text.equals(item.text))
      throw new AssertionError("成功ACK没有保留原身份或仍标未知");
  store.put(item);store.markSubmissionUncertain(item.localId,true);store.markSubmissionUncertain(item.localId,false);
  store.markFailureCode(item.localId,OutboxStore.FAILURE_FILE_TOO_LARGE);
  if(!reopened.get(item.localId).submissionAccepted||reopened.get(item.localId).submissionUncertain||reopened.get(item.localId).failureCode!=null)
      throw new AssertionError("原mutator降级已接受事实");
  var fileMethod=OutboxStore.class.getDeclaredMethod("file",String.class);fileMethod.setAccessible(true);
  var record=((java.io.File)fileMethod.invoke(store,item.localId)).toPath();
  var raw=com.google.gson.JsonParser.parseString(Files.readString(record)).getAsJsonObject();
  for(String malformed:new String[]{"null","{}","[]","42","\"true\"","false"}){
   var value=raw.deepCopy();value.add("submissionAccepted",com.google.gson.JsonParser.parseString(malformed));Files.writeString(record,value.toString());
   var restored=reopened.get(item.localId);
   if(restored.submissionAccepted||!restored.text.equals(item.text)||restored.messageId!=item.messageId)
       throw new AssertionError("畸形ACK值伪造成功或丢消息");
  }
  store.remove(item.localId);store.markSubmissionAccepted(item.localId);
  if(store.get(item.localId)!=null)throw new AssertionError("迟到ACK复活已经回显的记录");
  var local=root.resolve("accepted.bin");Files.write(local,new byte[]{1,2,3});
  var choice=new OutboxStore.Selection(local.toString(),"accepted.bin","file",null);
  var batch=OutboxStore.Item.selected("ack-batch","thread","",-82,1201,java.util.List.of(choice));store.put(batch);
  try{store.markSubmissionAccepted(batch.localId);throw new AssertionError("未暂存批次伪造成功");}catch(IllegalArgumentException expected){}
  var pending=AttachmentFiles.stage(local.toFile(),choice.name,choice.kind,choice.mimeType,root.resolve("accepted-staged").toFile(),batch.localId);
  var uploaded=pending.uploaded("/synthetic/accepted.bin",pending.sizeBytes,pending.sha256);
  store.rememberStaged(batch.localId,java.util.List.of(pending));store.rememberUploaded(batch.localId,java.util.List.of(uploaded));
  store.markSubmissionUncertain(batch.localId,true);store.markSubmissionAccepted(batch.localId);
  store.rememberStaged(batch.localId,java.util.List.of(pending));store.rememberUploaded(batch.localId,java.util.List.of(uploaded));
  var restored=reopened.get(batch.localId);
  if(!restored.submissionAccepted||restored.submissionUncertain||!restored.selections.equals(batch.selections)
      ||!restored.attachments.equals(java.util.List.of(pending))||!restored.uploaded.get(0).path.equals(uploaded.path))
      throw new AssertionError("附件ACK没有保留选择、暂存或上传身份");
  for(var isolated:new OutboxStore[]{new OutboxStore(root.toFile(),"accepted-server","other","machine"),
      new OutboxStore(root.toFile(),"accepted-server","account","other")})
      if(isolated.get(batch.localId)!=null)throw new AssertionError("接受事实跨账号或机器串用");
 }

}
