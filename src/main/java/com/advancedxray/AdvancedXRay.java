package com.advancedxray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advanced X-Ray 的常量与日志工具类。
 *
 * <p><b>注意：这个类不是模组入口。</b>本模组是纯客户端模组，唯一的入口是
 * {@link com.advancedxray.client.AdvancedXRayClient}（在 fabric.mod.json 的
 * {@code entrypoints.client} 中注册）。这里只存放全局常量和 Logger，任何类都可以安全地引用它。</p>
 */
public final class AdvancedXRay {

    /** 模组 ID，必须与 fabric.mod.json 中的 id 完全一致（也叫资源命名空间）。 */
    public static final String MOD_ID = "advanced-x-ray";

    /** 模组显示名。 */
    public static final String MOD_NAME = "Advanced X-Ray";

    /** 全局日志器。 */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    /**
     * 生成一个属于本模组的资源 {@link net.minecraft.util.Identifier}。
     *
     * @param path 资源路径，例如 {@code "lang/zh_cn.json"} 或 {@code "icon.png"}
     */
    public static net.minecraft.util.Identifier id(String path) {
        return net.minecraft.util.Identifier.of(MOD_ID, path);
    }

    private AdvancedXRay() {
        // 工具类，禁止实例化
    }
}
