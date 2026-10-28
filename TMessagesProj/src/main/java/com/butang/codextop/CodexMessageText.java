package com.butang.codextop;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.tables.*;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;

/** 仅转换显示副本；复用现有解析器及 Telegram 实体，不执行 HTML 或创建 WebView。 */
public final class CodexMessageText {
    private static final int MAX_SOURCE = 64 * 1024;
    private static final Parser PARSER = Parser.builder()
            .extensions(java.util.Collections.singletonList(TablesExtension.create())).build();
    private static final Parser WITHOUT_TABLES = Parser.builder().build();
    private static final java.util.regex.Pattern SOURCE_LOCATION = java.util.regex.Pattern.compile(
            "(?::[0-9]+(?::[0-9]+)?|#L[0-9]+(?:-L?[0-9]+)?)$");

    private CodexMessageText() { }

    /** 只识别普通链接中的源码行／列定位；图片、代码示例和网页不冒充旧的推断附件。 */
    public static boolean isSourceReference(String source, String attachmentPath) {
        if (source == null || attachmentPath == null || source.length() > MAX_SOURCE) return false;
        final boolean[] found = {false};
        WITHOUT_TABLES.parse(source).accept(new AbstractVisitor() {
            int depth;
            @Override protected void visitChildren(Node node) {
                if (++depth <= 32 && !found[0]) super.visitChildren(node);
                depth--;
            }
            @Override public void visit(Image node) { /* 图片语法保持附件语义，不遍历其说明。 */ }
            @Override public void visit(Link node) {
                String reference = node.getDestination().trim();
                if (!SOURCE_LOCATION.matcher(reference).find()) return;
                String local = localReferencePath(reference);
                if (attachmentPath.equals(local)) found[0] = true;
            }
        });
        return found[0];
    }

    /** 对齐后端 POSIX localPath：普通绝对路径不解码，file URL 解码并忽略查询／片段。 */
    private static String localReferencePath(String reference) {
        String path = reference.trim();
        if (path.startsWith("file://")) {
            try {
                java.net.URI uri = new java.net.URI(path);
                if (uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty()
                        && !uri.getRawAuthority().equalsIgnoreCase("localhost")) return null;
                String raw = uri.getRawPath();
                if (raw == null || raw.toLowerCase(java.util.Locale.ROOT).contains("%2f")) return null;
                path = uri.getPath();
            } catch (java.net.URISyntaxException invalid) { return null; }
        }
        return path != null && path.startsWith("/") && path.indexOf('\0') < 0 ? path : null;
    }

    /** 仅在历史／增量消息创建时执行一次，不在绘制或滑动时计算。 */
    public static void apply(TLRPC.Message message) {
        String source = message.message;
        if (source == null || source.length() > MAX_SOURCE) return;
        String visible = message.out ? userBody(source) : source;
        message.message = visible;
        if (visible.indexOf('*') < 0 && visible.indexOf('_') < 0 && visible.indexOf('`') < 0
                && visible.indexOf('>') < 0 && visible.indexOf('#') < 0 && visible.indexOf('[') < 0
                && visible.indexOf('|') < 0) return;
        Projection projection = new Projection();
        (tableColumnsPreserved(visible) ? PARSER : WITHOUT_TABLES).parse(visible).accept(projection);
        if (projection.unsupported || projection.entities.isEmpty() && !projection.changed) {
            message.message = visible;
            return;
        }
        message.message = projection.text.toString();
        message.entities = projection.entities;
        message.flags |= 128;
    }

