package com.mclauncher.ui.component;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

/** 把 64×64 皮肤贴图拼成一张「正面站立」的全身像 */
public final class SkinRenderer {

    private SkinRenderer() {}

    /** 一边手臂的宽度：经典（Steve）4px，纤细（Alex）3px */
    private static final int ARM_CLASSIC = 4;
    private static final int ARM_SLIM = 3;

    /** 渲染全身像 */
    public static Image fullBody(Image skin, int px, boolean slim) {
        if (skin == null || skin.getPixelReader() == null || px < 1) {
            return null;
        }
        int sw = (int) skin.getWidth();
        int sh = (int) skin.getHeight();
        if (sw < 64 || sh < 32) {
            return null;
        }
        boolean hasOverlay = sh >= 64;
        int armW = slim ? ARM_SLIM : ARM_CLASSIC;

        PixelReader r = skin.getPixelReader();

        // 画布：左臂 + 身体 + 右臂 = armW + 8 + armW 宽，头 8 + 身体 12 + 腿 12 = 32 高
        int canvasW = armW * 2 + 8;
        int canvasH = 32;
        int[] buf = new int[canvasW * canvasH];

        int bodyX = armW;          // 身体左边缘
        int headY = 0;
        // 头部居中：头 8 宽放在身体 8 宽上，正好对齐
        int headX = bodyX;

        // ---- 头 ----
        blit(buf, canvasW, r, hasOverlay, headX, headY, 8, 8, 8, 8, 40, 8);
        // ---- 身体 ----
        blit(buf, canvasW, r, hasOverlay, bodyX, 8, 8, 12, 20, 20, 20, 36);
        // ---- 右臂：贴在身体右侧 ----
        blit(buf, canvasW, r, hasOverlay, bodyX + 8, 8, armW, 12, 44, 20, 44, 36);
        // ---- 左臂：贴在身体左侧 ----
        blit(buf, canvasW, r, hasOverlay, 0, 8, armW, 12, 36, 52, 52, 52);
        // ---- 腿 ----
        blit(buf, canvasW, r, hasOverlay, bodyX, 20, 4, 12, 4, 20, 4, 36);
        blit(buf, canvasW, r, hasOverlay, bodyX + 4, 20, 4, 12, 20, 52, 20, 52);

        int outW = canvasW * px;
        int outH = canvasH * px;
        int[] out = new int[outW * outH];
        for (int y = 0; y < canvasH; y++) {
            for (int x = 0; x < canvasW; x++) {
                int argb = buf[y * canvasW + x];
                for (int dy = 0; dy < px; dy++) {
                    int row = (y * px + dy) * outW;
                    for (int dx = 0; dx < px; dx++) {
                        out[row + x * px + dx] = argb;
                    }
                }
            }
        }

        WritableImage img = new WritableImage(outW, outH);
        img.getPixelWriter().setPixels(0, 0, outW, outH,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), out, 0, outW);
        return img;
    }

    /**
     * 把贴图上的一个面画到画布上
     * 否则会出现「脖子/手臂缺一块」的破洞。
     */
    private static void blit(int[] buf, int canvasW, PixelReader r, boolean hasOverlay,
                             int ox, int oy, int w, int h,
                             int sx, int sy, int osx, int osy) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = r.getArgb(sx + x, sy + y);

                if (hasOverlay) {
                    // 内层透明时用外层的同位置像素补上（外层有内容的情况下）
                    if (alphaOf(argb) == 0) {
                        int o = r.getArgb(osx + x, osy + y);
                        if (alphaOf(o) > 0) {
                            argb = o;
                        }
                    } else {
                        // 内层不透明：外层若非全透明则叠加上去（半透明外层的颜色混合）
                        int o = r.getArgb(osx + x, osy + y);
                        int oa = alphaOf(o);
                        if (oa >= 255) {
                            argb = o;
                        } else if (oa > 0) {
                            argb = blend(argb, o, oa);
                        }
                    }
                }

                int cx = ox + x;
                int cy = oy + y;
                if (cx >= 0 && cx < canvasW && cy >= 0) {
                    buf[cy * canvasW + cx] = argb;
                }
            }
        }
    }

    private static int alphaOf(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** 把颜色 o（不透明度 a/255）叠到背景 c 上 */
    private static int blend(int c, int o, int a) {
        double t = a / 255.0;
        int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF, ca = alphaOf(c);
        int or = (o >> 16) & 0xFF, og = (o >> 8) & 0xFF, ob = o & 0xFF;
        int rr = (int) Math.round(or * t + cr * (1 - t));
        int rg = (int) Math.round(og * t + cg * (1 - t));
        int rb = (int) Math.round(ob * t + cb * (1 - t));
        int ra = Math.max(ca, a);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
}