# Advanced X-Ray

> 一个 **纯客户端** 的 Minecraft Fabric 透视高亮模组：把你关心的方块用彩色外轮廓框出来，隔着石头也能看见。
> 专为 **Sodium（钠）** 兼容而设计，不注入任何 Mixin，不碰区块渲染管线。

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-Loader%200.16%2B-DBB69B?style=flat-square)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)
[![AI Generated](https://img.shields.io/badge/Code-AI%20Generated-8A2BE2?style=flat-square)](#-ai-生成声明)

---

## ⚠️ 重要警告（请先读这一段）

> **本模组仅供单人游戏，或你拥有管理权限的自建服务器使用。**
>
> X-Ray 类模组在绝大多数多人服务器上属于**作弊行为**，使用可能导致**账号被封禁**。
> 在他人服务器使用本模组所产生的任何后果，由使用者自行承担。
>
> 本模组是**纯客户端**模组：它不修改服务端、不发送任何自定义网络包、不收集任何数据。
> 但「客户端能看到什么」本身就可能违反服务器规则 —— 请自行判断。

---

## 📖 目录

- [功能特性](#-功能特性)
- [安装](#-安装)
- [使用说明](#-使用说明)
- [配置](#-配置)
- [Sodium 兼容性说明](#-sodium-兼容性说明)
- [技术实现](#-技术实现)
- [AI 生成声明](#-ai-生成声明)
- [免责声明](#-免责声明)
- [许可证](#-许可证)

---

## ✨ 功能特性

| 功能 | 说明 |
|---|---|
| **透视高亮** | 选中的方块绘制彩色外轮廓，**隔着其他方块也能看见** |
| **只画边框** | 不填充方块面，不修改方块模型，不改变世界渲染结果 |
| **彩色区分** | 每个方块可单独设置边框颜色（12 色调色板），一眼区分不同矿物 |
| **合并相邻** | 一整片矿脉只显示外壳线框，顶点数下降 60~80%，帧率明显提升（可开关） |
| **渲染距离可调** | 8 ~ 128 格滑条，避免「全图透视」拖垮帧率 |
| **过载保护** | 方块过多时自动关闭透视并提示，不会卡死、不会崩溃 |
| **方块搜索** | 支持方块 ID（`minecraft:diamond_ore`）、中英文名称、常用关键词别名 |
| **全方块支持** | 遍历注册表，原版与**任何第三方模组**的方块都能搜索与高亮 |
| **多选** | 可同时高亮任意多个方块 |
| **配置持久化** | 自动保存到 `config/advanced-x-ray.json` |
| **纯客户端** | `environment: client`，专用服务端直接跳过加载，服务端零逻辑 |

### 默认快捷键

| 按键 | 功能 |
|---|---|
| `X` | 打开 / 关闭配置界面（可在原版「按键设置 → Advanced X-Ray」中修改） |

---

## 📦 安装

### 前置要求

| 组件 | 版本 |
|---|---|
| Minecraft | **1.21.1**（Java 版） |
| Fabric Loader | **0.16.0 或更高** |
| Fabric API | 任意 1.21.1 版本 |
| Java | **21 或更高** |

### 步骤

1. 安装 [Fabric Loader](https://fabricmc.net/use/installer/)（选择 Minecraft 1.21.1）
2. 下载 [Fabric API](https://modrinth.com/mod/fabric-api)（1.21.1 版本）
3. 从本仓库的 [Releases](../../releases) 页面下载 `advanced-x-ray-x.y.z.jar`
4. 把 **Fabric API** 和 **Advanced X-Ray** 两个 jar 一起放进 `.minecraft/mods/`
5. 启动游戏

> **Sodium（钠）** 与 **ModMenu** 均为**可选**依赖。本模组已针对 Sodium 做兼容设计。

---

## 🎮 使用说明

1. 进入游戏，按 **`X`** 打开配置界面
2. 在**搜索框**输入方块 ID 或名称，例如：
   - `diamond` / `钻石` / `diamond_ore` / `minecraft:diamond_ore`
   - `granite` / `花岗岩`
   - `create:copper_ore`（第三方模组方块同样支持）
3. 在**左侧「搜索结果」**列表点击某个方块 → 加入高亮列表
4. 在**右侧「已高亮方块」**列表：
   - 点击**左侧色块** → 循环切换该方块的边框颜色
   - 点击**行末的 `x`** → 从列表移除
5. 用**渲染距离滑条**调整透视范围（建议 32 ~ 48 格）
6. 用**「合并相邻」**按钮切换「矿脉外壳」与「每块独立边框」两种显示风格
7. 按 **`完成`** 或 `Esc` 关闭界面，设置即时生效

### 界面速览

```
┌──────────────────────────── 标题 ────────────────────────────┐
│  [透视高亮：开启] [合并相邻：开启] [清空列表] [过载保护：开启]   │
│  [ 渲染距离：48 格 ━━━━●━━━━ ]                                │
│  [ 搜索框：方块ID / 中文名 / 英文名 ..................... ]     │
│  已扫描 1234 个方块          ┌── 已高亮方块 ─────────┐         │
│  ┌── 搜索结果 ───────┐      │ ■ Granite         x │         │
│  │ Granite            │      │   minecraft:granite │         │
│  └────────────────────┘      └──────────────────────┘         │
│                      [       完成       ]                    │
└──────────────────────────────────────────────────────────────┘
```

---

## ⚙️ 配置

配置文件位置：`.minecraft/config/advanced-x-ray.json`

```json
{
  "enabled": true,
  "renderDistance": 48,
  "skipInnerFaces": true,
  "overloadProtection": true,
  "blockColors": {
    "minecraft:diamond_ore": -16776961,
    "minecraft:granite": -8323200
  }
}
```

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | boolean | `true` | 透视总开关 |
| `renderDistance` | int | `48` | 渲染距离（格），范围 8 ~ 128 |
| `skipInnerFaces` | boolean | `true` | 合并相邻方块（只画外壳） |
| `overloadProtection` | boolean | `true` | 过载时自动关闭透视 |
| `blockColors` | object | `{}` | 方块 ID → 边框颜色（ARGB 十进制） |

> 从 v1.0 / v1.1 升级时，旧的 `highlightedBlocks` 字段会被自动迁移进 `blockColors`，不会丢失已选方块。

---

## 🟢 Sodium 兼容性说明

本模组从**架构层面**保证与 Sodium 兼容，而不是靠打补丁：

| 做法 | 原因 |
|---|---|
| 只使用 Fabric API 公开稳定的 `WorldRenderEvents.AFTER_ENTITIES` 事件 | Sodium 只重写区块 meshing 与可见性判定，不会移除这个事件派发点 |
| 自己维护方块坐标缓存，用官方 `VertexConsumerProvider` 独立写入线段顶点 | 完全不接触 `ChunkBuilder` / `SectionBuilder` / `Occlusion` / 区块 Meshing |
| **零 Mixin** | 不注入任何内部渲染逻辑，不存在「注入点被 Sodium 重写后失效」的问题 |
| 只用一个 Access Widener 放开 `RenderLayer.of` 的访问权限 | Access Widener 只放宽访问修饰符，不修改方法体、不注入代码 |
| 使用 `ALWAYS_DEPTH_TEST`（空操作）的自定义线段图层 | 不抢占 GL 深度状态，配合同步 `immediate.draw(layer)` 实现零状态泄漏 |

> 关于事件选择的说明：Fabric 官方 Javadoc 明确指出 `AFTER_TRANSLUCENT` 触发时
> 顶点消费者已被 flush（"Vertex consumers are not available in this event"），
> 在该事件中写入 provider 的数据要等到下一帧才绘制，会造成边框抖动。
> 而 `AFTER_ENTITIES` 的官方说明正是 "to append block-related quads to the entity consumers"，
> 因此本项目选用 `AFTER_ENTITIES`。

---

## 🔧 技术实现

### 透视（隔墙可见）原理

关键在于让深度测试在**真正 flush 线段的那一刻**处于关闭状态：

1. 自定义图层使用 `RenderPhase.ALWAYS_DEPTH_TEST`。该阶段的 `begin()` / `end()`
   两个 lambda 都被 `if (depthFunc != 519)` 拦住，属于**空操作**，
   因此它既不会强行打开深度测试，也不会覆盖外部设置的 GL 状态。
2. 写入顶点后调用 `immediate.draw(XRAY_LAYER)` 做**同步绘制**，
   在同一个方法内恢复深度测试 —— 状态零泄漏，不污染后续的实体 / 方块实体渲染。

### 性能设计

| 优化 | 效果 |
|---|---|
| 复用 `Vector3f` 手工做矩阵变换 | 原版 `vertex(Matrix4f,...)` 每个顶点都 `new Vector3f()`；本模组每帧最多省下 96000 次对象分配 |
| 增量扫描（每 tick 2~8 个区块） | 摊平扫描开销，消除周期性卡顿尖峰 |
| 扫描期按距离裁剪 | 缓存规模大幅下降 |
| 视锥剔除 | 背对镜头的方块直接跳过 |
| 相邻面剔除 | 矿脉顶点数下降 60~80% |
| 无 lambda 写线段 | 直接遍历 `getBoundingBoxes()` 写 12 条棱 |

### 项目结构

```
src/main/
├── java/com/advancedxray/
│   ├── AdvancedXRay.java                     # 常量与日志工具类（非入口）
│   └── client/
│       ├── AdvancedXRayClient.java           # 客户端入口：按键、事件注册
│       ├── config/XRayConfig.java            # 配置持久化（JSON）
│       ├── gui/XRayConfigScreen.java         # 配置界面
│       └── render/
│           ├── XRayOutlineRenderer.java      # 边框渲染核心
│           └── OverloadHud.java              # 过载提示 HUD
└── resources/
    ├── fabric.mod.json
    ├── advanced-x-ray.accesswidener
    ├── advanced-x-ray.mixins.json            # 空（本项目零 Mixin）
    └── assets/advanced-x-ray/
        ├── icon.png
        └── lang/{zh_cn,en_us}.json
```

---

## 🤖 AI 生成声明

> **本项目的全部源代码、资源文件与文档均由 AI（Claude，Anthropic）生成。**
>
> 人类参与的部分仅限于：需求描述、功能验收、游戏内测试与发布操作。
>
> 这意味着：
> - 代码**未经专业软件工程审查**，可能存在未发现的缺陷、性能问题或边界情况处理不当；
> - 代码风格与架构决策由 AI 做出，可能不符合某些团队的规范；
> - 后续维护若继续由 AI 进行，请自行评估其可靠性。
>
> 本项目采用 MIT 许可证，你可以自由使用、修改、分发，但请保留本声明与许可证文件。

---

## ⚖️ 免责声明

1. **使用风险自负**：本软件按「原样」提供，不附带任何明示或暗示的担保。
   作者不对因使用本软件而产生的任何直接或间接损失负责。
2. **多人服务器风险**：在他人服务器使用 X-Ray 类模组可能违反服务器规则并导致封禁，
   请在使用前确认你拥有相应权限。
3. **无恶意行为**：本模组为纯客户端模组，不联网、不收集数据、不修改服务端内容。
4. **第三方依赖**：本项目依赖 Fabric API（Apache-2.0）与 Minecraft（受 Mojang EULA 约束），
   本项目不打包、不分发任何第三方代码。

---

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源。

```
Copyright (c) 2026 changtian2248 (AI-assisted)
```

---

## 🙏 致谢

- [Fabric](https://fabricmc.net/) —— 模组加载器与 API
- [Sodium](https://modrinth.com/mod/sodium) —— 兼容性设计的主要参考对象
- [Anthropic Claude](https://www.anthropic.com/) —— 本项目全部代码的生成者
