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
   System.out.println("OutboxStore: 重启恢复、编号幂等、冲突拒绝、账号电脑对话隔离、回显清理通过");
  } finally {try(var paths=Files.walk(root)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))Files.delete(path);}}
 }
}
