/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.text.TextUtils;
import android.widget.Button;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RadialProgressView;

public class ChatLoadingCell extends FrameLayout {

    private FrameLayout frameLayout;
    private RadialProgressView progressBar;
    private Button retryButton;
    private Runnable retryAction;
    private Theme.ResourcesProvider resourcesProvider;

    public ChatLoadingCell(Context context, View parent, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        frameLayout = new FrameLayout(context) {
            private final RectF rect = new RectF();
            @Override
            protected void dispatchDraw(Canvas canvas) {
                rect.set(0, 0, getWidth(), getHeight());
                applyServiceShaderMatrix();
                canvas.drawRoundRect(rect, dp(18), dp(18), getThemedPaint(Theme.key_paint_chatActionBackground));
                if (hasGradientService()) {
                    canvas.drawRoundRect(rect, dp(18), dp(18), getThemedPaint(Theme.key_paint_chatActionBackgroundDarken));
                }

                super.dispatchDraw(canvas);
            }
        };
        frameLayout.setWillNotDraw(false);
        addView(frameLayout, LayoutHelper.createFrame(36, 36, Gravity.CENTER));

        progressBar = new RadialProgressView(context, resourcesProvider);
        progressBar.setSize(dp(28));
        progressBar.setProgressColor(getThemedColor(Theme.key_chat_serviceText));
        frameLayout.addView(progressBar, LayoutHelper.createFrame(32, 32, Gravity.CENTER));
    }

    public boolean hasGradientService() {
        return resourcesProvider != null ? resourcesProvider.hasGradientService() : Theme.hasGradientService();
    }

    private float viewTop;
    private int backgroundHeight;
    public void applyServiceShaderMatrix() {
        applyServiceShaderMatrix(getMeasuredWidth(), backgroundHeight, getX(), viewTop);
    }

    private void applyServiceShaderMatrix(int measuredWidth, int backgroundHeight, float x, float viewTop) {
        if (resourcesProvider != null) {
            resourcesProvider.applyServiceShaderMatrix(measuredWidth, backgroundHeight, x, viewTop);
        } else {
            Theme.applyServiceShaderMatrix(measuredWidth, backgroundHeight, x, viewTop);
        }
    }

    public void setVisiblePart(float viewTop, int backgroundHeight) {
        if (this.viewTop != viewTop) {
            invalidate();
        }
        this.viewTop = viewTop;
        this.backgroundHeight = backgroundHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(44), MeasureSpec.EXACTLY));
    }

    /** 普通加载状态同时撤销复用单元中的旧重试入口，原 Telegram 行保持原样。 */
    public void setProgressVisible(boolean value) {
        setRetryAction(null, null);
        frameLayout.setVisibility(value ? VISIBLE : INVISIBLE);
    }

    /** 本行继续加载或明确失败重试共用按钮；沿原 44dp 高度和服务文字主题，回收时清理可点击状态。 */
    public void setRetryAction(CharSequence text, Runnable action) {
        retryAction = action;
        if (action == null) {
            if (retryButton != null) {
                retryButton.setVisibility(GONE);
                retryButton.setEnabled(false);
            }
            return;
        }
        if (retryButton == null) {
            retryButton = new Button(getContext());
            retryButton.setAllCaps(false);
            retryButton.setTextSize(14);
            retryButton.setMinWidth(0);
            retryButton.setMinHeight(0);
            retryButton.setPadding(dp(12), 0, dp(12), 0);
            retryButton.setSingleLine(true);
            retryButton.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            retryButton.setGravity(Gravity.CENTER);
            // 只执行当前绑定的动作，单元复用后不能保留上一次的重试回调。
            retryButton.setOnClickListener(view -> {
                Runnable current = retryAction;
                if (current != null) current.run();
            });
            addView(retryButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 12, 0, 12, 0));
        }
        retryButton.setText(text);
        retryButton.setContentDescription(text);
        retryButton.setTextColor(getThemedColor(Theme.key_chat_serviceText));
        retryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18),
                getThemedColor(Theme.key_chat_serviceBackground), getThemedColor(Theme.key_chat_serviceBackgroundSelector)));
        retryButton.setEnabled(true);
        retryButton.setVisibility(VISIBLE);
        frameLayout.setVisibility(INVISIBLE);
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private Paint getThemedPaint(String paintKey) {
        Paint paint = resourcesProvider != null ? resourcesProvider.getPaint(paintKey) : null;
        return paint != null ? paint : Theme.getThemePaint(paintKey);
    }
}
