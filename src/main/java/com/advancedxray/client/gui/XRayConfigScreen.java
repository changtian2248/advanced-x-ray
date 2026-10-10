package com.advancedxray.client.gui;

import com.advancedxray.client.config.XRayConfig;
import com.advancedxray.client.render.XRayOutlineRenderer;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * X-Ray 配置界面 —— v1.2。
 *
 * <h2>布局</h2>
 * <pre>
 *  ┌──────────────────────────── 标题 ────────────────────────────┐
 *  │  [透视高亮：开启] [合并相邻：开启] [清空列表]                    │
 *  │  [ 渲染距离：48 格 ━━━━●━━━━ ]                                │
 *  │  [ 搜索框：方块ID / 中文名 / 英文名 ..................... ]     │
 *  │  已扫描 1234 个方块          ┌── 已高亮方块 ─────────┐         │
 *  │  ┌── 搜索结果 ───────┐      │ ■ Granite         x │         │
 *  │  │ Granite            │      │   minecraft:granite │         │
 *  │  └────────────────────┘      └──────────────────────┘         │
 *  │                      [       完成       ]                    │
 *  └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>v1.2 新增</h2>
 * <ul>
 *     <li><b>渲染距离滑条</b>（8~128 格）：解决「全图透视太卡」，同时决定扫描范围。</li>
 *     <li><b>合并相邻开关</b>：开启后一整片矿脉只显示外轮廓线框，顶点数降 60~80%。</li>
 *     <li><b>左上角扫描统计</b>：显示当前缓存了多少方块；若某个区块被上限截断会给出橙色提示。</li>
 * </ul>
 */
