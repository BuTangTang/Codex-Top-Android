package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/** D-83 “我的”页的两种行：账号卡（头像字母、名字、连接状态、箭头）和带电脑选择的“额度”标题。 */
public final class CodexMineCells {
    private CodexMineCells() { }

    /** 账号行：圆形头像写账号首字母；已连接绿点，其余灰点。 */
    public static class AccountRow extends View {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint statusPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint letterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String name = "", status = "";
        private boolean connected;

        public AccountRow(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            namePaint.setTextSize(dp(16));
            statusPaint.setTextSize(dp(13));
            letterPaint.setTextSize(dp(16));
            letterPaint.setTypeface(AndroidUtilities.bold());
            letterPaint.setTextAlign(Paint.Align.CENTER);
        }

        void set(String name, String status, boolean connected) {
            this.name = name == null ? "" : name;
            this.status = status == null ? "" : status;
            this.connected = connected;
            setContentDescription(this.name + "，" + this.status);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(64));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth(), height = getHeight();
            boolean rtl = LocaleController.isRTL;
            int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
            int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            int muted = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            float radius = dp(18), cx = rtl ? width - dp(16) - radius : dp(16) + radius, cy = height / 2f;
            fill.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x2E));
            canvas.drawCircle(cx, cy, radius, fill);
            letterPaint.setColor(accent);
            String letter = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
            canvas.drawText(letter, cx, cy - (letterPaint.ascent() + letterPaint.descent()) / 2f, letterPaint);

            int textStart = dp(16 + 36 + 12), textWidth = width - textStart - dp(40);
            namePaint.setColor(ink);
            statusPaint.setColor(muted);
            CharSequence shownName = TextUtils.ellipsize(name, namePaint, textWidth, TextUtils.TruncateAt.END);
            CharSequence shownStatus = TextUtils.ellipsize(status, statusPaint, textWidth - dp(12), TextUtils.TruncateAt.END);
            float nameX = rtl ? width - textStart - namePaint.measureText(shownName, 0, shownName.length()) : textStart;
            canvas.drawText(shownName, 0, shownName.length(), nameX, cy - dp(4), namePaint);
            fill.setColor(connected ? 0xFF2EAD5B : muted);
            float dotX = rtl ? width - textStart - dp(4) : textStart + dp(4);
            float statusBase = cy + dp(16);
            canvas.drawCircle(dotX, statusBase - dp(4.5f), dp(3.5f), fill);
            float statusX = rtl ? width - textStart - dp(12) - statusPaint.measureText(shownStatus, 0, shownStatus.length()) : textStart + dp(12);
            canvas.drawText(shownStatus, 0, shownStatus.length(), statusX, statusBase, statusPaint);
            drawChevron(canvas, rtl ? dp(20) : width - dp(20), cy, muted, false);
        }

        public static class Factory extends UItem.UItemFactory<AccountRow> {
            static { setup(new Factory()); }
            @Override
            public AccountRow createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new AccountRow(context, resourcesProvider);
            }
            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((AccountRow) view).set(String.valueOf(item.text), String.valueOf(item.subtext), item.checked);
            }
            public static UItem of(int id, String name, String status, boolean connected) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.text = name;
                item.subtext = status;
                item.checked = connected;
                return item;
            }
            @Override
            public boolean equals(UItem a, UItem b) { return a.id == b.id; }
            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                return TextUtils.equals(a.text, b.text) && TextUtils.equals(a.subtext, b.subtext) && a.checked == b.checked;
            }
        }
    }

    /** “额度”标题：右侧是当前来源电脑的小按钮，点整行切换电脑。 */
    public static class QuotaHeader extends View {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint chipPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String machine = "";

        public QuotaHeader(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            titlePaint.setTextSize(dp(15));
            titlePaint.setTypeface(AndroidUtilities.bold());
            chipPaint.setTextSize(dp(13));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1));
        }

        void set(String machine) {
            this.machine = machine == null ? "" : machine;
            setContentDescription("额度，来源电脑 " + this.machine);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(48));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth(), height = getHeight();
            boolean rtl = LocaleController.isRTL;
            int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            int muted = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            titlePaint.setColor(ink);
            float base = height / 2f - (titlePaint.ascent() + titlePaint.descent()) / 2f;
            float titleWidth = titlePaint.measureText("额度");
            canvas.drawText("额度", rtl ? width - dp(16) - titleWidth : dp(16), base, titlePaint);
            chipPaint.setColor(muted);
            int maxChip = width / 2;
            CharSequence shown = TextUtils.ellipsize(machine, chipPaint, maxChip - dp(36), TextUtils.TruncateAt.END);
            float textWidth = chipPaint.measureText(shown, 0, shown.length());
            float chipWidth = textWidth + dp(10 + 18);
            float right = rtl ? dp(16) + chipWidth : width - dp(16), left = right - chipWidth;
            stroke.setColor(Theme.getColor(Theme.key_divider, resourcesProvider));
            AndroidUtilities.rectTmp.set(left, height / 2f - dp(13), right, height / 2f + dp(13));
            canvas.drawRoundRect(AndroidUtilities.rectTmp, dp(8), dp(8), stroke);
            float chipBase = height / 2f - (chipPaint.ascent() + chipPaint.descent()) / 2f;
            canvas.drawText(shown, 0, shown.length(), left + dp(10), chipBase, chipPaint);
            drawChevron(canvas, right - dp(11), height / 2f, muted, true);
        }

        public static class Factory extends UItem.UItemFactory<QuotaHeader> {
            static { setup(new Factory()); }
            @Override
            public QuotaHeader createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new QuotaHeader(context, resourcesProvider);
            }
            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                ((QuotaHeader) view).set(String.valueOf(item.text));
            }
            public static UItem of(int id, String machine) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.text = machine;
                return item;
            }
            @Override
            public boolean equals(UItem a, UItem b) { return a.id == b.id; }
            @Override
            public boolean contentsEquals(UItem a, UItem b) { return TextUtils.equals(a.text, b.text); }
        }
    }

    private static final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** down 为 true 时向下，否则向行尾。 */
    static void drawChevron(Canvas canvas, float cx, float cy, int color, boolean down) {
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeWidth(dp(1.6f));
        chevronPaint.setColor(color);
        float s = dp(down ? 3.5f : 4.5f);
        if (down) {
            canvas.drawLine(cx - s, cy - s / 2, cx, cy + s / 2, chevronPaint);
            canvas.drawLine(cx, cy + s / 2, cx + s, cy - s / 2, chevronPaint);
        } else {
            float d = LocaleController.isRTL ? -1 : 1;
            canvas.drawLine(cx - s / 2 * d, cy - s, cx + s / 2 * d, cy, chevronPaint);
            canvas.drawLine(cx + s / 2 * d, cy, cx - s / 2 * d, cy + s, chevronPaint);
        }
    }
}
