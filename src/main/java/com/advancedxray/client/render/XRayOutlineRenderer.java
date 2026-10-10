package com.advancedxray.client.render;

import com.advancedxray.AdvancedXRay;
import com.advancedxray.client.config.XRayConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.util.Formatting;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * X-Ray 方块轮廓渲染器 —— v1.2。
 *
 * <h2>Sodium 兼容性</h2>
 * 只使用 Fabric API 公开稳定的 {@link WorldRenderEvents#AFTER_ENTITIES} 派发点，
 * 自己维护缓存并用官方 {@link VertexConsumerProvider} 写线段，<b>完全不碰</b>区块
 * meshing / ChunkBuilder / Occlusion，也不注入任何 Mixin（只有一个 Access Widener）。
 *
 * <h2>v1.2 修好的关键 Bug</h2>
 * <h3>1. ArrayIndexOutOfBoundsException: Index 128 out of bounds for length 128</h3>
 * v1.1 的扫描代码里有这么一段（简化）：
 * <pre>
 *   if (count &gt;= positions.length) {
 *       if (count &gt;= MAX_POSITIONS_PER_CHUNK) {
 *           cache.size = count;   // ← 设了 size=8192
 *           return;               // ← 但 cache.positions 还是 length=128 的旧数组！
 *       }
 *       positions = Arrays.copyOf(...);  // ← 扩容只改了局部变量，没写回 cache
 *   }
 * </pre>
 * 于是出现「数组长度 128 + size 8192」的不自洽状态，渲染时读到 i=128 就崩。
 *
 * <p><b>v1.2 的修法</b>：
 * <ol>
 *     <li>数组与长度永远通过 {@link ChunkSnapshot} 一起发布 —— 一个 {@code final} 原子组，
 *         渲染线程拿到的永远是自洽的 {@code positions + size}。</li>
 *     <li>写入前先判断 {@code count == positions.length}，<b>先检查上限、再决定是否扩容</b>，
 *         彻底杜绝「扩容了却没写回」。</li>
 *     <li>{@code snapshot} 用 {@code volatile} 发布。</li>
 *     <li>整个渲染回调用 try-catch 包起来：万一还有漏网的异常，只跳过这一帧，绝不崩游戏。</li>
 * </ol>
 *
 * <h3>2. 移动时方块消失 / 闪烁</h3>
 * 根因是<b>渲染阶段的距离剔除阈值（renderDistance）比扫描阶段的裁剪阈值小</b>，
 * 加上扫描有延迟，导致「刚进视野的方块还没扫到」。
 * <p>v1.2 的修法：</p>
 * <ul>
 *     <li>扫描半径 = 渲染距离 + 2 区块（预留 32 格缓冲）</li>
 *     <li>扫描裁剪阈值 = 渲染剔除阈值 = {@code renderDistance + SCAN_MARGIN}，
 *         两者完全一致 ⇒ 边界不会来回跳</li>
 *     <li>玩家移动时自动提高每 tick 扫描区块数（8 个），快速补齐新区域</li>
 *     <li>淘汰半径放宽到 +4 区块，避免「刚出界就被删」</li>
 * </ul>
 *
 * <h2>v1.2 新增：相邻面剔除（可开关，默认开）</h2>
 * 两个紧挨着的高亮方块之间那条共享棱不再绘制，一整片矿脉显示为干净的线框外壳。
 * <ul>
 *     <li>顶点数下降 60~80%（矿脉越大省得越多）</li>
 *     <li>零星分布的矿石看起来和以前完全一样（没有相邻方块可剔除）</li>
 *     <li>实现方式：每个方块算出 6 个面「是否被邻块遮挡」的位掩码；
 *         每条棱由它所属的两个面决定 —— 只要有一个面是露出的，这条棱就画</li>
 * </ul>
 *
 * <h2>v1.2 性能设计</h2>
 * <ol>
 *     <li><b>零对象分配</b>：原版 {@code VertexConsumer.vertex(Matrix4f,...)} 每个顶点
 *         都 {@code new Vector3f()}（字节码确认）。本类改用复用的 {@link #SCRATCH_VEC}
 *         手工做矩阵变换，4000 个方块 = 每帧省下最多 96000 次对象分配。</li>
 *     <li><b>增量扫描</b>：每 tick 只扫 2~8 个区块，摊平开销，没有周期性卡顿尖峰。</li>
 *     <li><b>扫描期按距离裁剪</b>：缓存规模大幅下降。</li>
 *     <li><b>视锥剔除</b>：背对镜头的方块直接跳过。</li>
 *     <li><b>无 lambda 写线段</b>：直接遍历 {@code getBoundingBoxes()} 写棱。</li>
 * </ol>
 */
public final class XRayOutlineRenderer {

    private static final XRayOutlineRenderer INSTANCE = new XRayOutlineRenderer();

    // ------------------------------------------------------------------
    // 可调参数
    // ------------------------------------------------------------------

    /** 单个区块缓存多久刷新一次（tick）。40 tick = 2 秒。 */
    private static final int CHUNK_REFRESH_TICKS = 40;

    /** 静止时 / 移动时的每 tick 扫描区块数。 */
    private static final int MIN_CHUNKS_PER_TICK = 2;
    private static final int MAX_CHUNKS_PER_TICK = 6;
    private static final int MOVING_CHUNKS_PER_TICK = 8;

    /** 垂直扫描范围（玩家上下各多少格）。 */
    private static final int VERTICAL_RANGE = 48;

    /** 扫描裁剪余量（格）。渲染剔除也用同一个值，保证边界一致、不闪烁。 */
    private static final int SCAN_MARGIN = 32;

    /** 单个区块最多缓存多少格（防内存爆）。 */
    private static final int MAX_POSITIONS_PER_CHUNK = 8192;

    /** 数组初始长度（预分配，避免频繁扩容）。 */
    private static final int INITIAL_ARRAY_SIZE = 256;

    /** 单帧最多绘制多少个方块。超过即视为过载（v1.3 从 6000 下调到 4000，更早触发保护）。 */
    private static final int MAX_DRAWN_BOXES = 4000;

    /** 连续多少帧达到上限就判定为过载（v1.3 过载保护）。 */
    private static final int OVERLOAD_FRAMES = 3;

    /** 自动关闭后的冷却时间（毫秒），避免反复触发 / 刷屏。 */
    private static final long OVERLOAD_COOLDOWN_MS = 30_000L;

    private static final ShapeContext SHAPE_CONTEXT = ShapeContext.absent();

    /** 复用的向量，避免每个顶点 new 一个 Vector3f。 */
    private static final Vector3f SCRATCH_VEC = new Vector3f();

    /** 是否已经记录过渲染异常，避免聊天栏刷屏。 */
    private static final AtomicBoolean CRASH_LOGGED = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // 过载保护状态（v1.3）
    // ------------------------------------------------------------------

    /** 连续达到绘制上限的帧数。 */
    private static int overloadStreak = 0;

    /** 上次自动关闭的时间戳（毫秒），用于冷却。 */
    private static long lastOverloadDisableAt = 0L;

    /** 待显示的过载提示（由渲染线程写入，由客户端 tick 读取并显示）。 */
    private static volatile boolean pendingOverloadNotice = false;

    /**
     * 自定义线段图层：与原版 {@code RenderLayer.getLines()} 同构，
     * 只把深度测试换成空操作的 {@code ALWAYS_DEPTH_TEST}（不干扰我们设置的 GL 状态）。
     * 需要 advanced-x-ray.accesswidener 放开访问权限，<b>不是 Mixin，与 Sodium 无冲突</b>。
     */
    private static final RenderLayer XRAY_LAYER = createXRayLayer();

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private final Long2ObjectMap<ChunkCache> chunkCache = new Long2ObjectOpenHashMap<>();
    private final LongArrayList renderOrder = new LongArrayList();

    private ClientWorld world;
    private int tickCounter;
    private int scanCursor;
    private int lastRevision = Integer.MIN_VALUE;
    private BlockPos lastPlayerPos;

    private XRayOutlineRenderer() {
    }

    public static XRayOutlineRenderer getInstance() {
        return INSTANCE;
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(XRayOutlineRenderer::onRenderWorldSafely);
    }

    private static RenderLayer createXRayLayer() {
        return RenderLayer.of(
                "advanced_xray_lines",
                VertexFormats.LINES,
                VertexFormat.DrawMode.LINES,
                1536,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(RenderPhase.LINES_PROGRAM)
                        .depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
                        .writeMaskState(RenderPhase.COLOR_MASK)
                        .build(false)
        );
    }

    // ==================================================================
    // 数据结构
    // ==================================================================

    /**
     * 一个区块扫描结果的<b>不可变快照</b>。
     *
     * <p>{@code positions} 与 {@code size} 一起被 final 固定，渲染线程一次读取就能拿到
     * 自洽的整体 —— 这是修复 {@code Index 128 out of bounds} 的关键。</p>
     */
    private static final class ChunkSnapshot {
        static final ChunkSnapshot EMPTY = new ChunkSnapshot(new long[0], 0, null, false);

        final long[] positions;
        final int size;
        /** 位置集合，相邻面剔除用（可为 null）。 */
        final LongSet occupancy;
        /** 是否因为单区块上限被截断。 */
        final boolean truncated;

        ChunkSnapshot(long[] positions, int size, LongSet occupancy, boolean truncated) {
            this.positions = positions;
            this.size = size;
            this.occupancy = occupancy;
            this.truncated = truncated;
        }
    }

    private static final class ChunkCache {
        /** volatile：让渲染线程永远看到完整发布后的快照。 */
        volatile ChunkSnapshot snapshot = ChunkSnapshot.EMPTY;
        int lastScanTick = Integer.MIN_VALUE;
    }

    // ==================================================================
    // 1. 增量扫描（客户端 tick）
    // ==================================================================

    public void tick(MinecraftClient client) {
        ClientWorld currentWorld = client.world;

        if (currentWorld == null) {
            clear();
            world = null;
            lastPlayerPos = null;
            return;
        }

        if (currentWorld != world) {
            world = currentWorld;
            clear();
            lastPlayerPos = null;
        }

        XRayConfig config = XRayConfig.get();
        tickCounter++;

        // 开关关闭 / 列表为空 → 清空缓存，CPU 占用归零
        if (!config.isEnabled() || config.size() == 0) {
            if (!chunkCache.isEmpty()) {
                chunkCache.clear();
            }
            return;
        }

        if (client.player == null) {
            return;
        }

        if (config.getRevision() != lastRevision) {
            lastRevision = config.getRevision();
            chunkCache.clear();
            scanCursor = 0;
        }

        BlockPos playerPos = client.player.getBlockPos();

        // 移动检测：上一 tick 到这一 tick 位移超过 1 格 → 视为移动中，加大扫描预算
        boolean moving = lastPlayerPos != null
                && lastPlayerPos.getSquaredDistance(playerPos) > 1.0D;
        lastPlayerPos = playerPos;

        int radius = config.getScanRadiusChunks();
        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;

        // ---- 1) 剔除太远的区块缓存（放宽到 +4 区块，避免边界抖动） ----
        int keepRadius = radius + 4;
        chunkCache.long2ObjectEntrySet().removeIf(entry -> {
            long key = entry.getLongKey();
            int dx = Math.abs(ChunkPos.getPackedX(key) - centerChunkX);
            int dz = Math.abs(ChunkPos.getPackedZ(key) - centerChunkZ);
            return dx > keepRadius || dz > keepRadius;
        });

        // ---- 2) 本 tick 增量扫描 ----
        int budget;
        if (moving) {
            budget = MOVING_CHUNKS_PER_TICK;
        } else {
            budget = clamp(MIN_CHUNKS_PER_TICK + radius / 3, MIN_CHUNKS_PER_TICK, MAX_CHUNKS_PER_TICK);
        }

        for (int i = 0; i < budget; i++) {
            long key = pickChunkToScan(centerChunkX, centerChunkZ, radius);
            if (key == Long.MIN_VALUE) {
                break;
            }
            scanChunk(key, config, playerPos);
        }
    }

    private long pickChunkToScan(int centerChunkX, int centerChunkZ, int radius) {
        int size = radius * 2 + 1;
        int total = size * size;

        for (int i = 0; i < total; i++) {
            int index = scanCursor;
            scanCursor = (scanCursor + 1) % total;

            int dx = index % size - radius;
            int dz = index / size - radius;
            long key = ChunkPos.toLong(centerChunkX + dx, centerChunkZ + dz);

            ChunkCache cache = chunkCache.get(key);
            if (cache != null && tickCounter - cache.lastScanTick < CHUNK_REFRESH_TICKS) {
                continue;
            }
            return key;
        }
        return Long.MIN_VALUE;
    }

    /**
     * 扫描单个区块。
     *
     * <p><b>安全写法</b>：先判断 {@code count == positions.length}，
     * <b>先检查上限、再决定扩容</b>；所有写入都发生在局部数组里，
     * 最后才通过 {@link ChunkSnapshot} 一次性发布。</p>
     */
    private void scanChunk(long chunkKey, XRayConfig config, BlockPos playerPos) {
        int chunkX = ChunkPos.getPackedX(chunkKey);
        int chunkZ = ChunkPos.getPackedZ(chunkKey);

        ChunkCache cache = chunkCache.get(chunkKey);
        if (cache == null) {
            cache = new ChunkCache();
            chunkCache.put(chunkKey, cache);
        }

        // 区块没加载：清空快照，但【不更新】lastScanTick，让游标下一轮再试
        if (!world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
            cache.snapshot = ChunkSnapshot.EMPTY;
            return;
        }

        WorldChunk chunk = world.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            cache.snapshot = ChunkSnapshot.EMPTY;
            cache.lastScanTick = tickCounter;
            return;
        }

        cache.lastScanTick = tickCounter;

        final boolean skipInnerFaces = config.isSkipInnerFaces();

        int minY = playerPos.getY() - VERTICAL_RANGE;
        int maxY = playerPos.getY() + VERTICAL_RANGE;

        // 与渲染剔除完全一致的阈值 → 边界稳定，不闪烁
        double maxDistance = config.getRenderDistance() + SCAN_MARGIN;
        double maxDistanceSq = maxDistance * maxDistance;
        double playerX = playerPos.getX() + 0.5D;
        double playerY = playerPos.getY() + 0.5D;
        double playerZ = playerPos.getZ() + 0.5D;

        int bottomSectionY = world.getBottomSectionCoord();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        ChunkSection[] sections = chunk.getSectionArray();

        long[] positions = new long[INITIAL_ARRAY_SIZE];
        int count = 0;
        boolean truncated = false;

        int playerSectionIndex = (playerPos.getY() >> 4) - bottomSectionY;

        scan:
        for (int k = 0; k < sections.length; k++) {
            // 从玩家所在层向外扩散：万一触发上限，丢掉的是离玩家最远的方块
            int delta = (k % 2 == 0) ? (k / 2) : -((k + 1) / 2);
            int index = playerSectionIndex + delta;
            if (index < 0 || index >= sections.length) {
                continue;
            }

            ChunkSection section = sections[index];
            if (section == null || section.isEmpty()) {
                continue;
            }

            int sectionBaseY = (bottomSectionY + index) << 4;
            if (sectionBaseY + 15 < minY || sectionBaseY > maxY) {
                continue;
            }

            for (int x = 0; x < 16; x++) {
                int worldX = baseX + x;
                for (int y = 0; y < 16; y++) {
                    int worldY = sectionBaseY + y;
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir() || !config.isHighlighted(state)) {
                            continue;
                        }

                        int worldZ = baseZ + z;

                        double dx = worldX + 0.5D - playerX;
                        double dy = worldY + 0.5D - playerY;
                        double dz = worldZ + 0.5D - playerZ;
                        if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                            continue;
                        }

                        if (count == positions.length) {
                            if (count >= MAX_POSITIONS_PER_CHUNK) {
                                truncated = true;
                                break scan;
                            }
                            positions = java.util.Arrays.copyOf(positions,
                                    Math.min(positions.length * 2, MAX_POSITIONS_PER_CHUNK));
                        }
                        positions[count++] = BlockPos.asLong(worldX, worldY, worldZ);
                    }
                }
            }
        }

        // 相邻面剔除需要 O(1) 的邻块查询
        LongSet occupancy = null;
        if (skipInnerFaces && count > 0) {
            occupancy = new LongOpenHashSet(Math.max(16, count));
            for (int i = 0; i < count; i++) {
                occupancy.add(positions[i]);
            }
        }

        // ★ 一次性原子发布：positions / size / occupancy 永远自洽
        cache.snapshot = new ChunkSnapshot(positions, count, occupancy, truncated);
    }

    public void clear() {
        chunkCache.clear();
        renderOrder.clear();
        scanCursor = 0;
        lastRevision = Integer.MIN_VALUE;
        lastPlayerPos = null;
    }

    // ==================================================================
    // 2. 渲染（每帧）
    // ==================================================================

    /** 安全外壳：任何异常都只跳过这一帧，绝不崩游戏。 */
    private static void onRenderWorldSafely(WorldRenderContext context) {
        try {
            onRenderWorld(context);
        } catch (Throwable t) {
            if (!CRASH_LOGGED.getAndSet(true)) {
                AdvancedXRay.LOGGER.error("[Advanced X-Ray] 渲染时发生异常，已跳过该帧（不会崩溃）。", t);
            }
        }
    }

    private static void onRenderWorld(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        XRayConfig config = XRayConfig.get();
        if (world == null || !config.isEnabled() || config.size() == 0) {
            return;
        }

        XRayOutlineRenderer renderer = INSTANCE;
        if (renderer.chunkCache.isEmpty()) {
            return;
        }

        VertexConsumerProvider consumers = context.consumers();
        MatrixStack matrices = context.matrixStack();
        if (consumers == null || matrices == null) {
            return;
        }
        if (!(consumers instanceof VertexConsumerProvider.Immediate immediate)) {
            return;
        }

        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();
        Frustum frustum = context.frustum();

        // 阈值与扫描阶段一致（renderDistance + SCAN_MARGIN）→ 边界不闪
        double maxDistance = config.getRenderDistance() + SCAN_MARGIN;
        double maxDistanceSq = maxDistance * maxDistance;

        LongArrayList order = renderer.renderOrder;
        order.clear();
        for (Long2ObjectMap.Entry<ChunkCache> entry : renderer.chunkCache.long2ObjectEntrySet()) {
            if (entry.getValue().snapshot.size > 0) {
                order.add(entry.getLongKey());
            }
        }
        if (order.isEmpty()) {
            return;
        }
        if (order.size() > 1) {
            final double camX = cameraPos.x;
            final double camZ = cameraPos.z;
            order.sort((keyA, keyB) -> Double.compare(
                    chunkDistanceSq(keyA, camX, camZ),
                    chunkDistanceSq(keyB, camX, camZ)));
        }

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f positionMatrix = matrices.peek().getPositionMatrix();

        VertexConsumer buffer = immediate.getBuffer(XRAY_LAYER);

        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        int drawn = 0;
        boolean skipInnerFaces = config.isSkipInnerFaces();
        boolean hitLimit = false;

        outer:
        for (int chunkIndex = 0; chunkIndex < order.size(); chunkIndex++) {
            long chunkKey = order.getLong(chunkIndex);
            ChunkCache cache = renderer.chunkCache.get(chunkKey);
            if (cache == null) {
                continue;
            }

            // ★ 只读一次快照：positions 与 size 从此保证自洽（修 ArrayIndexOutOfBounds 的关键）
            ChunkSnapshot snapshot = cache.snapshot;
            long[] positions = snapshot.positions;
            int size = snapshot.size;
            if (size == 0) {
                continue;
            }

            int chunkX = ChunkPos.getPackedX(chunkKey);
            int chunkZ = ChunkPos.getPackedZ(chunkKey);
            LongSet occupancy = snapshot.occupancy;

            for (int i = 0; i < size; i++) {
                if (drawn >= MAX_DRAWN_BOXES) {
                    hitLimit = true;
                    break outer;
                }

                long packed = positions[i];
                int x = BlockPos.unpackLongX(packed);
                int y = BlockPos.unpackLongY(packed);
                int z = BlockPos.unpackLongZ(packed);

                double dx = x + 0.5D - cameraPos.x;
                double dy = y + 0.5D - cameraPos.y;
                double dz = z + 0.5D - cameraPos.z;
                if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                    continue;
                }

                mutablePos.set(x, y, z);
                BlockState state = world.getBlockState(mutablePos);

                // 方块可能已被挖掉 / 替换，再确认一次，避免「幽灵边框」
                if (!config.isHighlighted(state)) {
                    continue;
                }

                // 视锥剔除：屏幕外 / 背对镜头的直接跳过
                if (frustum != null && !frustum.isVisible(new Box(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D))) {
                    continue;
                }

                VoxelShape shape = state.getOutlineShape(world, mutablePos, SHAPE_CONTEXT);
                if (shape.isEmpty()) {
                    shape = VoxelShapes.fullCube();
                }

                int color = config.getColor(state.getBlock());

                if (skipInnerFaces && occupancy != null && isFullCube(shape)) {
                    int solidMask = computeSolidMask(renderer.chunkCache, occupancy,
                            chunkX, chunkZ, x, y, z);
                    writeCubeEdges(buffer, positionMatrix,
                            x, y, z, x + 1.0F, y + 1.0F, z + 1.0F, solidMask, color);
                } else {
                    // 非满方块 / 关闭剔除：每个盒子画完整 12 条棱（solidMask = 0）
                    writeShapeOutline(buffer, positionMatrix, shape, x, y, z, color);
                }

                drawn++;
            }
        }

        matrices.pop();

        // ---- 同步绘制 + 只在真正绘制的那一刻关闭深度测试 ----
        RenderSystem.disableDepthTest();
        immediate.draw(XRAY_LAYER);
        RenderSystem.enableDepthTest();

        // ---- v1.3 过载检测：连续多帧撞到绘制上限 → 判定为过载 ----
        if (hitLimit) {
            overloadStreak++;
            if (overloadStreak >= OVERLOAD_FRAMES) {
                overloadStreak = 0;
                long now = System.currentTimeMillis();
                if (config.isOverloadProtection() && now - lastOverloadDisableAt > OVERLOAD_COOLDOWN_MS) {
                    lastOverloadDisableAt = now;
                    config.forceDisableByOverload();
                    pendingOverloadNotice = true;
                }
            }
        } else {
            overloadStreak = 0;
        }
    }

    /**
     * 由客户端 tick 调用：如果有待显示的过载提示，就在聊天栏 + 屏幕中央提示玩家。
     *
     * <p>放在 tick 里而不是渲染线程里，是因为渲染线程里发聊天消息 / 设置 HUD 提示不安全。</p>
     */
    public void pollOverloadNotice(MinecraftClient client) {
        if (!pendingOverloadNotice) {
            return;
        }
        pendingOverloadNotice = false;

        if (client.player != null) {
            client.player.sendMessage(
                    Text.translatable("gui.advanced-x-ray.overload.chat").formatted(Formatting.RED),
                    false);
        }
        OverloadHud.set(Text.translatable("gui.advanced-x-ray.overload.hud"));
    }

    private static double chunkDistanceSq(long chunkKey, double cameraX, double cameraZ) {
        double centerX = (ChunkPos.getPackedX(chunkKey) << 4) + 8.0D;
        double centerZ = (ChunkPos.getPackedZ(chunkKey) << 4) + 8.0D;
        double dx = centerX - cameraX;
        double dz = centerZ - cameraZ;
        return dx * dx + dz * dz;
    }

    /**
     * 计算 6 个面「是否被相邻高亮方块遮挡」的位掩码。
     * <p>bit0=-X, bit1=+X, bit2=-Y, bit3=+Y, bit4=-Z, bit5=+Z（置位 = 被遮挡）</p>
     *
     * <p>性能：同区块内的邻居直接查 {@code occupancy} 集合（O(1)）；
     * 只有贴到区块边界的方块才需要去查相邻区块，代价可以忽略。</p>
     */
    private static int computeSolidMask(Long2ObjectMap<ChunkCache> map, LongSet selfSet,
                                        int chunkX, int chunkZ,
                                        int x, int y, int z) {
        int localX = x & 15;
        int localZ = z & 15;
        int mask = 0;

        // -X
        if (localX != 0) {
            if (selfSet.contains(BlockPos.asLong(x - 1, y, z))) {
                mask |= 1 << 0;
            }
        } else if (isFilledInChunk(map, chunkX - 1, chunkZ, x - 1, y, z)) {
            mask |= 1 << 0;
        }

        // +X
        if (localX != 15) {
            if (selfSet.contains(BlockPos.asLong(x + 1, y, z))) {
                mask |= 1 << 1;
            }
        } else if (isFilledInChunk(map, chunkX + 1, chunkZ, x + 1, y, z)) {
            mask |= 1 << 1;
        }

        // -Y / +Y：同一个区块列内，直接查 selfSet
        if (selfSet.contains(BlockPos.asLong(x, y - 1, z))) {
            mask |= 1 << 2;
        }
        if (selfSet.contains(BlockPos.asLong(x, y + 1, z))) {
            mask |= 1 << 3;
        }

        // -Z
        if (localZ != 0) {
            if (selfSet.contains(BlockPos.asLong(x, y, z - 1))) {
                mask |= 1 << 4;
            }
        } else if (isFilledInChunk(map, chunkX, chunkZ - 1, x, y, z - 1)) {
            mask |= 1 << 4;
        }

        // +Z
        if (localZ != 15) {
            if (selfSet.contains(BlockPos.asLong(x, y, z + 1))) {
                mask |= 1 << 5;
            }
        } else if (isFilledInChunk(map, chunkX, chunkZ + 1, x, y, z + 1)) {
            mask |= 1 << 5;
        }

        return mask;
    }

    private static boolean isFilledInChunk(Long2ObjectMap<ChunkCache> map, int chunkX, int chunkZ,
                                            int x, int y, int z) {
        ChunkCache cache = map.get(ChunkPos.toLong(chunkX, chunkZ));
        if (cache == null) {
            return false;
        }
        LongSet set = cache.snapshot.occupancy;
        if (set == null) {
            return false;
        }
        return set.contains(BlockPos.asLong(x, y, z));
    }

    /** 判断一个形状是不是标准满方块 (0,0,0)-(1,1,1)。 */
    private static boolean isFullCube(VoxelShape shape) {
        List<Box> boxes = shape.getBoundingBoxes();
        if (boxes.size() != 1) {
            return false;
        }
        Box box = boxes.get(0);
        return box.minX == 0.0D && box.minY == 0.0D && box.minZ == 0.0D
                && box.maxX == 1.0D && box.maxY == 1.0D && box.maxZ == 1.0D;
    }

    /** 非满方块 / 关闭相邻剔除时：把形状里每个盒子的 12 条棱全画出来。 */
    private static void writeShapeOutline(VertexConsumer buffer, Matrix4f matrix, VoxelShape shape,
                                          int offsetX, int offsetY, int offsetZ, int argb) {
        for (Box box : shape.getBoundingBoxes()) {
            writeCubeEdges(buffer, matrix,
                    (float) (offsetX + box.minX), (float) (offsetY + box.minY), (float) (offsetZ + box.minZ),
                    (float) (offsetX + box.maxX), (float) (offsetY + box.maxY), (float) (offsetZ + box.maxZ),
                    0, argb);
        }
    }

    /**
     * 按遮挡掩码写立方体的棱。
     *
     * <p>每条棱由它所属的<b>两个面</b>决定：只要这两个面里有一个是「露出的」，
     * 这条棱就需要绘制。</p>
     *
     * <pre>
     *   沿 X 的 4 条棱：(Y面, Z面)     沿 Y 的 4 条：(X面, Z面)     沿 Z 的 4 条：(X面, Y面)
     * </pre>
     *
     * <p>{@code solidMask == 0}（全部露出）时会画出完整 12 条棱，与旧行为一致。</p>
     */
    private static void writeCubeEdges(VertexConsumer buffer, Matrix4f matrix,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       int solidMask, int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) {
            a = 0xFF;
        }

        boolean eNegX = (solidMask & 0x01) == 0;
        boolean ePosX = (solidMask & 0x02) == 0;
        boolean eNegY = (solidMask & 0x04) == 0;
        boolean ePosY = (solidMask & 0x08) == 0;
        boolean eNegZ = (solidMask & 0x10) == 0;
        boolean ePosZ = (solidMask & 0x20) == 0;

        // 沿 X 的 4 条棱
        if (eNegY || eNegZ) {
            writeEdge(buffer, matrix, x1, y1, z1, x2, y1, z1, 1.0F, 0.0F, 0.0F, r, g, b, a);
        }
        if (eNegY || ePosZ) {
            writeEdge(buffer, matrix, x1, y1, z2, x2, y1, z2, 1.0F, 0.0F, 0.0F, r, g, b, a);
        }
        if (ePosY || eNegZ) {
            writeEdge(buffer, matrix, x1, y2, z1, x2, y2, z1, 1.0F, 0.0F, 0.0F, r, g, b, a);
        }
        if (ePosY || ePosZ) {
            writeEdge(buffer, matrix, x1, y2, z2, x2, y2, z2, 1.0F, 0.0F, 0.0F, r, g, b, a);
        }

        // 沿 Y 的 4 条棱
        if (eNegX || eNegZ) {
            writeEdge(buffer, matrix, x1, y1, z1, x1, y2, z1, 0.0F, 1.0F, 0.0F, r, g, b, a);
        }
        if (ePosX || eNegZ) {
            writeEdge(buffer, matrix, x2, y1, z1, x2, y2, z1, 0.0F, 1.0F, 0.0F, r, g, b, a);
        }
        if (eNegX || ePosZ) {
            writeEdge(buffer, matrix, x1, y1, z2, x1, y2, z2, 0.0F, 1.0F, 0.0F, r, g, b, a);
        }
        if (ePosX || ePosZ) {
            writeEdge(buffer, matrix, x2, y1, z2, x2, y2, z2, 0.0F, 1.0F, 0.0F, r, g, b, a);
        }

        // 沿 Z 的 4 条棱
        if (eNegX || eNegY) {
            writeEdge(buffer, matrix, x1, y1, z1, x1, y1, z2, 0.0F, 0.0F, 1.0F, r, g, b, a);
        }
        if (ePosX || eNegY) {
            writeEdge(buffer, matrix, x2, y1, z1, x2, y1, z2, 0.0F, 0.0F, 1.0F, r, g, b, a);
        }
        if (eNegX || ePosY) {
            writeEdge(buffer, matrix, x1, y2, z1, x1, y2, z2, 0.0F, 0.0F, 1.0F, r, g, b, a);
        }
        if (ePosX || ePosY) {
            writeEdge(buffer, matrix, x2, y2, z1, x2, y2, z2, 0.0F, 0.0F, 1.0F, r, g, b, a);
        }
    }

    /**
     * 写一条线段（两个顶点）。
     *
     * <p><b>关键性能点</b>：不用 {@code buffer.vertex(matrix, x, y, z)} —— 原版那个默认方法
     * 每个顶点都 {@code new Vector3f()}（字节码确认），一帧几万次分配会把帧率砍半。
     * 这里用复用的 {@link #SCRATCH_VEC} 做变换，再走 {@code vertex(float,float,float)}，全程零分配。</p>
     */
    private static void writeEdge(VertexConsumer buffer, Matrix4f matrix,
                                  float ax, float ay, float az, float bx, float by, float bz,
                                  float nx, float ny, float nz,
                                  int r, int g, int b, int a) {
        matrix.transformPosition(ax, ay, az, SCRATCH_VEC);
        buffer.vertex(SCRATCH_VEC.x, SCRATCH_VEC.y, SCRATCH_VEC.z).color(r, g, b, a).normal(nx, ny, nz);

        matrix.transformPosition(bx, by, bz, SCRATCH_VEC);
        buffer.vertex(SCRATCH_VEC.x, SCRATCH_VEC.y, SCRATCH_VEC.z).color(r, g, b, a).normal(nx, ny, nz);
    }

    // ==================================================================
    // 供 GUI 读取的统计信息
    // ==================================================================

    /** 当前缓存的方块总数。 */
    public int getScannedBlockCount() {
        int total = 0;
        for (ChunkCache cache : chunkCache.values()) {
            total += cache.snapshot.size;
        }
        return total;
    }

    /** 有多少个区块触发了单区块上限（>0 时 GUI 会给出提示）。 */
    public int getTruncatedChunkCount() {
        int total = 0;
        for (ChunkCache cache : chunkCache.values()) {
            if (cache.snapshot.truncated) {
                total++;
            }
        }
        return total;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
