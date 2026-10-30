package org.telegram.ui.Components.chat;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.inset.InAppKeyboardInsetView;
import org.telegram.ui.Components.inset.WindowInsetsProvider;

public class ChatInputViewsContainer extends FrameLayout {
    public static final int INPUT_BUBBLE_RADIUS = 22;
    public static final int INPUT_KEYBOARD_RADIUS = 29;

    public static final int INPUT_BUBBLE_BOTTOM = 9;

    private WindowInsetsProvider windowInsetsProvider;

    private final View fadeView;
    private final FrameLayout inputIslandBubbleContainer;
    private final FrameLayout inAppKeyboardBubbleContainer;

    public ChatInputViewsContainer(@NonNull Context context) {
        super(context);

        inputIslandBubbleContainer = new FrameLayout(context);
        addView(inputIslandBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        inAppKeyboardBubbleContainer = new FrameLayout(context) {
            @Override
            public void addView(View child, int width, int height) {
                super.addView(child, width, height);
                checkViewsPositions();
            }
        };
        addView(inAppKeyboardBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        fadeView = new View(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (backgroundWithFadeDrawable != null) {
                    backgroundWithFadeDrawable.draw(canvas);
                }
                super.dispatchDraw(canvas);
            }
        };
    }

    public View getFadeView() {
        return fadeView;
    }

    public void setWindowInsetsProvider(WindowInsetsProvider windowInsetsProvider) {
        this.windowInsetsProvider = windowInsetsProvider;
    }



    public boolean drawInputBackground = true;
    public BlurredBackgroundDrawable blurredBackgroundDrawable;
    private BlurredBackgroundDrawable underKeyboardBackgroundDrawable;
    private boolean codexComposeAppearance;
    private int codexComposeAlpha = 255;
    private Theme.ResourcesProvider codexComposeResourcesProvider;
    private final Paint codexComposeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint codexComposeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF codexComposeRect = new RectF();

    /** 只替换输入岛表面，控件、边界及键盘路径仍由原容器负责。 */
    public void setCodexComposeAppearance(boolean enabled, Theme.ResourcesProvider provider) {
        codexComposeAppearance = enabled;
        codexComposeResourcesProvider = provider;
        updateCodexComposeColors();
        invalidate();
    }

    private void updateCodexComposeColors() {
        if (!codexComposeAppearance) {
            return;
        }
        codexComposeFillPaint.setColor(Theme.getColor(Theme.key_chat_messagePanelBackground, codexComposeResourcesProvider) | 0xff000000);
        codexComposeFillPaint.setStyle(Paint.Style.FILL);
        codexComposeStrokePaint.setColor(Theme.getColor(Theme.key_actionBarDefaultTitle, codexComposeResourcesProvider) | 0xff000000);
        codexComposeStrokePaint.setStyle(Paint.Style.STROKE);
        codexComposeStrokePaint.setStrokeWidth(dp(1));
        codexComposeFillPaint.setAlpha(codexComposeAlpha);
        codexComposeStrokePaint.setAlpha(codexComposeAlpha);
        final com.butang.codextop.CodexPixelPalette.Style style = Theme.getCodexPixelStyle();
        codexComposeStyle = style.id;
        if (codexGroupFillPaint == null) {
            codexGroupFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            codexGroupOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            codexGroupStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        codexGroupFillPaint.setColor(style.surface);
        codexGroupOuterPaint.setColor(Theme.getColor(Theme.key_chat_messagePanelBackground, codexComposeResourcesProvider) | 0xff000000);
        codexGroupStrokePaint.setColor(style.outlineColor);
        codexGroupStrokePaint.setStyle(Paint.Style.STROKE);
        codexGroupStrokePaint.setStrokeWidth(dp(1));
        codexGroupFillPaint.setAlpha(codexComposeAlpha);
        codexGroupOuterPaint.setAlpha(codexComposeAlpha);
        codexGroupStrokePaint.setAlpha(codexComposeAlpha);
    }

    private boolean codexComposeGroupingActive;
    private int codexComposeStyle;
    private Paint codexGroupFillPaint;
    private Paint codexGroupOuterPaint;
    private Paint codexGroupStrokePaint;

    public void setCodexComposeGroupingActive(boolean active) {
        if (codexComposeGroupingActive == active) return;
        codexComposeGroupingActive = active;
        invalidate();
    }

    /** 五图仅共享颜色缓存，轮廓依照原 actor 在输入栏内的实际位置绘制。 */
    public void drawCodexComposeGrouping(Canvas canvas, float clipLeft, float clipTop, float clipRight, float clipBottom,
                                         float fieldLeft, float fieldTop, float fieldRight, float fieldBottom, float sendRight) {
        if (!codexComposeAppearance || !codexComposeGroupingActive || !drawInputBackground || codexComposeAlpha <= 0) return;
        final float halfStroke = codexGroupStrokePaint.getStrokeWidth() / 2f;
        final float radius = dp(codexComposeStyle == com.butang.codextop.CodexPixelPalette.STYLE_LIME ? 2 : 4);
        if (codexComposeStyle == com.butang.codextop.CodexPixelPalette.STYLE_INDUSTRIAL) {
            codexComposeRect.set(clipLeft - dp(3), fieldTop - dp(3), sendRight + dp(3), fieldBottom + dp(3));
            codexComposeRect.inset(halfStroke, halfStroke);
            canvas.drawRoundRect(codexComposeRect, dp(6), dp(6), codexGroupOuterPaint);
            canvas.drawRoundRect(codexComposeRect, dp(6), dp(6), codexGroupStrokePaint);
        }
        if (codexComposeStyle == com.butang.codextop.CodexPixelPalette.STYLE_TERMINAL
            || codexComposeStyle == com.butang.codextop.CodexPixelPalette.STYLE_BLUEPRINT) {
            codexComposeRect.set(clipLeft, clipTop, clipRight, clipBottom);
            codexComposeRect.inset(halfStroke, halfStroke);
            canvas.drawRoundRect(codexComposeRect, radius, radius, codexGroupFillPaint);
            canvas.drawRoundRect(codexComposeRect, radius, radius, codexGroupStrokePaint);
        }
        final boolean sharedClip = codexComposeStyle == com.butang.codextop.CodexPixelPalette.STYLE_LIME;
        final boolean sharedSend = codexComposeStyle == com.butang.codextop.CodexPixelPalette.STYLE_BLUEPRINT;
        codexComposeRect.set(sharedClip ? clipLeft : fieldLeft, fieldTop, sharedSend ? sendRight : fieldRight, fieldBottom);
        codexComposeRect.inset(halfStroke, halfStroke);
        canvas.drawRoundRect(codexComposeRect, radius, radius, codexGroupFillPaint);
        canvas.drawRoundRect(codexComposeRect, radius, radius, codexGroupStrokePaint);
        if (sharedClip) {
            final float divider = clipRight + dp(3);
            canvas.drawLine(divider, clipTop + dp(8), divider, clipBottom - dp(8), codexGroupStrokePaint);
        }
    }

    public void setInputIslandBubbleDrawable(BlurredBackgroundDrawable drawable) {
        blurredBackgroundDrawable = drawable;
        blurredBackgroundDrawable.setPadding(dp(7));
        blurredBackgroundDrawable.setRadius(dp(INPUT_BUBBLE_RADIUS));
    }

    public void setUnderKeyboardBackgroundDrawable(BlurredBackgroundDrawable drawable) {
        underKeyboardBackgroundDrawable = drawable;
        underKeyboardBackgroundDrawable.enableInAppKeyboardOptimization();
        underKeyboardBackgroundDrawable.setRadius(dp(INPUT_KEYBOARD_RADIUS), dp(INPUT_KEYBOARD_RADIUS), 0, 0);
        underKeyboardBackgroundDrawable.setThickness(dp(32));
        underKeyboardBackgroundDrawable.setIntensity(0.4f);
    }

    public void updateColors() {
        blurredBackgroundDrawable.updateColors();
        underKeyboardBackgroundDrawable.updateColors();
        updateCodexComposeColors();
        invalidate();
    }


    @NonNull
    public FrameLayout getInputIslandBubbleContainer() {
        return inputIslandBubbleContainer;
    }

    @NonNull
    public FrameLayout getInAppKeyboardBubbleContainer() {
        return inAppKeyboardBubbleContainer;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        checkViewsPositions();
        checkInAppKeyboardChild();
    }



    private void checkInAppKeyboardViewHeight() {
        LayoutParams lp = (LayoutParams) inAppKeyboardBubbleContainer.getLayoutParams();

        final int oldHeight = lp.height;
        final int newHeight = windowInsetsProvider.getInAppKeyboardRecommendedViewHeight();

        if (oldHeight != newHeight) {
            lp.height = newHeight;
            requestLayout();
        }
    }

    private final Path underKeyboardPath = new Path();

    private int currentBlurredHeight;
    private void checkBlurredHeight(boolean force) {
        checkViewsPositions();

        final int blurredHeight = inputBubbleHeightRound + dp(INPUT_BUBBLE_BOTTOM) + Math.round(maxBottomInset);
        if (currentBlurredHeight != blurredHeight || force) {
            currentBlurredHeight = blurredHeight;

            final int r = dp(INPUT_KEYBOARD_RADIUS);
            tmpRectF.set(0, getMeasuredHeight() - imeBottomInset, getMeasuredWidth(), getMeasuredHeight());
            underKeyboardPath.rewind();
            underKeyboardPath.addRoundRect(tmpRectF, new float[] {r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
            underKeyboardPath.close();
            invalidate();
        }
    }

    private float maxBottomInset;
    private float imeBottomInset;
    private boolean needDrawInAppKeyboard;

    public void checkInsets() {
        maxBottomInset = windowInsetsProvider.getAnimatedMaxBottomInset();
        imeBottomInset = windowInsetsProvider.getAnimatedImeBottomInset();

        needDrawInAppKeyboard = windowInsetsProvider.inAppViewIsVisible();

        if ((inAppKeyboardBubbleContainer.getVisibility() == VISIBLE) != needDrawInAppKeyboard) {
            inAppKeyboardBubbleContainer.setVisibility(needDrawInAppKeyboard ? VISIBLE : GONE);
        }

        checkInAppKeyboardViewHeight();
        checkBlurredHeight(false);
        checkInAppKeyboardChild();

        if (underKeyboardBackgroundDrawable != null) {
            int leftBottomRadius = 0;
            int rightBottomRadius = 0;
            if (Build.VERSION.SDK_INT >= 31) {
                final WindowInsets insets = getRootWindowInsets();
                if (insets != null) {
                    final RoundedCorner bottomLeft = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
                    final RoundedCorner bottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
                    leftBottomRadius = bottomLeft == null ? 0 : bottomLeft.getRadius();
                    rightBottomRadius = bottomRight == null ? 0 : bottomRight.getRadius();
                }
            }
            underKeyboardBackgroundDrawable.setRadius(dp(INPUT_KEYBOARD_RADIUS), dp(INPUT_KEYBOARD_RADIUS), rightBottomRadius, leftBottomRadius, true);
        }
    }

    private void checkViewsPositions() {
        inputIslandBubbleContainer.setTranslationY(-maxBottomInset - dp(INPUT_BUBBLE_BOTTOM));
        inAppKeyboardBubbleContainer.setTranslationY(inAppKeyboardBubbleContainer.getMeasuredHeight() - imeBottomInset);
    }


    private void checkInAppKeyboardChild() {
        final int navbarHeight = windowInsetsProvider.getCurrentNavigationBarInset();
        final float keyboardHeight = windowInsetsProvider.getAnimatedImeBottomInset();

        for (int a = 0, N = inAppKeyboardBubbleContainer.getChildCount(); a < N; a++) {
            final View child = inAppKeyboardBubbleContainer.getChildAt(a);
            if (child instanceof InAppKeyboardInsetView) {
                InAppKeyboardInsetView insetView = (InAppKeyboardInsetView) child;
                insetView.applyNavigationBarHeight(navbarHeight);
                insetView.applyInAppKeyboardAnimatedHeight(keyboardHeight);
            }
        }
    }



    /* */

    private float inputBubbleOffsetLeft;
    private float inputBubbleOffsetRight;

    private float inputBubbleHeight;
    private int inputBubbleHeightRound;
    public void setInputBubbleHeight(float height) {
        inputBubbleHeight = height;
        inputBubbleHeightRound = Math.round(inputBubbleHeight);
        checkBlurredHeight(false);
    }

    public void setInputBubbleOffsets(float left, float right) {
        inputBubbleOffsetLeft = left;
        inputBubbleOffsetRight = right;
        invalidate();
    }

    public float getInputBubbleHeight() {
        return inputBubbleHeight;
    }

    public float getInputBubbleTop() {
        return getInputBubbleBottom() - getInputBubbleHeight();
    }

    public float getInputBubbleBottom() {
        return getMeasuredHeight() - maxBottomInset - dp(INPUT_BUBBLE_BOTTOM);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        checkBlurredHeight(true);
        checkDrawableBounds();
        checkViewsPositions();
        checkInAppKeyboardChild();
    }

    /* Render */

    private final Rect tmpRect = new Rect();
    private final RectF tmpRectF = new RectF();

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        underKeyboardBackgroundDrawable.setBounds(
            0,
            getMeasuredHeight() - (int) imeBottomInset,
            getMeasuredWidth(),
            Math.max(getMeasuredHeight(), getMeasuredHeight() - (int) imeBottomInset + dp(INPUT_KEYBOARD_RADIUS * 2))
        );

        final int blurTop = getMeasuredHeight() - currentBlurredHeight;

        tmpRect.set(
            Math.round(inputBubbleOffsetLeft),
            0,
            getMeasuredWidth() - Math.round(inputBubbleOffsetRight),
            inputBubbleHeightRound
        );
        tmpRect.inset(0, -dp(7));
        tmpRect.offset(0, blurTop + (int) bubbleInputTranlationY);

        blurredBackgroundDrawable.setBounds(tmpRect);
        if (drawInputBackground) {
            if (codexComposeAppearance) {
                if (codexComposeAlpha > 0 && !codexComposeGroupingActive) {
                    codexComposeRect.set(tmpRect);
                    float halfStroke = codexComposeStrokePaint.getStrokeWidth() / 2f;
                    codexComposeRect.inset(halfStroke, halfStroke);
                    canvas.drawRoundRect(codexComposeRect, dp(2), dp(2), codexComposeFillPaint);
                    canvas.drawRoundRect(codexComposeRect, dp(2), dp(2), codexComposeStrokePaint);
                }
            } else {
                blurredBackgroundDrawable.draw(canvas);
            }
        }

        if (needDrawInAppKeyboard) {
            underKeyboardBackgroundDrawable.draw(canvas);
        }

        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        final boolean needClip = child == inAppKeyboardBubbleContainer;
        if (needClip) {
            canvas.save();
            canvas.clipPath(underKeyboardBackgroundDrawable.getPath());
        }

        final boolean result = super.drawChild(canvas, child, drawingTime);
        if (needClip) {
            canvas.restore();
        }

        return result;
    }





    private BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable;

    public void setBackgroundWithFadeDrawable(BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable) {
        this.backgroundWithFadeDrawable = backgroundWithFadeDrawable;
    }

    private float blurredBottomHeight;
    public void setBlurredBottomHeight(float height) {
        if (blurredBottomHeight != height) {
            blurredBottomHeight = height;
            checkDrawableBounds();
        }
    }

    private float bubbleInputTranlationY;
    public void setInputBubbleTranslationY(float translationY) {
        this.bubbleInputTranlationY = translationY;
        invalidate();
    }

    public void setInputBubbleAlpha(int alpha) {
        if (blurredBackgroundDrawable != null) {
            blurredBackgroundDrawable.setAlpha(alpha);
        }
        codexComposeAlpha = alpha;
        if (codexComposeAppearance) {
            codexComposeFillPaint.setAlpha(alpha);
            codexComposeStrokePaint.setAlpha(alpha);
            codexGroupFillPaint.setAlpha(alpha);
            codexGroupOuterPaint.setAlpha(alpha);
            codexGroupStrokePaint.setAlpha(alpha);
            invalidate();
        }
    }

    private void checkDrawableBounds() {
        if (backgroundWithFadeDrawable == null) {
            return;
        }

        final int oldBound = backgroundWithFadeDrawable.getBounds().top;
        final int newBound = getMeasuredHeight() - Math.round(blurredBottomHeight);

        if (oldBound != newBound) {
            backgroundWithFadeDrawable.setBounds(0, newBound, getMeasuredWidth(), getMeasuredHeight());
            fadeView.invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
            invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
        }
    }


    private boolean captured;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();

        if (action == MotionEvent.ACTION_DOWN) {
            final int x = (int) event.getX();
            final int y = (int) event.getY();

            captured = blurredBackgroundDrawable != null && blurredBackgroundDrawable.getAlpha() == 255 && blurredBackgroundDrawable.getBounds().contains(x, y)
                || underKeyboardBackgroundDrawable != null && underKeyboardBackgroundDrawable.getBounds().contains(x, y);

        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            captured = false;
        }

        return captured;
    }
}
