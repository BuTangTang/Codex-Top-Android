package com.butang.codextop;

import java.util.*;

/** 本地变化与FCM共用的有界已通知账本；与静默初始状态基线分别保存。 */
public final class TaskNotificationDelivery {
    private final LinkedHashSet<String> delivered=new LinkedHashSet<>();
    /** 恢复本账号已消费的通知身份，最多保留256条。 */
    public TaskNotificationDelivery(Set<String> saved) { for(String key:saved) remember(key); }
    /** 返回持久化副本，不暴露可变账本。 */
    public Set<String> saved() { return new LinkedHashSet<>(delivered); }
    /** 同一真实事件只允许一个通道通知；无requestId的泛化待办只能按轮次通知一次。 */
    public boolean claim(String scope,String kind,Set<String> requests) {
        if("completed".equals(kind)) return remember(scope+":completed");
        if(!"needs_input".equals(kind)) return false;
        String prefix=scope+":needs_input:", generic=prefix+"*";
        if(delivered.contains(generic)) return false;
        if(requests.isEmpty()||requests.contains("")) {
            boolean existing=false;
            for(String key:delivered) if(key.startsWith(prefix)) { existing=true; break; }
            if(existing) return false;
            return remember(generic);
        }
        boolean fresh=false;
        for(String request:requests) fresh |= remember(prefix+TaskPushEvent.digest(request));
        return fresh;
    }
    /** 有界保留最近事件，返回是否第一次记录。 */
    private boolean remember(String key) {
        boolean added=delivered.add(key);
        while(delivered.size()>256) delivered.remove(delivered.iterator().next());
        return added;
    }
}
