package com.butang.codextop;

import android.text.SpannableStringBuilder;
import android.text.Spanned;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;

/**
 * 会话列表单行预览的显示副本。
 * 只展开写法完整的行内图片、链接、成对强调和代码；不改消息对象、正文或附件。
 */
public final class CodexDialogPreview {
    /**
     * 列表预览最多扫描 1024 个 UTF-16 单元。
     * 更长的原文只看这一段；没改写、超过扫描预算，或窗口末尾仍有未闭合格式时返回原对象。
     * 不读取窗口之后的文字，也不补右括号或链接目标。
     */
    private static final int LIST_PREVIEW_LIMIT = 1024;

    /**
     * 括号、目标和强调最多再查看的字符数。
     * 未闭合标记会反复扫同一段后缀；超过这个固定次数就退回原文。
     */
    private static final int LIST_PREVIEW_WORK = 8192;

    /** 禁止实例化；调用方只取显示副本。 */
    private CodexDialogPreview() {}

    /**
     * 返回列表上使用的文字。
     * 没有可折叠格式、扫描超过 {@link #LIST_PREVIEW_WORK}，或超长原文的前缀在窗口末尾仍未闭合时，返回原 CharSequence 及其 span。
     * 有改写时只复制完整落在扫描窗口内、且没有跨过被删结构的 span；查询也停在窗口，不枚举窗口后的 span。
     * 然后交给原有换行处理。空图片说明使用调用方传入的原照片文案。
     */
    public static CharSequence readable(CharSequence text, String emptyImageLabel) {
        if (text == null || text.length() == 0) return text;
        int limit = scanEnd(text);
        if (limit <= 0) return text;
        String source = copyPrefix(text, limit);
        String label = emptyImageLabel == null ? "" : emptyImageLabel;
        Scan scan = new Scan(limit < text.length(), source);
        String flattened = flatten(source, label, null, scan);
        if (scan.work > LIST_PREVIEW_WORK || scan.openAtEnd || flattened.equals(source)) return text;
        CharSequence edited = flattened;
        if (text instanceof Spanned) {
            ArrayList<Piece> pieces = new ArrayList<>();
            Scan again = new Scan(scan.truncated, source);
            flatten(source, label, pieces, again);
            if (again.work > LIST_PREVIEW_WORK || again.openAtEnd) return text;
            edited = copyKeptSpans((Spanned) text, flattened, pieces, limit);
        }
        return AndroidUtilities.replaceNewLines(edited);
    }

    /** 扫描终点不超过 1024，并且不把窗口末尾的高位代理和窗口外的低位代理拆开。 */
    private static int scanEnd(CharSequence text) {
        int end = Math.min(text.length(), LIST_PREVIEW_LIMIT);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return end;
    }

    /** 只复制扫描窗口。String 直接截取；其他文本逐字读取，避免为了前缀把整段超长原文变成字符串。 */
    private static String copyPrefix(CharSequence text, int end) {
        if (text instanceof String) return ((String) text).substring(0, end);
        char[] chars = new char[end];
        for (int index = 0; index < end; index++) chars[index] = text.charAt(index);
        return new String(chars);
    }

    /** 一次扫描的预算和窗口状态。未闭合标记只有走到顶层窗口末尾才算，子串内部的失败不算。 */
    private static final class Scan {
        final boolean truncated;
        final String prefix;
        int work;
        boolean openAtEnd;

        /** 记录这次扫描是否只看了超长原文的前缀，以及前缀字符串本身。 */
        Scan(boolean truncated, String prefix) {
            this.truncated = truncated;
            this.prefix = prefix;
        }

        /** 记一笔扫描。超过固定次数后返回 true，整段预览改退回原文。 */
        boolean charge() {
            work++;
            return work > LIST_PREVIEW_WORK;
        }

        /** 顶层窗口走到末尾仍未闭合时，标记整段预览必须退回原文。 */
        void noteEnd(String source) {
            if (truncated && source == prefix) openAtEnd = true;
        }
    }

    /** 单次扫描完整格式。失败的片段原样留下，避免把未闭合标记整段删掉。 */
    private static String flatten(String source, String emptyImageLabel, ArrayList<Piece> pieces, Scan scan) {
        StringBuilder out = new StringBuilder(source.length());
        flattenInto(source, 0, emptyImageLabel, out, pieces, scan);
        return out.toString();
    }

