package com.butang.codextop;

import java.util.ArrayDeque;
import java.util.Locale;

/** 比较桌面项目路径，不使用手机文件系统解析其他操作系统的路径。 */
public final class ProjectPaths {
    /** 按目录边界匹配真实项目根目录，Windows 路径忽略大小写。 */
    public static boolean contains(String rootValue, String pathValue) {
        String root = normalize(rootValue), path = normalize(pathValue);
        if (root == null || path == null) return false;
        if (windows(root) && windows(path)) {
            root = root.toLowerCase(Locale.ROOT);
            path = path.toLowerCase(Locale.ROOT);
        }
        return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/");
    }

    /** 识别盘符和网络共享路径，不把普通 POSIX 路径改成大小写不敏感。 */
    private static boolean windows(String path) {
        return path.startsWith("//") || path.matches("^[A-Za-z]:/.*");
    }

    /** 仅接受绝对路径，折叠重复分隔符和点段，保留盘符及共享前缀。 */
    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String path = value.trim().replace('\\', '/').replaceFirst("^/+([A-Za-z]:/)", "$1");
        String prefix;
        int start;
        if (path.matches("^[A-Za-z]:/.*")) { prefix = path.substring(0, 3); start = 3; }
        else if (path.startsWith("//")) { prefix = "//"; start = 2; }
        else if (path.startsWith("/")) { prefix = "/"; start = 1; }
        else return null;
        ArrayDeque<String> parts = new ArrayDeque<>();
        for (String part : path.substring(start).split("/+")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
            else parts.addLast(part);
        }
        return prefix + String.join("/", parts);
    }
}
