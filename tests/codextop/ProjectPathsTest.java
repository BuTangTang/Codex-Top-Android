package com.butang.codextop;

/** 项目归属不依赖运行测试的操作系统，覆盖目录前缀与跨平台路径。 */
public final class ProjectPathsTest {
    /** 合成路径只验证归属，不访问文件系统。 */
    public static void main(String[] args) {
        check("/work/app", "/work/app", true);
        check("/work/app", "/work/app/src", true);
        check("/work/app", "/work/apple", false);
        check("/work/app", "/work/app/../other", false);
        check("/work//app/", "/work/app/./src", true);
        check("/work/App", "/work/app/src", false);
        check("/", "/work/app", true);
        check("C:\\Work\\App", "c:/work/app/src", true);
        check("C:/", "c:/work/app", true);
        check("C:/Work/App", "D:/Work/App", false);
        check("//SERVER/share/app", "\\\\server\\share\\app\\src", true);
        check("//server/share/app", "//server/share/apple", false);
        check("app", "/work/app", false);
        check("/work/app", null, false);
        check("/work/app", "", false);
        System.out.println("ProjectPaths: POSIX、Windows、共享路径、目录边界与点段测试通过");
    }

    /** 断言项目包含关系，失败仅输出合成样例。 */
    private static void check(String root, String path, boolean expected) {
        if (ProjectPaths.contains(root, path) != expected) throw new AssertionError(root + " -> " + path);
    }
}
