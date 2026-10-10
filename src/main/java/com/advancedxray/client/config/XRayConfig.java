package com.advancedxray.client.config;

import com.advancedxray.AdvancedXRay;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置持久化类（纯客户端）—— v1.2。
 *
 * <p>配置文件：{@code .minecraft/config/advanced-x-ray.json}</p>
 *
 * <h2>v1.2 变化</h2>
 * <ul>
 *     <li><b>默认不再高亮任何方块</b>（{@code DEFAULT_BLOCKS} 为空），一切由玩家自己搜、自己加</li>
 *     <li>新增 {@code skipInnerFaces}：相邻面剔除开关，<b>默认开启</b>。
 *         开启后，两个紧挨着的高亮方块之间那条共享棱不会再画，
 *         一整片矿脉会显示为干净的"线框外壳"，顶点数下降 60~80%，帧率明显提升。</li>
 *     <li>渲染距离默认仍为 48 格（滑条可调 8~128）</li>
 * </ul>
 */
public final class XRayConfig {

    private static final String CONFIG_FILE = "advanced-x-ray.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static final int MIN_RENDER_DISTANCE = 8;
    public static final int MAX_RENDER_DISTANCE = 128;

    private static final int DEFAULT_RENDER_DISTANCE = 48;

    public static final int DEFAULT_COLOR = 0xFFFFFFFF;

    /** 边框调色板（ARGB）。新添加的方块会依次分配未使用的颜色。 */
    public static final int[] PALETTE = {
            0xFFFFFFFF, // 白
            0xFFFF5555, // 红
            0xFFFFAA00, // 橙
            0xFFFFFF55, // 黄
            0xFF55FF55, // 绿
            0xFF55FFFF, // 青
            0xFF5555FF, // 蓝
            0xFFFF55FF, // 紫
            0xFF00FFAA, // 薄荷
            0xFFFF8800, // 琥珀
            0xFFAA55FF, // 淡紫
            0xFFBBBBBB  // 灰
    };

    /**
     * v1.2 起默认为空 —— 首次安装不会透视任何方块。
     * 玩家需要在 GUI 里自己搜索并添加。
     */
    private static final List<String> DEFAULT_BLOCKS = List.of();

    private static XRayConfig instance = new XRayConfig();

    // ------------------------------------------------------------------
    // 会被 Gson 序列化到磁盘的字段
    // ------------------------------------------------------------------

    private boolean enabled = true;

    /** 渲染距离（格）。同时决定扫描范围。 */
    private int renderDistance = DEFAULT_RENDER_DISTANCE;

    /** 相邻面剔除。true（默认）= 只画矿脉外壳；false = 每个方块都画完整 12 条棱。 */
    private boolean skipInnerFaces = true;

    /**
     * 过载自动关闭保护（v1.3 新增，默认开启）。
     *
     * <p>当单帧需要绘制的方块数连续多帧超过上限时，说明当前设置已经超出设备承受能力，
     * 此时会自动把透视总开关关掉并给出提示，避免玩家陷入「卡死 → 只能强退重开」的窘境。
     * 关掉后随时可以按 X 重新开启。</p>
     */
    private boolean overloadProtection = true;

    /** 高亮方块 → 边框颜色（ARGB），LinkedHashMap 保序。 */
    private Map<String, Integer> blockColors = new LinkedHashMap<>();

    /** v1.0/v1.1 遗留字段，仅用于迁移老配置；迁移后置 null（Gson 默认跳过 null）。 */
    private List<String> highlightedBlocks;

    // ------------------------------------------------------------------
    // 运行期缓存（transient）
    // ------------------------------------------------------------------

    private transient Set<Block> blockCache = new HashSet<>();
    private transient Object2IntMap<Block> colorCache = new Object2IntOpenHashMap<>();
    private transient int revision = 0;

    private XRayConfig() {
        for (String id : DEFAULT_BLOCKS) {
            blockColors.put(id, 0);
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    public static XRayConfig get() {
        return instance;
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
    }

    public static void load() {
        Path path = configPath();
        XRayConfig loaded = null;

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, XRayConfig.class);
            } catch (Exception e) {
                AdvancedXRay.LOGGER.error("[Advanced X-Ray] 读取配置文件失败，将使用默认配置：{}", path, e);
            }
        }