public class XRayConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 20;
    private static final int MAX_RESULTS = 500;

    /** 颜色色块在行内的可点击区域。 */
    private static final int SWATCH_LEFT = 2;
    private static final int SWATCH_RIGHT = 15;

    private static List<BlockEntry> globalIndex;

    private final Screen parent;

    private TextFieldWidget searchField;
    private ButtonWidget toggleButton;
    private ButtonWidget mergeButton;
    private ButtonWidget overloadButton;

    private final List<Block> searchResults = new ArrayList<>();
    private final List<Block> highlighted = new ArrayList<>();

    private double searchScroll;
    private double highlightScroll;

    private Text statusMessage = Text.empty();
    private int statusColor = 0xFFFFFF;
    private int statusTicks;

    private int listTop;
    private int listHeight;
    private int leftX;
    private int leftWidth;
    private int rightX;
    private int rightWidth;

    public XRayConfigScreen(Screen parent) {
        super(Text.translatable("gui.advanced-x-ray.title"));
        this.parent = parent;
    }

    // ==================================================================
    // 初始化
    // ==================================================================

    @Override
    protected void init() {
        super.init();

        ensureIndex();
        refreshHighlighted();

        XRayConfig config = XRayConfig.get();
        int centerX = this.width / 2;

        // ---- 第一栏：总开关 / 合并相邻 / 清空 ----
        this.toggleButton = ButtonWidget.builder(toggleText(), button -> {
            config.toggle();
            updateToggleMessage();
            setStatus(Text.translatable(config.isEnabled()
                    ? "gui.advanced-x-ray.hint.enabled"
                    : "gui.advanced-x-ray.hint.disabled"), config.isEnabled() ? 0x55FF55 : 0xFFAA55);
        }).dimensions(centerX - 250, 28, 118, 20).build();
        addDrawableChild(this.toggleButton);

        this.mergeButton = ButtonWidget.builder(mergeText(), button -> {
            config.toggleSkipInnerFaces();
            updateMergeMessage();
            refreshHighlighted();
            setStatus(Text.translatable(config.isSkipInnerFaces()
                    ? "gui.advanced-x-ray.hint.merge.on"
                    : "gui.advanced-x-ray.hint.merge.off"), 0x88CCFF);
        }).dimensions(centerX - 128, 28, 112, 20).build();
        addDrawableChild(this.mergeButton);

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.advanced-x-ray.clear"), button -> {
            config.clearBlocks();
            refreshHighlighted();
            setStatus(Text.translatable("gui.advanced-x-ray.hint.cleared"), 0xFFAA55);
        }).dimensions(centerX - 12, 28, 84, 20).build());

        // ---- v1.3：过载保护开关 ----
        this.overloadButton = ButtonWidget.builder(overloadText(), button -> {
            config.toggleOverloadProtection();
            updateOverloadMessage();
            setStatus(Text.translatable(config.isOverloadProtection()
                    ? "gui.advanced-x-ray.hint.overload.on"
                    : "gui.advanced-x-ray.hint.overload.off"), 0x88CCFF);
        }).dimensions(centerX + 76, 28, 112, 20).build();
        addDrawableChild(this.overloadButton);

        // ---- 第二栏：渲染距离滑条 ----
        addDrawableChild(new DistanceSlider(centerX - 108, 50, 216, 20, config.getRenderDistance()));

        // ---- 第三栏：搜索框 ----
        this.searchField = new TextFieldWidget(this.textRenderer, centerX - 108, 72, 216, 18,
                Text.translatable("gui.advanced-x-ray.search_result"));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(Text.translatable("gui.advanced-x-ray.search_hint"));
        this.searchField.setChangedListener(this::onSearchChanged);
        addDrawableChild(this.searchField);

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(centerX - 60, this.height - 26, 120, 20).build());

        // ---- 双栏列表几何 ----
        this.listTop = 98;
        int listBottom = this.height - 32;
        this.listHeight = Math.max(ROW_HEIGHT * 3, listBottom - this.listTop);

        int totalWidth = Math.min(this.width - 24, 560);
        int gap = 10;
        int columnWidth = (totalWidth - gap) / 2;
        this.leftX = centerX - totalWidth / 2;
        this.leftWidth = columnWidth;
        this.rightX = this.leftX + columnWidth + gap;
        this.rightWidth = columnWidth;

        onSearchChanged(this.searchField.getText());
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        String text = this.searchField != null ? this.searchField.getText() : "";
        super.resize(client, width, height);
        if (this.searchField != null) {
            this.searchField.setText(text);
        }
    }

    /**
     * 渲染距离滑条。
     * <p>用原版 {@link SliderWidget}（构造签名 {@code (int,int,int,int,Text,double)} 已核验），
     * 值域映射到 {@link XRayConfig#MIN_RENDER_DISTANCE} ~ {@link XRayConfig#MAX_RENDER_DISTANCE}。</p>
     */
    private static final class DistanceSlider extends SliderWidget {
        private static final int MIN = XRayConfig.MIN_RENDER_DISTANCE;
        private static final int MAX = XRayConfig.MAX_RENDER_DISTANCE;

        DistanceSlider(int x, int y, int width, int height, int distance) {
            super(x, y, width, height, Text.empty(), (distance - MIN) / (double) (MAX - MIN));
            updateMessage();
        }

        private int currentDistance() {
            return MIN + (int) Math.round(this.value * (MAX - MIN));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.translatable("gui.advanced-x-ray.distance", currentDistance()));
        }

        @Override
        protected void applyValue() {
            XRayConfig.get().setRenderDistance(currentDistance());
        }
    }

    // ==================================================================
    // 方块索引 & 搜索
    // ==================================================================

    private static void ensureIndex() {
        if (globalIndex != null) {
            return;
        }
        List<BlockEntry> entries = new ArrayList<>();
        for (Block block : Registries.BLOCK) {
            if (block == Blocks.AIR) {
                continue;
            }
            Identifier id = Registries.BLOCK.getId(block);
            if (id == null) {
                continue;
            }
            String fullId = id.toString();

            String localized;
            try {
                localized = block.getName().getString();
            } catch (Exception e) {
                localized = id.getPath();
            }

            StringBuilder haystack = new StringBuilder(64);
            haystack.append(fullId.toLowerCase(Locale.ROOT)).append(' ')
                    .append(id.getPath().toLowerCase(Locale.ROOT)).append(' ')
                    .append(localized.toLowerCase(Locale.ROOT)).append(' ');

            String alias = SEARCH_ALIASES.get(fullId);
            if (alias != null) {
                haystack.append(alias.toLowerCase(Locale.ROOT));
            }

            entries.add(new BlockEntry(block, haystack.toString()));
        }
        globalIndex = entries;
    }

    private void onSearchChanged(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        searchResults.clear();
        searchScroll = 0D;

        if (globalIndex == null) {
            return;
        }

        for (BlockEntry entry : globalIndex) {
            if (!query.isEmpty() && !entry.haystack.contains(query)) {
                continue;
            }
            searchResults.add(entry.block);
            if (searchResults.size() >= MAX_RESULTS) {
                break;
            }
        }
    }

    private void refreshHighlighted() {
        highlighted.clear();
        highlighted.addAll(XRayConfig.get().getBlocks());
        clampScroll();
    }

    private void clampScroll() {
        this.searchScroll = clamp(this.searchScroll, searchResults.size(), this.listHeight);
        this.highlightScroll = clamp(this.highlightScroll, highlighted.size(), this.listHeight);
    }

    private static double clamp(double scroll, int itemCount, int viewHeight) {
        double max = Math.max(0D, (double) itemCount * ROW_HEIGHT - viewHeight);
        return Math.max(0D, Math.min(max, scroll));
    }

    private void updateToggleMessage() {
        if (this.toggleButton != null) {
            this.toggleButton.setMessage(toggleText());
        }
    }

    private void updateMergeMessage() {
        if (this.mergeButton != null) {
            this.mergeButton.setMessage(mergeText());
        }
    }

    private void updateOverloadMessage() {
        if (this.overloadButton != null) {
            this.overloadButton.setMessage(overloadText());
        }
    }

    private static Text overloadText() {
        return Text.translatable(XRayConfig.get().isOverloadProtection()
                ? "gui.advanced-x-ray.overload.on"
                : "gui.advanced-x-ray.overload.off");
    }

    private static Text toggleText() {
        return Text.translatable(XRayConfig.get().isEnabled()
                ? "gui.advanced-x-ray.toggle.on"
                : "gui.advanced-x-ray.toggle.off");
    }

    private static Text mergeText() {
        return Text.translatable(XRayConfig.get().isSkipInnerFaces()
                ? "gui.advanced-x-ray.merge.on"
                : "gui.advanced-x-ray.merge.off");
    }

    private void setStatus(Text message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
        this.statusTicks = 100;
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);

        if (this.statusTicks > 0) {
            this.statusTicks--;
            context.drawCenteredTextWithShadow(this.textRenderer, this.statusMessage,
                    this.width / 2, 90, this.statusColor);
        }

        // ---- 左上角：扫描统计 + 上限警告 ----
        XRayOutlineRenderer renderer = XRayOutlineRenderer.getInstance();
        int scanned = renderer.getScannedBlockCount();
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("gui.advanced-x-ray.scan_info", scanned), 6, 6, 0xFF909090);

        int truncated = renderer.getTruncatedChunkCount();
        if (truncated > 0) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("gui.advanced-x-ray.truncated", truncated), 6, 18, 0xFFFF8800);
        }

        Text leftTitle = Text.translatable("gui.advanced-x-ray.search_result");
        Text rightTitle = Text.translatable("gui.advanced-x-ray.highlight_list")
                .copy().append(Text.literal(" (" + highlighted.size() + ")"));

        drawList(context, leftX, listTop, leftWidth, listHeight, searchResults, searchScroll,
                mouseX, mouseY, true, leftTitle, Text.translatable("gui.advanced-x-ray.empty_result"));
        drawList(context, rightX, listTop, rightWidth, listHeight, highlighted, highlightScroll,
                mouseX, mouseY, false, rightTitle, Text.translatable("gui.advanced-x-ray.empty_highlight"));
    }

    private void drawList(DrawContext context, int x, int y, int width, int height,
                          List<Block> list, double scroll, int mouseX, int mouseY,
                          boolean addMode, Text header, Text emptyText) {

        XRayConfig config = XRayConfig.get();

        context.fill(x, y, x + width, y + height, 0x80000000);
        context.fill(x, y, x + width, y + 1, 0xFF6A6A6A);
        context.fill(x, y + height - 1, x + width, y + height, 0xFF6A6A6A);

        context.drawTextWithShadow(this.textRenderer, header, x + 2, y - 12, 0xFFD0D0D0);

        context.enableScissor(x, y + 1, x + width, y + height - 1);

        if (list.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, emptyText,
                    x + width / 2, y + height / 2 - 4, 0xFF909090);
        } else {
            int firstIndex = (int) (scroll / ROW_HEIGHT);
            int lastIndex = Math.min(list.size(), firstIndex + (height / ROW_HEIGHT) + 2);

            for (int index = firstIndex; index < lastIndex; index++) {
                int rowY = y + 1 + index * ROW_HEIGHT - (int) scroll;
                if (rowY + ROW_HEIGHT < y || rowY > y + height) {
                    continue;
                }

                Block block = list.get(index);
                boolean hovered = mouseX >= x && mouseX < x + width
                        && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

                context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT - 1,
                        hovered ? 0x50FFFFFF : ((index % 2 == 0) ? 0x18FFFFFF : 0x00000000));

                // -------- 颜色色块 --------
                boolean hasColor = config.isHighlighted(block);
                int swatchX = x + SWATCH_LEFT;
                int swatchY = rowY + (ROW_HEIGHT - 9) / 2;
                if (hasColor) {
                    boolean onSwatch = mouseX >= swatchX && mouseX < x + SWATCH_RIGHT
                            && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                    context.fill(swatchX - 1, swatchY - 1, x + SWATCH_RIGHT, swatchY + 10, 0xFF202020);
                    context.fill(swatchX, swatchY, x + SWATCH_RIGHT - 1, swatchY + 9,
                            config.getColor(block) | 0xFF000000);
                    if (onSwatch) {
                        context.fill(swatchX - 1, swatchY - 1, x + SWATCH_RIGHT, swatchY, 0xFFFFFFFF);
                        context.fill(swatchX - 1, swatchY + 9, x + SWATCH_RIGHT, swatchY + 10, 0xFFFFFFFF);
                    }
                }

                int textX = x + (addMode ? 5 : SWATCH_RIGHT + 4);
                String name = block.getName().getString();

                String id;
                try {
                    Identifier identifier = Registries.BLOCK.getId(block);
                    id = identifier == null ? "?" : identifier.toString();
                } catch (Exception e) {
                    id = "?";
                }

                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(name, width - (textX - x) - 14),
                        textX, rowY + 3, 0xFFFFFFFF);
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(id, width - (textX - x) - 14),
                        textX, rowY + 12, 0xFF9A9A9A);

                String mark = addMode ? "+" : "x";
                int markColor = addMode
                        ? (hasColor ? 0xFF808080 : 0xFF55FF55)
                        : 0xFFFF5555;
                context.drawTextWithShadow(this.textRenderer, mark, x + width - 11, rowY + 6, markColor);
            }
        }

        context.disableScissor();

        double total = (double) list.size() * ROW_HEIGHT;
        if (total > height) {
            int barHeight = Math.max(16, (int) (height * (height / total)));
            int barY = y + (int) ((height - barHeight) * (scroll / (total - height)));
            context.fill(x + width - 3, barY, x + width - 1, barY + barHeight, 0xAAFFFFFF);
        }
    }

    // ==================================================================
    // 输入处理
    // ==================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (handleListClick(searchResults, leftX, listTop, leftWidth, listHeight, searchScroll,
                    mouseX, mouseY, true)) {
                return true;
            }
            if (handleListClick(highlighted, rightX, listTop, rightWidth, listHeight, highlightScroll,
                    mouseX, mouseY, false)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleListClick(List<Block> list, int x, int y, int width, int height,
                                    double scroll, double mouseX, double mouseY, boolean addMode) {
        if (mouseX < x || mouseX >= x + width || mouseY < y + 1 || mouseY >= y + height - 1) {
            return false;
        }
        if (list.isEmpty()) {
            return false;
        }

        int index = (int) ((mouseY - y - 1 + scroll) / ROW_HEIGHT);
        if (index < 0 || index >= list.size()) {
            return false;
        }

        Block block = list.get(index);
        XRayConfig config = XRayConfig.get();
        int rowY = y + 1 + index * ROW_HEIGHT - (int) scroll;

        // ---- 点色块 = 切换颜色（只对已高亮的方块生效）----
        boolean swatchClickable = !addMode || config.isHighlighted(block);
        if (swatchClickable && mouseX >= x + SWATCH_LEFT && mouseX < x + SWATCH_RIGHT
                && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
            int color = config.cycleColor(block);
            setStatus(Text.translatable("gui.advanced-x-ray.hint.color", block.getName()), color);
            return true;
        }

        if (addMode) {
            if (config.addBlock(block)) {
                setStatus(Text.translatable("gui.advanced-x-ray.hint.added", block.getName()), 0x55FF55);
            } else {
                setStatus(Text.translatable("gui.advanced-x-ray.already_added"), 0xFFAA55);
            }
            refreshHighlighted();
        } else {
            config.removeBlock(block);
            setStatus(Text.translatable("gui.advanced-x-ray.hint.removed", block.getName()), 0xFF8888);
            refreshHighlighted();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (inRect(mouseX, mouseY, leftX, listTop, leftWidth, listHeight)) {
            searchScroll = clamp(searchScroll - verticalAmount * ROW_HEIGHT, searchResults.size(), listHeight);
            return true;
        }
        if (inRect(mouseX, mouseY, rightX, listTop, rightWidth, listHeight)) {
            highlightScroll = clamp(highlightScroll - verticalAmount * ROW_HEIGHT, highlighted.size(), listHeight);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public void close() {
        XRayConfig.get().save();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    /** 返回 false：打开界面时世界继续运行，方便实时观察透视效果。 */
    @Override
    public boolean shouldPause() {
        return false;
    }

    // ==================================================================
    // 内部数据结构
    // ==================================================================

    private record BlockEntry(Block block, String haystack) {
    }

    /** 常用方块的中英关键词别名（补足「英文客户端用中文搜」的场景）。 */
    private static final Map<String, String> SEARCH_ALIASES = buildAliases();

    private static Map<String, String> buildAliases() {
        Map<String, String> map = new HashMap<>();
        addOreAliases(map, "coal_ore", "coal", "煤 煤矿 煤炭");
        addOreAliases(map, "iron_ore", "iron", "铁 铁矿");
        addOreAliases(map, "copper_ore", "copper", "铜 铜矿");
        addOreAliases(map, "gold_ore", "gold", "金 黄金 金矿");
        addOreAliases(map, "redstone_ore", "redstone", "红石 红石矿");
        addOreAliases(map, "lapis_ore", "lapis_lazuli", "青金石 青金石矿");
        addOreAliases(map, "diamond_ore", "diamond", "钻石 钻石矿");
        addOreAliases(map, "emerald_ore", "emerald", "绿宝石 绿宝石矿");
        addOreAliases(map, "nether_quartz_ore", "quartz", "石英 下界石英");

        put(map, "minecraft:granite", "花岗岩 granite");
        put(map, "minecraft:diorite", "闪长岩 diorite");
        put(map, "minecraft:andesite", "安山岩 andesite");
        put(map, "minecraft:nether_gold_ore", "金 黄金 金矿 nether gold");
        put(map, "minecraft:ancient_debris", "远古残骸 下界合金 debris");
        put(map, "minecraft:chest", "箱子 宝箱");
        put(map, "minecraft:trapped_chest", "陷阱箱 箱子");
        put(map, "minecraft:ender_chest", "末影箱");
        put(map, "minecraft:barrel", "木桶 桶");
        put(map, "minecraft:spawner", "刷怪笼 刷怪箱");
        put(map, "minecraft:obsidian", "黑曜石");
        put(map, "minecraft:crying_obsidian", "哭泣的黑曜石");
        put(map, "minecraft:diamond_block", "钻石块");
        put(map, "minecraft:emerald_block", "绿宝石块");
        put(map, "minecraft:gold_block", "金块");
        put(map, "minecraft:iron_block", "铁块");
        put(map, "minecraft:netherite_block", "下界合金块");
        put(map, "minecraft:water", "水 水源");
        put(map, "minecraft:lava", "岩浆 熔岩");
        put(map, "minecraft:budding_amethyst", "紫水晶母岩");
        return map;
    }

    private static void addOreAliases(Map<String, String> map, String path, String english, String chinese) {
        put(map, "minecraft:" + path, english + " " + chinese);
        put(map, "minecraft:deepslate_" + path, english + " " + chinese + " 深层 深板岩");
    }

    private static void put(Map<String, String> map, String id, String keywords) {
        map.merge(id, keywords, (a, b) -> a + " " + b);
    }
}