    /**
     * 把 source 写进已有输出。base 是这段文字在完整原文中的起点，用来给保留片段定位。
     */
    private static void flattenInto(String source, int base, String emptyImageLabel, StringBuilder out, ArrayList<Piece> pieces, Scan scan) {
        int index = 0;
        while (index < source.length() && scan.work <= LIST_PREVIEW_WORK) {
            int consumed = appendConstruct(source, index, base, emptyImageLabel, out, pieces, scan);
            if (consumed > index) {
                index = consumed;
                continue;
            }
            int output = out.length();
            out.append(source.charAt(index));
            keep(pieces, base + index, base + index + 1, output, out.length());
            index++;
        }
    }

    /** 记下原样复制的源区间。相邻的保留文字并成一段，避免每个字符单独分配。 */
    private static void keep(ArrayList<Piece> pieces, int sourceStart, int sourceEnd, int outputStart, int outputEnd) {
        if (pieces == null || sourceEnd <= sourceStart) return;
        int last = pieces.size() - 1;
        if (last >= 0) {
            Piece piece = pieces.get(last);
            if (piece.sourceEnd == sourceStart && piece.outputEnd == outputStart) {
                piece.sourceEnd = sourceEnd;
                piece.outputEnd = outputEnd;
                return;
            }
        }
        pieces.add(new Piece(sourceStart, sourceEnd, outputStart, outputEnd));
    }

    /**
     * 复制完整落在扫描窗口内、且没有跨过删除区的原 span。
     * 越过窗口、落在窗口外或盖住链接目标的 span 直接丢掉，不用旧偏移贴回新文字。
     */
    private static SpannableStringBuilder copyKeptSpans(Spanned original, String flattened, ArrayList<Piece> pieces, int prefixLength) {
        SpannableStringBuilder builder = new SpannableStringBuilder(flattened);
        for (Object span : original.getSpans(0, prefixLength, Object.class)) {
            int start = original.getSpanStart(span);
            int end = original.getSpanEnd(span);
            if (start < 0 || end < start || start >= prefixLength || end > prefixLength) continue;
            if (start != end && overlapsReplacement(pieces, start, end)) continue;
            int mappedStart = mapPoint(pieces, start, prefixLength, builder.length());
            int mappedEnd = mapPoint(pieces, end, prefixLength, builder.length());
            if (mappedStart < 0 || mappedEnd < mappedStart || mappedEnd > builder.length()) continue;
            builder.setSpan(span, mappedStart, mappedEnd, original.getSpanFlags(span));
        }
        return builder;
    }

    /** 跨过被删标记或链接目标时，这段 span 的旧范围已经对不上显示副本。 */
    private static boolean overlapsReplacement(ArrayList<Piece> pieces, int start, int end) {
        int cursor = start;
        for (Piece piece : pieces) {
            if (piece.sourceEnd <= cursor) continue;
            if (piece.sourceStart > cursor) return true;
            cursor = Math.min(end, piece.sourceEnd);
            if (cursor == end) return false;
        }
        return true;
    }

    /** 把落在保留片段上的源位置换成显示副本位置；删除区返回 -1。 */
    private static int mapPoint(ArrayList<Piece> pieces, int point, int sourceLength, int outputLength) {
        if (point == 0) return 0;
        for (Piece piece : pieces) {
            if (point < piece.sourceStart) return -1;
            if (point <= piece.sourceEnd) return piece.outputStart + (point - piece.sourceStart);
        }
        return point == sourceLength ? outputLength : -1;
    }

    /** 一段原样保留的源文字，以及它在显示副本中的位置。 */
    private static final class Piece {
        final int sourceStart;
        int sourceEnd;
        final int outputStart;
        int outputEnd;

        /** 记录一个没有被格式改写的连续区间。 */
        Piece(int sourceStart, int sourceEnd, int outputStart, int outputEnd) {
            this.sourceStart = sourceStart;
            this.sourceEnd = sourceEnd;
            this.outputStart = outputStart;
            this.outputEnd = outputEnd;
        }
    }

