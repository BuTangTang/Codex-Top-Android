package com.butang.codextop;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/** data-only 推送的有界元数据；不保存正文，不把推送当作当前可答题授权。 */
public final class TaskPushEvent {
    public final String kind, eventKey, machine, remote, turn, requestId;
    /** 仅由完成类型、归属和时效校验的解析入口建立实例。 */
    private TaskPushEvent(String kind,String eventKey,String machine,String remote,String turn,String requestId) {
        this.kind=kind; this.eventKey=eventKey; this.machine=machine; this.remote=remote;
        this.turn=turn; this.requestId=requestId;
    }
    /** 校验真实本地账号及服务作用域，并限制FCM队列有效期最多300秒；格式错误静默丢弃。 */
    public static TaskPushEvent parse(Map<String,String> data,String server,String account,
            long sentAt,int ttlSeconds,long now) {
        try {
            if(data==null||!"codex_task".equals(data.get("type"))||account==null||account.isEmpty()
                    ||!account.equals(data.get("accountId"))) return null;
            String expected=serverScope(server), actual=serverScope(data.get("serverUrl"));
            if(expected.isEmpty()||!expected.equals(actual)||sentAt<=0||now<=0||ttlSeconds<=0||ttlSeconds>300
                    ||sentAt>now+60000||now-sentAt>ttlSeconds*1000L) return null;
            String kind=data.get("eventKind"), request=data.get("requestId");
            if(!"completed".equals(kind)&&!"needs_input".equals(kind)) return null;
            if(request==null) request="";
            if(request.length()>512) return null;
            String key=required(data,"eventKey"), machine=required(data,"machineId"),
                    remote=required(data,"remoteSessionId"), turn=required(data,"turnId");
            if(key==null||machine==null||remote==null||turn==null) return null;
            return new TaskPushEvent(kind,key,machine,remote,turn,"completed".equals(kind)?"":request);
        } catch(RuntimeException error) { return null; }
    }
    /** 仅统一HTTPS来源的默认端口、主机大小写与末尾斜杠，不接受凭据、查询或片段。 */
    public static String serverScope(String value) {
        try {
            URI uri=new URI(value);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null
                    ||uri.getRawQuery()!=null||uri.getRawFragment()!=null) return "";
            String path=uri.getRawPath(); if(path==null) path="";
            while(path.endsWith("/")) path=path.substring(0,path.length()-1);
            int port=uri.getPort();
            return "https://"+uri.getHost().toLowerCase(Locale.ROOT)+(port==-1||port==443?"":":"+port)+path;
        } catch(Exception error) { return ""; }
    }
    /** 为本地与远程通知使用完全相同的电脑、会话和轮次身份。 */
    public static String scope(String machine,String remote,String turn) {
        return digest(machine.length()+":"+machine+remote.length()+":"+remote+turn.length()+":"+turn);
    }
    /** 摘要只用于私有身份键；不输出输入或将内容用作系统通知。 */
    public static String digest(String text) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder(64);
            for(byte value:hash) result.append(String.format(Locale.ROOT,"%02x",value&255));
            return result.toString();
        } catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    /** 不让空身份或无界字符串占用持久通知账本。 */
    private static String required(Map<String,String> data,String key) {
        String value=data.get(key);
        return value!=null&&!value.trim().isEmpty()&&value.length()<=512?value:null;
    }
}
