package com.mclauncher.ui.component;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

/**
 * 把 64×64 皮肤贴图拼成一张「正面站立」的全身像
 *
 * Minecraft 的皮肤图是人物的**展开图**：头/身体/手臂/腿分四块摊在图上，
 * 直接显示整张图玩家看不懂。这里按标准布局把各块的面拼出来：
 *
 * <pre>
 *   头   (8,8)  8×8         头外层   (40,8)   8×8    ← 发型/帽子
 *   身体 (20,20) 8×12        身体外层 (20,36) 8×12
 *   右臂 (44,20) 4×12（经典）/ 3×12（纤细）  右侧外层 (44,36)
 *   左臂 (36,52) 4×12        左侧外层 (52,52)
 *   右腿 (4,20)  4×12        腿外层   (4,36)
 *   左腿 (20,52) 4×12
 * </pre>
 *
 * <b>透明像素</b>：外层（第二层）大部分是透明的，不画就会露出脖子、手臂的缺口。
 * 遇到透明像素时补上「内层同位置的像素」，让轮廓完整。
 *
 * <b>老版本皮肤</b>：64×32 的皮肤没有外层，也没有独立的左臂/左腿 —— 左右镜像即可。
 */
public final class SkinRenderer {

    private SkinRenderer() {}

    /** 一边手臂的宽度：经典（Steve）4px，纤细（Alex）3px */
    private static final int ARM_CLASSIC = 4;
    private static final int ARM_SLIM = 3;

    /**
     * 渲染全身像
     *
     * @param skin 64×64（或老版 64×32）皮肤
     * @param px   每个皮肤像素放大成多少屏幕像素（建议 6~10）
     * @param slim 是否纤细模型：影响手臂宽度
     * @return 全身像；皮肤尺寸不对时返回 null
     */
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
     *
     * 先画内层，再把外层（第二层）叠上去；外层该位置透明就保留内层，
     * 否则会出现「脖子/手臂缺一块」的破洞。
     *
     * @param ox,oy,w,h   画布上的目标区域
     * @param sx,sy       内层源区域左上角
     * @param osx,osy     外层源区域左上角（没有外层时忽略）
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