    /** 尝试从当前位置吃掉一种完整格式；吃不掉时返回原位置。保留下来的原文会记入 pieces。 */
    private static int appendConstruct(String source, int index, int base, String emptyImageLabel, StringBuilder out, ArrayList<Piece> pieces, Scan scan) {
        if (isEscaped(source, index)) return index;
        if (source.charAt(index) == '\\' && index + 1 < source.length() && isPunctuation(source.charAt(index + 1))) {
            char escaped = source.charAt(index + 1);
            int bracket = escaped == '!' && index + 2 < source.length() && source.charAt(index + 2) == '[' ? index + 2
                    : escaped == '[' ? index + 1 : -1;
            if (bracket >= 0) {
                int end = linkEnd(source, bracket, scan);
                if (end > bracket) {
                    int output = out.length();
                    out.append(source, index, end);
                    keep(pieces, base + index, base + end, output, out.length());
                    return end;
                }
            }
            int output = out.length();
            out.append('\\').append(escaped);
            keep(pieces, base + index, base + index + 2, output, out.length());
            return index + 2;
        }
        if (isLineStart(source, index)) {
            int fenceEnd = fenceEnd(source, index, scan);
            if (fenceEnd > index) {
                appendFence(source, index, fenceEnd, base, out, pieces);
                return fenceEnd;
            }
        }
        char current = source.charAt(index);
        if (current == '`') {
            int run = runLength(source, index, '`');
            int close = findExactRun(source, index + run, '`', run, scan);
            if (close >= 0) {
                int output = out.length();
                out.append(source, index + run, close);
                keep(pieces, base + index + run, base + close, output, out.length());
                return close + run;
            }
        }
        if (current == '!' && index + 1 < source.length() && source.charAt(index + 1) == '[') {
            int end = linkEnd(source, index + 1, scan);
            if (end > index) {
                appendLink(source, index + 1, true, base, emptyImageLabel, out, pieces, scan);
                return end;
            }
        }
        if (current == '[') {
            int end = linkEnd(source, index, scan);
            if (end > index) {
                appendLink(source, index, false, base, emptyImageLabel, out, pieces, scan);
                return end;
            }
        }
        if (current == '*' || current == '_') {
            int run = runLength(source, index, current);
            if (run > 2) return index;
            int close = emphasisClose(source, index + run, current, run, scan);
            int after = close + run;
            if (close > index + run && unseenAfter(scan, source, after)) {
                // 闭合是否成立要看标记后面那个字符；窗口到此为止时不能把它当成字符串结束。
                scan.noteEnd(source);
            } else if (close > index + run && isBoundary(source, index - 1) && isBoundary(source, after)) {
                flattenInto(source.substring(index + run, close), base + index + run, emptyImageLabel, out, pieces, scan);
                return close + run;
            }
        }
        return index;
    }

    /** 反斜杠只保护紧挨着的标点，不把后面的正文一起跳过。 */
    private static boolean isEscaped(String source, int index) {
        int slashes = 0;
        for (int cursor = index - 1; cursor >= 0 && source.charAt(cursor) == '\\'; cursor--) slashes++;
        return (slashes & 1) == 1;
    }

    /** 行首才识别围栏，避免把段中的反引号当成代码块。 */
    private static boolean isLineStart(String source, int index) {
        return index == 0 || source.charAt(index - 1) == '\n';
    }

    /** 截断窗口的末尾没有这个下标。需要它才能确认的闭合不能当成原文已经结束。 */
    private static boolean unseenAfter(Scan scan, String source, int index) {
        return scan.truncated && source == scan.prefix && index >= source.length();
    }

