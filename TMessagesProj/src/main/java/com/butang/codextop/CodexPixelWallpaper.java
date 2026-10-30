package com.butang.codextop;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;

/** 静态聊天纸面。最多保留两张小纹理，绘制只复用已有 Paint 和 Shader，不读取文件或网络。 */
public final class CodexPixelWallpaper extends ColorDrawable {
    private static final int MAX_TILE_SIZE = 128;
    private static Tile recentTile;
    private static Tile olderTile;
    private final Paint paint;

    /** 缓存只保存不可变像素；移出时不 recycle，以免破坏仍在绘制的旧 Drawable。 */
    private static final class Tile {
        final int kind, size, primary, secondary;
        final Bitmap bitmap;

        /** 记录构造时确定的完整纹理键，不共享可变 Paint 或 Shader。 */
        Tile(int kind, int size, int primary, int secondary, Bitmap bitmap) {
            this.kind = kind;
            this.size = size;
            this.primary = primary;
            this.secondary = secondary;
            this.bitmap = bitmap;
        }

        /** 所有影响纹理像素的参数相同时才允许复用。 */
        boolean matches(int kind, int size, int primary, int secondary) {
            return this.kind == kind && this.size == size && this.primary == primary && this.secondary == secondary;
        }
    }

    /** 保留原入口的 16dp 双方点纸面；亮度只选择原方点色，不据底色猜测五套样式。 */
    public CodexPixelWallpaper(int color, float density) {
        super(color);
        paint = createPaint(density, CodexPixelPalette.TEXTURE_DOTS,
                perceived(color) < 128 ? 0xB02C3D52 : 0x8CB7C9DA,
                perceived(color) < 128 ? 0x88243446 : 0x73C5D4E3);
    }

    /** 新入口按明确样式提供纸面底和纹理，缺失样式安全回到浅色出版。 */
    public CodexPixelWallpaper(CodexPixelPalette.Style style, float density) {
        super(style == null ? CodexPixelPalette.metadata(CodexPixelPalette.STYLE_EDITORIAL).wallpaper : style.wallpaper);
        CodexPixelPalette.Style resolved = style == null ? CodexPixelPalette.metadata(CodexPixelPalette.STYLE_EDITORIAL) : style;
        paint = createPaint(density, resolved.textureKind, resolved.textureColor, resolved.textureSecondaryColor);
    }

    /** 构造阶段才生成纹理，纯纸面不分配 bitmap；每个 Drawable 独立拥有 Paint 和 Shader。 */
    private static Paint createPaint(float density, int kind, int primary, int secondary) {
        if (kind == CodexPixelPalette.TEXTURE_NONE) {
            return null;
        }
        float stepDp = kind == CodexPixelPalette.TEXTURE_DOTS ? 16f : 32f;
        int tileSize = Math.max(1, Math.min(MAX_TILE_SIZE, (int) Math.ceil(stepDp * normalizeDensity(density))));
        Bitmap bitmap = tile(kind, tileSize, primary, secondary);
        Paint paint = new Paint();
        paint.setShader(new BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        return paint;
    }

    /** 异常密度回到 1，有限正值限制在 0.25–8；贴图尺寸另有 128px 上限。 */
    private static float normalizeDensity(float density) {
        if (Float.isNaN(density) || Float.isInfinite(density) || density <= 0f) {
            return 1f;
        }
        return Math.max(0.25f, Math.min(8f, density));
    }

    /** 主题线程和 UI 线程共用两项缓存；完整命中时不新建 bitmap，不回收仍被引用的像素。 */
    private static synchronized Bitmap tile(int kind, int size, int primary, int secondary) {
        if (recentTile != null && recentTile.matches(kind, size, primary, secondary)) {
            return recentTile.bitmap;
        }
        if (olderTile != null && olderTile.matches(kind, size, primary, secondary)) {
            Tile previous = recentTile;
            recentTile = olderTile;
            olderTile = previous;
            return recentTile.bitmap;
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint texture = new Paint();
        float half = size / 2f;
        float unit = size / (kind == CodexPixelPalette.TEXTURE_DOTS ? 16f : 32f);
        texture.setColor(primary);
        if (kind == CodexPixelPalette.TEXTURE_DOTS) {
            float dot = 0.75f * unit;
            canvas.drawRect(0f, 0f, dot, dot, texture);
            texture.setColor(secondary);
            canvas.drawRect(half, half, half + dot, half + dot, texture);
        } else {
            float width = Math.max(0.5f, 0.45f * unit);
            canvas.drawRect(0f, 0f, size, width, texture);
            if (kind == CodexPixelPalette.TEXTURE_GRID) {
                canvas.drawRect(0f, 0f, width, size, texture);
            }
            texture.setColor(secondary);
            canvas.drawRect(0f, half, size, half + width, texture);
            if (kind == CodexPixelPalette.TEXTURE_GRID) {
                canvas.drawRect(half, 0f, half + width, size, texture);
            }
        }
        olderTile = recentTile;
        recentTile = new Tile(kind, size, primary, secondary, bitmap);
        return bitmap;
    }

    /** 先画原底色，再重复静态纹理；不创建 bitmap、Paint、路径或临时数组。 */
    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (paint != null) {
            paint.setAlpha(getAlpha());
            canvas.drawRect(getBounds(), paint);
        }
    }

    /** 兼容旧入口的原明暗方点决策，不选择样式 ID。 */
    private static int perceived(int color) {
        return (((color >> 16) & 255) * 30 + ((color >> 8) & 255) * 59 + (color & 255) * 11) / 100;
    }
}
