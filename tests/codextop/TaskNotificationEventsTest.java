package com.butang.codextop;
import java.util.*;
/** 合成状态验证首快照、真实轮次、断连与持久去重。 */
public class TaskNotificationEventsTest {
    /** 失败立即终止，不依赖 Android 或真实账号。 */
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    /** 从静默基线推进真实状态，并验证重启与旧请求不会刷通知。 */
    public static void main(String[] args) {
        TaskNotificationEvents e = new TaskNotificationEvents(Collections.emptySet());
        check(!e.accept("s", "t", "completed", Collections.emptySet(), 1));
        check(!e.accept("s", "u", "running", Collections.emptySet(), 2));
        check(e.accept("s", "u", "needs_input", Collections.singleton("q"), 3));
        check(!e.accept("s", "u", "needs_input", Collections.singleton("q"), 4));
        check(e.accept("s", "u", "completed", Collections.emptySet(), 5));
        check(!e.accept("s", "u", "completed", Collections.emptySet(), 6));
        e.reset();
        check(!e.accept("s", "v", "needs_input", Collections.singleton("r"), 7));
        check(!e.accept("s", "w", "running", Collections.emptySet(), 8));
        check(!e.accept("s", "w", "completed", Collections.emptySet(), 16009));
        e = new TaskNotificationEvents(e.seen());
        check(!e.accept("s", "u", "running", Collections.emptySet(), 20000));
        check(!e.accept("s", "u", "completed", Collections.emptySet(), 20001));
        check(!e.accept("other", "u", "running", Collections.emptySet(), 20002));
        check(e.accept("other", "u", "completed", Collections.emptySet(), 20003));
        check(!e.accept("s", "", "completed", Collections.emptySet(), 20004));
        check(!e.accept("third", "x", "failed", Collections.emptySet(), 20005));
        check(!e.accept("third", "x", "completed", Collections.emptySet(), 20006));
        check(!e.accept("fourth", "x", "running", Collections.emptySet(), 20007));
        check(e.accept("fourth", "x", "needs_input", new LinkedHashSet<>(Arrays.asList("a","b")), 20008));
        check(!e.accept("fourth", "x", "needs_input", Collections.singleton("a"), 20009));
        check(e.accept("fourth", "x", "needs_input", new LinkedHashSet<>(Arrays.asList("a","c")), 20010));
        e.invalidate("fourth");
        check(!e.accept("fourth", "x", "needs_input", Collections.singleton("d"), 20011));
        for (int i=0;i<1000;i++) e.accept("bounded"+i,"t","completed",Collections.emptySet(),30000+i);
        check(e.seen().size()<=256);
        System.out.println("TaskNotificationEventsTest passed");
    }
}
