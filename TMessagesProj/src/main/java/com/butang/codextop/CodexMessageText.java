package com.butang.codextop;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;

/** Display-only Markdown projection. Reuses the bundled parser and Telegram entities; no HTML or WebView. */
public final class CodexMessageText {
    private static final int MAX_SOURCE = 64 * 1024;
    private static final Parser PARSER = Parser.builder().build();

    private CodexMessageText() { }

    /** Called once when constructing a history/incremental message, never from draw or scroll. */
    public static void apply(TLRPC.Message message) {
        String source = message.message;
        if (source == null || source.length() > MAX_SOURCE) return;
        String visible = message.out ? userBody(source) : source;
        message.message = visible;
        if (visible.indexOf('*') < 0 && visible.indexOf('_') < 0 && visible.indexOf('`') < 0
                && visible.indexOf('>') < 0 && visible.indexOf('#') < 0 && visible.indexOf('[') < 0) return;
        Projection projection = new Projection();
        PARSER.parse(visible).accept(projection);
        if (projection.unsupported || projection.entities.isEmpty()) {
            message.message = visible;
            return;
        }
        message.message = projection.text.toString();
        message.entities = projection.entities;
        message.flags |= 128;
    }

    /** Only the exact leading ambient-browser envelope plus request delimiter is hidden. */
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
        int depth;

        @Override protected void visitChildren(Node node) {
            if (++depth > 32) unsupported = true;
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
            // Existing Telegram TextUrl handling owns opening/confirmation; never execute HTML.
            String url = node.getDestination();
            if (!(url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:"))) {
                unsupported = true; return;
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
        // These need a separate presentation contract. Preserve the complete original, including URLs.
        @Override public void visit(Image node) { unsupported = true; }
        @Override public void visit(ThematicBreak node) { text.append("---"); blockEnd(node); }
        @Override public void visit(CustomBlock node) { unsupported = true; }
        @Override public void visit(CustomNode node) { unsupported = true; }
    }
}
