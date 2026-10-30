package com.butang.codextop;

import java.util.*;

/** 只检测连续有效的真实状态变化；有界元数据不含标题、正文或凭据。 */
public final class TaskNotificationEvents {
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final LinkedHashMap<String, Previous> previous = new LinkedHashMap<>();
    private static final int LIMIT = 256;
    private static final class Previous {
        final String turn, state;
        final long at;
        /** 保留上一份在线事实，失效后不得据此补发通知。 */
        Previous(String turn, String state, long at) { this.turn=turn; this.state=state; this.at=at; }
    }
    /** 恢复已消费事件，进程启动不恢复在线基线。 */
    public TaskNotificationEvents(Set<String> saved) { for (String key : saved) remember(key); }
    /** 为持久化返回副本，调用者无法更改内部去重状态。 */
    public Set<String> seen() { return new LinkedHashSet<>(seen); }
    /** 断连、退出和未知状态清空在线基线；重连首快照只记账。 */
    public void reset() { previous.clear(); }
    /** 对单会话失效，不影响同账号其他在线电脑的连续状态。 */
    public void invalidate(String session) { previous.remove(session); }
    /** 接受已经由状态 owner 校验的 current 事实；身份缺失与超过15秒间隔均静默。 */
    public boolean accept(String session, String turn, String state, Set<String> requests, long at) {
        if (turn == null || turn.isEmpty()) { invalidate(session); return false; }
        Previous old = previous.put(session,new Previous(turn,state,at));
        while (previous.size()>LIMIT) previous.remove(previous.keySet().iterator().next());
        boolean continuous = old!=null && at>=old.at && at-old.at<=15000;
        boolean emit=false;
        if ("completed".equals(state)) {
            String key=session+":"+turn.length()+":"+turn+":completed";
            boolean fresh=!seen.contains(key);
            remember(key);
            emit=continuous && fresh && turn.equals(old.turn)
                    && ("running".equals(old.state)||"needs_input".equals(old.state));
        } else if ("needs_input".equals(state)) {
            for (String request : requests) {
                String key=session+":"+turn.length()+":"+turn+":request:"+request;
                boolean fresh=!seen.contains(key);
                remember(key);
                emit |= continuous && fresh;
            }
        }
        return emit;
    }
    /** 有界保留最近身份，避免通知账本随会话历史无限增长。 */
    private void remember(String key) {
        seen.add(key);
        while(seen.size()>LIMIT) seen.remove(seen.iterator().next());
    }
}