    /** 现有 GFM 扩展会丢掉超出表头的格。此时仅关闭表格扩展，正常加粗／引用仍可显示。 */
    private static boolean tableColumnsPreserved(String source) {
        if (source.indexOf('|') < 0) return true;
        String[] lines = source.split("\n", -1);
        int columns = 0;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            // 引用、列表和缩进的容器边界由 CommonMark 解析，不能用顶层列数推断。
            // 有管线文本且存在容器迹象时保守关闭表格扩展，保留正文及引用／强调实体。
            if (line.indexOf('|') >= 0 && containerLine(lines[index], line)) return false;
            if (columns > 0) {
                // 表体可含无管线的单格及分隔符样式数据行；只有空行才保守结束检查。
                if (line.isEmpty()) columns = 0;
                else if (cells(line) > columns) return false;
            } else if (index > 0 && separator(line)) {
                columns = cells(lines[index - 1].trim());
            }
        }
        return true;
    }

    private static boolean containerLine(String raw, String line) {
        if (line.isEmpty()) return false;
        if (raw.charAt(0) == ' ' || raw.charAt(0) == '\t' || line.charAt(0) == '>') return true;
        char first = line.charAt(0);
        if ((first == '-' || first == '+' || first == '*') && line.length() > 1
                && Character.isWhitespace(line.charAt(1))) return true;
        int digit = 0;
        while (digit < line.length() && line.charAt(digit) >= '0' && line.charAt(digit) <= '9') digit++;
        return digit > 0 && digit + 1 < line.length()
                && (line.charAt(digit) == '.' || line.charAt(digit) == ')')
                && Character.isWhitespace(line.charAt(digit + 1));
    }

    private static boolean separator(String line) {
        if (line.indexOf('|') < 0 || line.indexOf('-') < 0) return false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value != '|' && value != '-' && value != ':' && value != ' ' && value != '\t') return false;
        }
        return true;
    }

    /** 只计算未转义分隔符，真正的格内语法仍由已有解析器负责。 */
    private static int cells(String line) {
        int count = 1;
        boolean escaped = false;
        boolean trailingPipe = false;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            trailingPipe = value == '|' && !escaped;
            if (trailingPipe) count++;
            escaped = value == '\\' && !escaped;
        }
        if (line.startsWith("|")) count--;
        if (trailingPipe) count--;
        return count;
    }

    /** 只隐藏完整且位于开头的浏览器环境封套及其请求分隔符。 */
    static String userBody(String source) {
        String open = "<in-app-browser-context source=\"ambient-ui-state\">";
        String close = "</in-app-browser-context>";
        String notice = "This block is automatically supplied ambient UI state, not part of the user's request.";
        int start = source.startsWith("\n") ? 1 : 0;
        if (!source.startsWith(open, start)) return source;
        int end = source.indexOf(close, start + open.length());
        if (end < 0 || !source.substring(start + open.length(), end).contains(notice)) return source;
        int body = end + close.length();
        while (body < source.length() && (source.charAt(body) == '\n' || source.charAt(body) == '\r')) body++;
        String delimiter = "## My request:";
        if (!source.startsWith(delimiter, body)) return source;
        body += delimiter.length();
        if (body == source.length() || source.charAt(body) != '\n' && source.charAt(body) != '\r') return source;
        if (source.charAt(body) == '\r') body++;
        if (body < source.length() && source.charAt(body) == '\n') body++;
        return body < source.length() ? source.substring(body) : source;
    }

    private static final class Projection extends AbstractVisitor {
        final StringBuilder text = new StringBuilder();
        final ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        final java.util.IdentityHashMap<Node, Integer> listNumbers = new java.util.IdentityHashMap<>();
        boolean unsupported;
        boolean changed;
        int depth;

        @Override protected void visitChildren(Node node) {
            if (++depth > 32 || text.length() > MAX_SOURCE * 2) unsupported = true;
            else if (unsupported) { depth--; return; }
            else super.visitChildren(node);
            depth--;
        }

        private void span(Node node, TLRPC.MessageEntity entity) {
            entity.offset = text.length();
            visitChildren(node);
            entity.length = text.length() - entity.offset;
            add(entity);
        }

        private void literal(String value, TLRPC.MessageEntity entity) {
            entity.offset = text.length();
            text.append(value);
            entity.length = text.length() - entity.offset;
            add(entity);
        }

        private void add(TLRPC.MessageEntity entity) {
            if (entity.length <= 0) return;
            if (entities.size() == 1024) unsupported = true;
            else entities.add(entity);
        }

        private void blockEnd(Node node) {
            if (node.getNext() != null) text.append('\n').append('\n');
        }

        @Override public void visit(Text node) { text.append(node.getLiteral()); }
        @Override public void visit(SoftLineBreak node) { text.append('\n'); }
        @Override public void visit(HardLineBreak node) { text.append('\n'); }
        @Override public void visit(Paragraph node) { visitChildren(node); blockEnd(node); }
        @Override public void visit(StrongEmphasis node) { span(node, new TLRPC.TL_messageEntityBold()); }
        @Override public void visit(Emphasis node) { span(node, new TLRPC.TL_messageEntityItalic()); }
        @Override public void visit(Heading node) { span(node, new TLRPC.TL_messageEntityBold()); blockEnd(node); }
        @Override public void visit(BlockQuote node) { span(node, new TLRPC.TL_messageEntityBlockquote()); blockEnd(node); }
        @Override public void visit(Code node) { literal(node.getLiteral(), new TLRPC.TL_messageEntityCode()); }
        @Override public void visit(FencedCodeBlock node) {
            TLRPC.TL_messageEntityPre entity = new TLRPC.TL_messageEntityPre();
            entity.language = node.getInfo() == null ? "" : node.getInfo();
            literal(node.getLiteral(), entity); blockEnd(node);
        }
        @Override public void visit(IndentedCodeBlock node) {
            TLRPC.TL_messageEntityPre entity = new TLRPC.TL_messageEntityPre(); entity.language = "";
            literal(node.getLiteral(), entity); blockEnd(node);
        }
        @Override public void visit(Link node) {
            // 可点击网页仍走 Telegram 原有打开／确认机制；本地及未知协议仅显示文字。
            String url = node.getDestination();
            if (!(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:"))) {
                visitChildren(node); destination(url); return;
            }
            TLRPC.TL_messageEntityTextUrl entity = new TLRPC.TL_messageEntityTextUrl();
            entity.url = url; span(node, entity);
        }
        @Override public void visit(BulletList node) { visitChildren(node); blockEnd(node); }
        @Override public void visit(OrderedList node) { visitChildren(node); blockEnd(node); }
        @Override public void visit(ListItem node) {
            if (node.getParent() instanceof OrderedList) {
                int number = listNumbers.getOrDefault(node.getParent(), ((OrderedList) node.getParent()).getStartNumber());
                listNumbers.put(node.getParent(), number + 1);
                text.append(number).append(". ");
            } else text.append("• ");
            visitChildren(node);
            if (node.getNext() != null) text.append('\n');
        }
        @Override public void visit(HtmlInline node) { text.append(node.getLiteral()); }
        @Override public void visit(HtmlBlock node) { text.append(node.getLiteral()); blockEnd(node); }
        /** 图片不下载或执行，保留说明及可复制地址；不影响相邻正常格式。 */
        @Override public void visit(Image node) {
            text.append("图片："); visitChildren(node); destination(node.getDestination());
        }
        private void destination(String value) {
            changed = true;
            text.append('（'); literal(value, new TLRPC.TL_messageEntityCode()); text.append('）');
        }
        @Override public void visit(ThematicBreak node) { text.append("---"); blockEnd(node); }
        /** 表格按原行序展开为列名和值，不用等宽宽表，也不截断单元格。 */
        @Override public void visit(CustomBlock node) {
            if (!(node instanceof TableBlock)) { visitChildren(node); blockEnd(node); return; }
            changed = true;
            ArrayList<Node> headers = new ArrayList<>();
            ArrayList<Node> rows = new ArrayList<>();
            for (Node section = node.getFirstChild(); section != null; section = section.getNext()) {
                for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                    if (section instanceof TableHead) {
                        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) headers.add(cell);
                    } else rows.add(row);
                }
            }
            // 空表仍显示表头，不能凭空丢失用户提供的列名。
            if (rows.isEmpty()) {
                for (int column = 0; column < headers.size(); column++) {
                    if (column > 0) text.append('\n');
                    visitChildren(headers.get(column));
                }
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                // 列名会逐行重复，给展开结果单独设预算，防止短表格放大成大段内存。
                if (text.length() > MAX_SOURCE * 2) { unsupported = true; return; }
                if (rowIndex > 0) text.append('\n').append('\n');
                int column = 0;
                for (Node cell = rows.get(rowIndex).getFirstChild(); cell != null; cell = cell.getNext(), column++) {
                    if (column > 0) text.append('\n');
                    if (column < headers.size() && headers.get(column).getFirstChild() != null) visitChildren(headers.get(column));
                    else text.append("列 ").append(column + 1);
                    text.append('：'); visitChildren(cell);
                }
            }
            blockEnd(node);
        }
        @Override public void visit(CustomNode node) { visitChildren(node); }
    }
}
