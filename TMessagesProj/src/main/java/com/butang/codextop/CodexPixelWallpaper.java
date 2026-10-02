package com.butang.codextop;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;

/** 聊天纸面。底色仍由 ColorDrawable 提供，方点只在构造时画进一小张可重复贴图。 */
public final class CodexPixelWallpaper extends ColorDrawable {
    private final BitmapShader shader;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 按当前底色和屏幕密度生成一次 16 dp 透明贴图；density 由主题入口传入，draw 只复用贴图。 */
    public CodexPixelWallpaper(int color, float density) {
        super(color);
        int tileSize = Math.max(1, (int) Math.ceil(16f * density));
        float dotSize = 0.75f * density;
        float midpoint = 8f * density;
        Bitmap tile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tile);
        Paint dot = new Paint();
        boolean dark = perceived(color) < 128;
        dot.setColor(dark ? 0xB02C3D52 : 0x8CB7C9DA);
        canvas.drawRect(0f, 0f, dotSize, dotSize, dot);
        dot.setColor(dark ? 0x88243446 : 0x73C5D4E3);
        canvas.drawRect(midpoint, midpoint, midpoint + dotSize, midpoint + dotSize, dot);
        shader = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        paint.setShader(shader);
    }

    /** 先铺原来的底色，再盖稀疏方点。不在这里创建新的 bitmap。 */
    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        paint.setAlpha(getAlpha());
        canvas.drawRect(getBounds(), paint);
    }

    /** 只用亮度区分日夜纸面，不读取主题名。 */
    private static int perceived(int color) {
        return (((color >> 16) & 255) * 30 + ((color >> 8) & 255) * 59 + (color & 255) * 11) / 100;
    }
}