    /** 标记两侧的英文数字不构成强调，中文和空白可以。 */
    private static boolean isBoundary(String source, int index) {
        if (index < 0 || index >= source.length()) return true;
        char value = source.charAt(index);
        return !((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9'));
    }

    /** 常见 Markdown 标点。转义时两个字符都保留，不把转义本身显示成格式。 */
    private static boolean isPunctuation(char value) {
        return "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(value) >= 0;
    }

    /** 统计同一标记连续出现的次数。 */
    private static int runLength(String source, int index, char mark) {
        int end = index;
        while (end < source.length() && source.charAt(end) == mark) end++;
        return end - index;
    }

    /** 寻找同样长度、且没有更长连续标记包住的闭合位置。 */
    private static int findExactRun(String source, int from, char mark, int count, Scan scan) {
        for (int index = from; index + count <= source.length(); index++) {
            if (scan.charge()) return -1;
            if (isEscaped(source, index) || source.charAt(index) != mark) continue;
            int run = runLength(source, index, mark);
            if (run == count) {
                if (unseenAfter(scan, source, index + run)) {
                    // 紧贴窗口末尾的标记可能在窗外继续，长度还不能确定。
                    scan.noteEnd(source);
                    return -1;
                }
                return index;
            }
            index += run - 1;
        }
        scan.noteEnd(source);
        return -1;
    }

    /** 闭合强调时跳过已经完整的代码和链接，避免在它们内部截断。 */
    private static int emphasisClose(String source, int from, char mark, int count, Scan scan) {
        for (int index = from; index + count <= source.length(); index++) {
            if (scan.charge()) return -1;
            if (isEscaped(source, index)) {
                index++;
                continue;
            }
            char current = source.charAt(index);
            if (current == '`') {
                int run = runLength(source, index, '`');
                int close = findExactRun(source, index + run, '`', run, scan);
                if (close >= 0) {
                    index = close + run - 1;
                    continue;
                }
            } else if (current == '!' && index + 1 < source.length() && source.charAt(index + 1) == '[') {
                int end = linkEnd(source, index + 1, scan);
                if (end > index) {
                    index = end - 1;
                    continue;
                }
            } else if (current == '[') {
                int end = linkEnd(source, index, scan);
                if (end > index) {
                    index = end - 1;
                    continue;
                }
            }
            if (source.charAt(index) == mark && runLength(source, index, mark) == count) return index;
        }
        scan.noteEnd(source);
        return -1;
    }

    /** 读取完整围栏的结束位置；信息行或结束行不完整时返回起点。窗口末尾仍未结束时记下未闭合。 */
    private static int fenceEnd(String source, int start, Scan scan) {
        char mark = source.charAt(start);
        if (mark != '`' && mark != '~') return start;
        int count = runLength(source, start, mark);
        if (count < 3) return start;
        int infoEnd = source.indexOf('\n', start + count);
        if (infoEnd < 0) {
            scan.noteEnd(source);
            return start;
        }
        if (mark == '`' && source.substring(start + count, infoEnd).indexOf('`') >= 0) return start;
        int line = infoEnd + 1;
        while (line < source.length()) {
            int next = source.indexOf('\n', line);
            int lineEnd = next < 0 ? source.length() : next;
            if (closesFence(source, line, lineEnd, mark, count)) {
                if (next < 0 && unseenAfter(scan, source, lineEnd)) {
                    // 结束行没有换行就碰到窗口末尾，后面还可能有非空白字符。
                    scan.noteEnd(source);
                    return start;
                }
                return lineEnd + (next < 0 ? 0 : 1);
            }
            if (next < 0) break;
            line = next + 1;
        }
        scan.noteEnd(source);
        return start;
    }

    /** 结束行只能包含围栏标记和空白，且标记不能短于开头。 */
    private static boolean closesFence(String source, int start, int end, char mark, int count) {
        int index = start;
        while (index < end && source.charAt(index) == ' ') index++;
        if (index >= end || source.charAt(index) != mark) return false;
        int run = runLength(source, index, mark);
        if (run < count) return false;
        for (int cursor = index + run; cursor < end; cursor++) {
            if (source.charAt(cursor) != ' ' && source.charAt(cursor) != '\t') return false;
        }
        return true;
    }

    /** 写入围栏中间的代码，并记下这段原文位置；围栏行本身不进入预览。 */
    private static void appendFence(String source, int start, int fenceEnd, int base, StringBuilder out, ArrayList<Piece> pieces) {
        int bodyStart = source.indexOf('\n', start) + 1;
        int bodyEnd = fenceEnd;
        if (bodyEnd > bodyStart && source.charAt(bodyEnd - 1) == '\n') bodyEnd--;
        int line = bodyEnd;
        while (line > bodyStart && source.charAt(line - 1) != '\n') line--;
        if (line > bodyStart && closesFence(source, line, bodyEnd, source.charAt(start), runLength(source, start, source.charAt(start)))) {
            bodyEnd = line;
            if (bodyEnd > bodyStart && source.charAt(bodyEnd - 1) == '\n') bodyEnd--;
        }
        int output = out.length();
        out.append(source, bodyStart, bodyEnd);
        keep(pieces, base + bodyStart, base + bodyEnd, output, out.length());
    }

    /** 链接或图片必须有闭合标签和目标，否则整段保持原样。 */
    private static int linkEnd(String source, int bracket, Scan scan) {
        int labelEnd = closingBracket(source, bracket + 1, scan);
        if (labelEnd < 0) return -1;
        if (labelEnd + 1 >= source.length()) {
            scan.noteEnd(source);
            return -1;
        }
        if (source.charAt(labelEnd + 1) != '(') return -1;
        int destinationEnd = destinationClose(source, labelEnd + 1, scan);
        return destinationEnd < 0 ? -1 : destinationEnd + 1;
    }

    /** 标签内的方括号按层配对，换行或转义右括号都不提前结束。 */
    private static int closingBracket(String source, int start, Scan scan) {
        int depth = 1;
        for (int index = start; index < source.length() && source.charAt(index) != '\n'; index++) {
            if (scan.charge()) return -1;
            if (isEscaped(source, index)) continue;
            char value = source.charAt(index);
            if (value == '[') depth++;
            else if (value == ']') {
                depth--;
                if (depth == 0) return index;
            }
        }
        if (start >= source.length()) {
            scan.noteEnd(source);
            return -1;
        }
        if (source.charAt(start) == '\n') return -1;
        int index = start;
        while (index < source.length() && source.charAt(index) != '\n') index++;
        if (index >= source.length()) scan.noteEnd(source);
        return -1;
    }

    /** 普通目标按圆括号层数结束；尖括号目标允许空格，标题写法保持不动。 */
    private static int destinationClose(String source, int openParen, Scan scan) {
        int index = openParen + 1;
        if (index >= source.length()) {
            scan.noteEnd(source);
            return -1;
        }
        if (source.charAt(index) == '<') {
            int angle = index + 1;
            while (angle < source.length() && source.charAt(angle) != '\n') {
                if (scan.charge()) return -1;
                if (source.charAt(angle) == '>' && !isEscaped(source, angle)) break;
                angle++;
            }
            if (angle >= source.length()) {
                scan.noteEnd(source);
                return -1;
            }
            if (source.charAt(angle) != '>' || angle + 1 >= source.length() || source.charAt(angle + 1) != ')') {
                if (source.charAt(angle) == '>' && angle + 1 >= source.length()) scan.noteEnd(source);
                return -1;
            }
            return angle + 1;
        }
        int depth = 1;
        while (index < source.length()) {
            if (scan.charge()) return -1;
            char value = source.charAt(index);
            if (value == '\n') return -1;
            if (!isEscaped(source, index)) {
                if (value == '(') depth++;
                else if (value == ')') {
                    depth--;
                    if (depth == 0) return index;
                } else if (Character.isWhitespace(value) && depth == 1) return -1;
            }
            index++;
        }
        scan.noteEnd(source);
        return -1;
    }

    /** 链接留下标签。图片说明只展开一次；展开后仍是空白时，改用调用方给出的照片文案并撤掉这段 span。 */
    private static void appendLink(String source, int bracket, boolean image, int base, String emptyImageLabel, StringBuilder out, ArrayList<Piece> pieces, Scan scan) {
        int labelEnd = closingBracket(source, bracket + 1, scan);
        int labelStart = bracket + 1;
        if (labelEnd < labelStart || scan.work > LIST_PREVIEW_WORK) return;
        int outputBefore = out.length();
        int piecesBefore = pieces == null ? 0 : pieces.size();
        int savedSourceEnd = 0;
        int savedOutputEnd = 0;
        if (piecesBefore > 0) {
            Piece last = pieces.get(piecesBefore - 1);
            savedSourceEnd = last.sourceEnd;
            savedOutputEnd = last.outputEnd;
        }
        flattenInto(source.substring(labelStart, labelEnd), base + labelStart, emptyImageLabel, out, pieces, scan);
        if (!image || !regionIsBlank(out, outputBefore)) return;
        out.setLength(outputBefore);
        if (pieces != null) {
            while (pieces.size() > piecesBefore) pieces.remove(pieces.size() - 1);
            if (piecesBefore > 0) {
                Piece last = pieces.get(piecesBefore - 1);
                last.sourceEnd = savedSourceEnd;
                last.outputEnd = savedOutputEnd;
            }
        }
        out.append(emptyImageLabel);
    }

    /** 判断刚写入的图片说明是否没有可见文字。 */
    private static boolean regionIsBlank(StringBuilder out, int from) {
        for (int index = from; index < out.length(); index++) {
            if (!Character.isWhitespace(out.charAt(index))) return false;
        }
        return true;
    }
}