        if (loaded == null) {
            loaded = new XRayConfig();
        }

        // Gson 反序列化不走构造函数，transient 字段必须手动初始化
        if (loaded.blockCache == null) {
            loaded.blockCache = new HashSet<>();
        }
        if (loaded.colorCache == null) {
            loaded.colorCache = new Object2IntOpenHashMap<>();
        }
        if (loaded.blockColors == null) {
            loaded.blockColors = new LinkedHashMap<>();
        }

        // ---- 迁移 v1.0/v1.1 的 highlightedBlocks ----
        if (loaded.highlightedBlocks != null) {
            for (String id : loaded.highlightedBlocks) {
                if (id != null) {
                    loaded.blockColors.putIfAbsent(id, 0);
                }
            }
            loaded.highlightedBlocks = null;
        }

        // ---- 清洗：丢弃无效 ID、补齐缺失 / 非法颜色 ----
        Set<Integer> usedColors = new HashSet<>();
        for (Integer color : loaded.blockColors.values()) {
            if (color != null && color != 0) {
                usedColors.add(color | 0xFF000000);
            }
        }

        Map<String, Integer> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : loaded.blockColors.entrySet()) {
            Block block = resolveBlock(entry.getKey());
            if (block == null) {
                AdvancedXRay.LOGGER.warn("[Advanced X-Ray] 配置中忽略了未知方块：{}", entry.getKey());
                continue;
            }
            Integer raw = entry.getValue();
            int color = (raw == null || raw == 0) ? nextPaletteColor(usedColors) : (raw | 0xFF000000);
            usedColors.add(color);
            cleaned.put(Registries.BLOCK.getId(block).toString(), color);
        }
        loaded.blockColors = cleaned;

        loaded.renderDistance = clamp(loaded.renderDistance <= 0 ? DEFAULT_RENDER_DISTANCE : loaded.renderDistance,
                MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);

        loaded.rebuildCache();
        instance = loaded;
        loaded.save();
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            AdvancedXRay.LOGGER.error("[Advanced X-Ray] 保存配置文件失败：{}", path, e);
        }
    }

    // ==================================================================
    // 总开关
    // ==================================================================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        if (this.enabled == value) {
            return;
        }
        this.enabled = value;
        this.revision++;
        save();
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    // ==================================================================
    // 相邻面剔除开关
    // ==================================================================

    public boolean isSkipInnerFaces() {
        return skipInnerFaces;
    }

    public void setSkipInnerFaces(boolean value) {
        if (this.skipInnerFaces == value) {
            return;
        }
        this.skipInnerFaces = value;
        // 需要重建缓存里的 occupancy 集合
        this.revision++;
        save();
    }

    public void toggleSkipInnerFaces() {
        setSkipInnerFaces(!skipInnerFaces);
    }

    // ==================================================================
    // 过载自动关闭保护
    // ==================================================================

    public boolean isOverloadProtection() {
        return overloadProtection;
    }

    public void setOverloadProtection(boolean value) {
        if (this.overloadProtection == value) {
            return;
        }
        this.overloadProtection = value;
        save();
    }

    public void toggleOverloadProtection() {
        setOverloadProtection(!overloadProtection);
    }

    /**
     * 由渲染器在检测到过载时调用：强制关闭透视。
     *
     * <p>只改 {@code enabled}，方块列表与颜色全部保留，玩家随时可以再打开。</p>
     */
    public void forceDisableByOverload() {
        if (!this.enabled) {
            return;
        }
        this.enabled = false;
        this.revision++;
        save();
    }

    // ==================================================================
    // 渲染距离
    // ==================================================================

    public int getRenderDistance() {
        return renderDistance;
    }

    public void setRenderDistance(int value) {
        int clamped = clamp(value, MIN_RENDER_DISTANCE, MAX_RENDER_DISTANCE);
        if (this.renderDistance == clamped) {
            return;
        }
        this.renderDistance = clamped;
        // 故意不自增 revision：距离是「即时生效」的，
        // 若自增会清空全部区块缓存 → 拖动滑条时会一片空白。
        save();
    }

    /**
     * 扫描半径（区块）。
     *
     * <p>刻意比渲染距离<b>多预留 2 个区块（32 格）</b>：
     * 这样玩家走动时，「刚进入渲染距离」的方块其实早就被扫进缓存了，
     * 不会出现「移动中前方方块消失、停下才补上」的现象。</p>
     */
    public int getScanRadiusChunks() {
        return clamp((renderDistance + 32 + 15) / 16 + 1, 3, 14);
    }

    public int getRevision() {
        return revision;
    }

    // ==================================================================
    // 高亮方块列表
    // ==================================================================

    public List<String> getBlockIds() {
        return Collections.unmodifiableList(new ArrayList<>(blockColors.keySet()));
    }

    public List<Block> getBlocks() {
        List<Block> list = new ArrayList<>(blockColors.size());
        for (String id : blockColors.keySet()) {
            Block block = resolveBlock(id);
            if (block != null) {
                list.add(block);
            }
        }
        return list;
    }

    public int size() {
        return blockColors.size();
    }

    public boolean addBlock(Block block) {
        if (block == null || block == Blocks.AIR) {
            return false;
        }
        Identifier id = Registries.BLOCK.getId(block);
        if (id == null) {
            return false;
        }
        String key = id.toString();
        if (blockColors.containsKey(key)) {
            return false;
        }
        Set<Integer> used = new HashSet<>(blockColors.values());
        blockColors.put(key, nextPaletteColor(used));
        rebuildCache();
        save();
        return true;
    }

    public boolean removeBlock(Block block) {
        if (block == null) {
            return false;
        }
        Identifier id = Registries.BLOCK.getId(block);
        if (id == null) {
            return false;
        }
        if (blockColors.remove(id.toString()) == null) {
            return false;
        }
        rebuildCache();
        save();
        return true;
    }

    public void clearBlocks() {
        if (blockColors.isEmpty()) {
            return;
        }
        blockColors.clear();
        rebuildCache();
        save();
    }

    // ==================================================================
    // 颜色
    // ==================================================================

    public int getColor(Block block) {
        if (block == null) {
            return DEFAULT_COLOR;
        }
        int color = colorCache.getInt(block);
        return color == 0 ? DEFAULT_COLOR : color;
    }

    /** 把某方块的颜色切到调色板的下一格，返回新颜色。 */
    public int cycleColor(Block block) {
        if (block == null) {
            return DEFAULT_COLOR;
        }
        Identifier id = Registries.BLOCK.getId(block);
        if (id == null) {
            return DEFAULT_COLOR;
        }
        String key = id.toString();
        Integer current = blockColors.get(key);
        int index = 0;
        if (current != null) {
            for (int i = 0; i < PALETTE.length; i++) {
                if (PALETTE[i] == (current | 0xFF000000)) {
                    index = i;
                    break;
                }
            }
        }
        int next = PALETTE[(index + 1) % PALETTE.length];
        blockColors.put(key, next);
        rebuildCache();
        save();
        return next;
    }

    private static int nextPaletteColor(Set<Integer> used) {
        for (int color : PALETTE) {
            if (!used.contains(color)) {
                return color;
            }
        }
        return PALETTE[used.size() % PALETTE.length];
    }

    // ==================================================================
    // 判定 & 缓存
    // ==================================================================

    public boolean isHighlighted(Block block) {
        return block != null && blockCache.contains(block);
    }

    public boolean isHighlighted(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        return blockCache.contains(state.getBlock());
    }

    public void rebuildCache() {
        blockCache.clear();
        colorCache.clear();
        for (Map.Entry<String, Integer> entry : blockColors.entrySet()) {
            Block block = resolveBlock(entry.getKey());
            if (block == null) {
                continue;
            }
            blockCache.add(block);
            colorCache.put(block, entry.getValue() == null ? DEFAULT_COLOR : entry.getValue());
        }
        revision++;
    }

    public static Block resolveBlock(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        Identifier id;
        try {
            id = Identifier.tryParse(trimmed);
        } catch (Exception e) {
            return null;
        }
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return null;
        }
        Block block = Registries.BLOCK.get(id);
        if (block == null || block == Blocks.AIR) {
            return null;
        }
        return block;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
