package com.advancedxray.client.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 过载提示 HUD（v1.3）。
 *
 * <p>当透视因为方块过多被自动关闭时，在屏幕中央显示 3 秒醒目提示，
 * 让玩家知道「不是游戏崩了，而是模组主动关掉了透视」。</p>
 *
 * <p>实现方式：纯客户端静态状态 + 每帧绘制，不注册任何事件、不碰渲染管线，
 * 因此与 Sodium 完全无冲突。</p>
 */
public final class OverloadHud {

    /** 提示显示时长（毫秒）。 */
    private static final long DURATION_MS = 3000L;

    private static Text message;
    private static long shownAt;

    private OverloadHud() {
    }

    /** 设置一条提示（由客户端 tick 调用）。 */
    public static void set(Text text) {
        message = text;
        shownAt = System.currentTimeMillis();
    }

    /** 是否正在显示提示。 */
    public static boolean isVisible() {
        return message != null && System.currentTimeMillis() - shownAt < DURATION_MS;
    }

    /**
     * 每帧绘制（由 {@code HudRenderCallback} 调用）。
     *
     * <p>用 {@link DrawContext} 的公开 API 画半透明底板 + 居中文字，
     * 不直接调用任何 GL 状态，安全且兼容 Sodium。</p>
     */
    public static void render(DrawContext context, float tickDelta) {
        if (!isVisible()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return;
        }

        long elapsed = System.currentTimeMillis() - shownAt;
        // 最后 500ms 淡出
        float alpha = elapsed > DURATION_MS - 500L
                ? (DURATION_MS - elapsed) / 500.0F
                : 1.0F;
        alpha = Math.max(0.0F, Math.min(1.0F, alpha));

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();

        int textWidth = client.textRenderer.getWidth(message);
        int boxWidth = textWidth + 24;
        int boxHeight = 24;
        int boxX = (screenWidth - boxWidth) / 2;
        int boxY = screenHeight / 2 - 60;

        int alphaByte = (int) (alpha * 0xC0) << 24;
        context.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, alphaByte | 0x101010);
        context.fill(boxX, boxY, boxX + boxWidth, boxY + 1, alphaByte | 0xFF5555);
        context.fill(boxX, boxY + boxHeight - 1, boxX + boxWidth, boxY + boxHeight, alphaByte | 0xFF5555);

        int textColor = ((int) (alpha * 255) << 24) | 0xFF5555;
        context.drawCenteredTextWithShadow(client.textRenderer, message,
                screenWidth / 2, boxY + (boxHeight - 8) / 2, textColor);
    }
}
