package com.butang.codextop;
import java.util.*;
/** 仅合成消息和账号验证协议校验与跨通道通知去重。 */
public final class TaskPushEventTest {
    /** 失败即停止，不读取设备、网络或真实凭据。 */
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
    /** 生成不包含正文或凭据的最小消息。 */
    private static Map<String,String> message() {
        Map<String,String> data=new HashMap<>();
        data.put("type","codex_task"); data.put("eventKind","needs_input"); data.put("eventKey","event1");
        data.put("machineId","m"); data.put("remoteSessionId","s"); data.put("accountId","a");
        data.put("turnId","t"); data.put("requestId","q"); data.put("serverUrl","https://example.test/");
        return data;
    }
    /** 覆盖归属、过期、错误类型、恢复、重复、本地/远程逆序与有界账本。 */
    public static void main(String[] args) {
        Map<String,String> data=message();
        TaskPushEvent event=TaskPushEvent.parse(data,"https://example.test","a",1000,300,2000);
        check(event!=null);
        check(TaskPushEvent.parse(data,"https://other.test","a",1000,300,2000)==null);
        check(TaskPushEvent.parse(data,"https://example.test","b",1000,300,2000)==null);
        check(TaskPushEvent.parse(data,"https://example.test","a",1000,300,301001)==null);
        check(TaskPushEvent.parse(data,"https://example.test","a",1000,3600,2000)==null);
        data.put("eventKind","failed");
        check(TaskPushEvent.parse(data,"https://example.test","a",1000,300,2000)==null);
        data=message(); data.put("serverUrl","https://user:pass@example.test");
        check(TaskPushEvent.parse(data,"https://example.test","a",1000,300,2000)==null);
        TaskNotificationDelivery ledger=new TaskNotificationDelivery(Collections.emptySet());
        String scope=TaskPushEvent.scope("m","s","t");
        check(ledger.claim(scope,"needs_input",Collections.singleton("q")));
        check(!ledger.claim(scope,event.kind,Collections.singleton(event.requestId)));
        ledger=new TaskNotificationDelivery(ledger.saved());
        check(!ledger.claim(scope,"needs_input",Collections.singleton("q")));
        check(ledger.claim(scope,"needs_input",Collections.singleton("q2")));
        check(!ledger.claim(scope,"needs_input",Collections.singleton("")));
        check(ledger.claim(scope,"needs_input",Collections.singleton("q-next")));
        String generic=TaskPushEvent.scope("m","s","t2");
        check(ledger.claim(generic,"needs_input",Collections.singleton("")));
        check(!ledger.claim(generic,"needs_input",Collections.singleton("q3")));
        check(ledger.claim(scope,"completed",Collections.emptySet()));
        check(!ledger.claim(scope,"completed",Collections.emptySet()));
        for(int i=0;i<1000;i++) ledger.claim("scope"+i,"completed",Collections.emptySet());
        check(ledger.saved().size()<=256);
        System.out.println("TaskPushEventTest passed");
    }
}
