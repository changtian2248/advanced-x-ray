package com.advancedxray.client;

import com.advancedxray.AdvancedXRay;
import com.advancedxray.client.config.XRayConfig;
import com.advancedxray.client.gui.XRayConfigScreen;
import com.advancedxray.client.render.OverloadHud;
import com.advancedxray.client.render.XRayOutlineRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口。
 *
 * <p>本模组是<b>纯客户端</b>模组（fabric.mod.json 里 {@code "environment": "client"}），
 * 因此只有 {@link ClientModInitializer}，没有 {@code ModInitializer}（服务端入口）。</p>
 *
 * <p>这里做三件事：</p>
 * <ol>
 *     <li>加载配置文件 {@link XRayConfig}</li>
 *     <li>注册快捷键 X → 打开 {@link XRayConfigScreen} 配置界面</li>
 *     <li>注册世界渲染事件 → {@link XRayOutlineRenderer} 绘制白色方块轮廓</li>
 * </ol>
 */
public class AdvancedXRayClient implements ClientModInitializer {

    /** 按键分类的翻译键（见 assets/advanced-x-ray/lang/*.json）。 */
    public static final String KEY_CATEGORY = "key.categories.advanced-x-ray";

    /** 打开配置界面的按键翻译键。 */
    public static final String KEY_OPEN_CONFIG = "key.advanced-x-ray.open_config";

    /** 默认按键：X。 */
    private static final int DEFAULT_KEY_CODE = GLFW.GLFW_KEY_X;

    private static KeyBinding openConfigKey;

    @Override
    public void onInitializeClient() {
        // 1) 读取配置（内部自带异常保护，配置损坏时会回落默认值）
        XRayConfig.load();
        AdvancedXRay.LOGGER.info("[Advanced X-Ray] 客户端初始化完成，配置已加载。");

        // 2) 注册快捷键：X
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                KEY_OPEN_CONFIG,
                InputUtil.Type.KEYSYM,
                DEFAULT_KEY_CODE,
                KEY_CATEGORY
        ));

        // 3) 每客户端 tick 检查按键；注意原版在「有界面打开时」不会派发按键绑定事件，
        //    所以这里天然只在没有其它界面时触发。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // 让渲染器推进自己的扫描节流逻辑（只在客户端主线程执行，无需加锁）
            XRayOutlineRenderer.getInstance().tick(client);

            // v1.3：处理「过载自动关闭」的提示（必须在主线程发聊天消息）
            XRayOutlineRenderer.getInstance().pollOverloadNotice(client);

            while (openConfigKey.wasPressed()) {
                client.setScreen(new XRayConfigScreen(client.currentScreen));
            }
        });

        // 4) 注册世界渲染事件（Sodium 兼容的关键：用公开的 WorldRenderEvents，不碰区块渲染管线）
        XRayOutlineRenderer.register();

        // 4.5) v1.3：过载提示 HUD（纯客户端绘制，不碰渲染管线）
        HudRenderCallback.EVENT.register((drawContext, tickCounter) ->
                OverloadHud.render(drawContext, tickCounter.getTickDelta(false)));

        // 5) 断开连接时清空缓存，避免切换世界/服务器后残留旧坐标
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                XRayOutlineRenderer.getInstance().clear());
    }

    /** 供其它类获取按键绑定（当前 GUI 未使用，留给后续做按键提示用）。 */
    public static KeyBinding getOpenConfigKey() {
        return openConfigKey;
    }

    /** 便捷方法：在客户端主线程打开配置界面。 */
    public static void openConfigScreen(MinecraftClient client) {
        client.setScreen(new XRayConfigScreen(client.currentScreen));
    }
}
