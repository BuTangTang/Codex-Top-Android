package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;

import com.butang.codextop.DialogArrangement;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** D-83 会话页的分组行：置顶区／电脑标题、离线电脑、项目组标题和“已结束 N”；不承载会话本身。 */
public class CodexGroupCell extends View {
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detailPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable folder;
    private DialogArrangement.Row row;

    public CodexGroupCell(Context context) {
        super(context);
        folder = ContextCompat.getDrawable(context, R.drawable.msg_folders).mutate();
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        arrowPaint.setStrokeWidth(dp(1.6f));
    }

    public void setRow(DialogArrangement.Row row) {
        this.row = row;
        setContentDescription(description());
        requestLayout();
        invalidate();
    }

    public DialogArrangement.Row getRow() { return row; }

    /** 项目组与折叠行可点，标题行不可点。 */
    public boolean isToggle() {
        return row != null && (row.type == DialogArrangement.RowType.PROJECT_HEADER
                || row.type == DialogArrangement.RowType.FINISHED_FOLD);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        boolean section = row != null && (row.type == DialogArrangement.RowType.PENDING_HEADER
                || row.type == DialogArrangement.RowType.MACHINE_HEADER);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(section ? 36 : 48) + 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (row == null) return;
        int width = getMeasuredWidth(), height = getMeasuredHeight() - 1;
        boolean rtl = LocaleController.isRTL;
        int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int muted = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        titlePaint.setColor(text);
        titlePaint.setFakeBoldText(false);
        detailPaint.setColor(muted);
        detailPaint.setTextSize(dp(13));
        float baseline = height / 2f - (titlePaint.ascent() + titlePaint.descent()) / 2f;
        switch (row.type) {
            case PENDING_HEADER:
            case MACHINE_HEADER: {
                titlePaint.setTextSize(dp(13));
                titlePaint.setColor(muted);
                String label = row.type == DialogArrangement.RowType.PENDING_HEADER ? row.title + " " + row.count : row.title;
                baseline = height - dp(10);
                drawText(canvas, label, titlePaint, dp(16), width - dp(32), baseline, rtl, width);
                return;
            }
            case OFFLINE_MACHINE: {
                titlePaint.setTextSize(dp(15));
                titlePaint.setColor(muted);
                baseline = height / 2f - (titlePaint.ascent() + titlePaint.descent()) / 2f;
                String detail = (row.lastSeenMs > 0 ? "离线 · 上次在线 " + com.butang.codextop.CodexDates.format(row.lastSeenMs)
                        : "离线 · 时间未知") + " · " + row.count + " 会话";
                float detailWidth = Math.min(detailPaint.measureText(detail), width * 0.6f);
                drawText(canvas, detail, detailPaint, width - dp(16) - (int) detailWidth, (int) detailWidth, baseline, rtl, width);
                drawText(canvas, row.title, titlePaint, dp(16), (int) (width - dp(16 + 16 + 8) - detailWidth), baseline, rtl, width);
                break;
            }
            case PROJECT_HEADER: {
                titlePaint.setTextSize(dp(15));
                titlePaint.setFakeBoldText(true);
                baseline = height / 2f - (titlePaint.ascent() + titlePaint.descent()) / 2f;
                int iconSize = dp(20), iconLeft = rtl ? width - dp(12) - iconSize : dp(12);
                folder.setColorFilter(new PorterDuffColorFilter(muted, PorterDuff.Mode.SRC_IN));
                folder.setBounds(iconLeft, (height - iconSize) / 2, iconLeft + iconSize, (height + iconSize) / 2);
                folder.draw(canvas);
                float summaryWidth = Math.min(detailPaint.measureText(row.summary), width * 0.45f);
                int arrowSpace = dp(28);
                drawText(canvas, row.summary, detailPaint, width - arrowSpace - (int) summaryWidth, (int) summaryWidth, baseline, rtl, width);
                drawText(canvas, row.title, titlePaint, dp(42), (int) (width - dp(42) - arrowSpace - summaryWidth - dp(8)), baseline, rtl, width);
                drawArrow(canvas, rtl ? dp(16) : width - dp(16), height / 2f, row.expanded, muted);
                break;
            }
            case FINISHED_FOLD: {
                titlePaint.setTextSize(dp(14));
                titlePaint.setColor(muted);
                baseline = height / 2f - (titlePaint.ascent() + titlePaint.descent()) / 2f;
                drawText(canvas, row.title, titlePaint, dp(42), width - dp(42 + 28), baseline, rtl, width);
                drawArrow(canvas, rtl ? dp(16) : width - dp(16), height / 2f, row.expanded, muted);
                break;
            }
            default:
                return;
        }
        canvas.drawLine(rtl ? 0 : dp(42), height, rtl ? width - dp(42) : width, height, Theme.dividerPaint);
    }

    /** left 为 LTR 下的起点；RTL 时镜像到右侧。 */
    private void drawText(Canvas canvas, String value, TextPaint paint, int left, int maxWidth, float baseline, boolean rtl, int width) {
        if (TextUtils.isEmpty(value) || maxWidth <= 0) return;
        CharSequence shown = TextUtils.ellipsize(value, paint, maxWidth, TextUtils.TruncateAt.END);
        float measured = paint.measureText(shown, 0, shown.length());
        float x = rtl ? width - left - measured : left;
        canvas.drawText(shown, 0, shown.length(), x, baseline, paint);
    }

    /** 折叠时向右（RTL 向左），展开时向下。 */
    private void drawArrow(Canvas canvas, float cx, float cy, boolean expanded, int color) {
        arrowPaint.setColor(color);
        float s = dp(4);
        if (expanded) {
            canvas.drawLine(cx - s, cy - s / 2, cx, cy + s / 2, arrowPaint);
            canvas.drawLine(cx, cy + s / 2, cx + s, cy - s / 2, arrowPaint);
        } else {
            float d = LocaleController.isRTL ? -1 : 1;
            canvas.drawLine(cx - s / 2 * d, cy - s, cx + s / 2 * d, cy, arrowPaint);
            canvas.drawLine(cx + s / 2 * d, cy, cx - s / 2 * d, cy + s, arrowPaint);
        }
    }

    private String description() {
        if (row == null) return null;
        switch (row.type) {
            case PROJECT_HEADER: return "项目 " + row.title + "，" + row.summary + "，" + (row.expanded ? "已展开" : "已折叠");
            case FINISHED_FOLD: return row.title + "，" + (row.expanded ? "已展开" : "已折叠");
            case PENDING_HEADER: return row.title + " " + row.count;
            default: return row.title;
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClickable(isToggle());
    }
}
