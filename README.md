# Ensemble Reads

一款 Android 多角色朗读阅读器：导入电子书，用 **DeepSeek 解析角色 → Edge TTS 多音色合成**，实现「不同角色不同声音」的听书体验，同时保留完整的阅读功能。

苹果风 Compose UI · 纯 Kotlin · Room 本地库。

## 核心特性

### 📖 阅读
- 导入格式：**TXT / EPUB / PDF（文本层）/ Markdown**
- 段落渲染、点击段落从此处播放、当前段高亮跟读
- 排版设置：字号 / 行距 / 字体（系统·衬线·等宽）
- 多阅读主题：跟随系统 / 亮 / 暗 / 羊皮纸 / 墨绿（沉浸阅读配色独立于 App 主题）
- 阅读器内目录抽屉、书内全文搜索
- 书签 / 高亮（多色）/ 笔记

### 📚 书架
- 网格书架 + 整书朗读进度（读到第 X 章 · 已听 N%）
- 长按卡片：重命名 / 删除（级联清理正文·音频·角色）
- 排序（最近 / 标题 / 进度）、书名搜索

### 🔊 多角色朗读（核心）
- **DeepSeek** 按语义切段并识别说话人（旁白 / 角色），长文本分块 + 跨块说话人延续
- **Edge TTS** 按角色自动分配或手动指定音色（云扬 / 云希 / 云健 / 晓晓…），全量音色列表可搜索、可试听
- **系统 TTS 离线兜底**：断网 / 接口异常时自动降级，保证仍可朗读
- 边合成边播（首段就绪即出声）、失败重试、断网恢复自动补合成
- 短段合并：长章节被切碎时自动合并，避免 1000+ 段 / 时长失真
- 倍速 0.5–2x、睡眠定时（15/30/60 分钟 / 播完本章）、章内进度条 seek

### 🎧 播放体验
- MediaSession 锁屏媒体卡 + 蓝牙耳机媒体键 + 系统媒体通知
- 音频焦点处理（来电自动暂停、挂断恢复）、拔耳机自动暂停
- 前台服务 + 朗读进度持久化（书架「继续阅读」）

### ⚙️ 设置
- DeepSeek API Key（Android Keystore 加密存储）、缓存上限、默认倍速、解析分块大小
- 主题 / 排版设置、**备份导出 / 导入**（书·章·角色·标注·设置）、清空缓存
- 累计收听时长统计
- 调试：全局悬浮「后台请求日志」面板

## 快速开始

### 构建
```bash
./gradlew assembleDebug
```
产物：`app/build/outputs/apk/debug/app-debug.apk`

### 配置 DeepSeek Key
> 项目**不内置任何 API Key**。首次使用请在「设置 → DeepSeek API Key」填入你的 Key（火山方舟 / DeepSeek 兼容 Anthropic 协议端点）。

未配置 Key 时，阅读器降级为纯文本（并提示去设置）；配置后自动启用角色解析与 Edge TTS 合成。Key 仅加密存储在本机。

### 导入书籍
书架右上角 `+` 选择文件（TXT / EPUB / PDF / MD），或复制到手机后通过文件选择器导入。

## 架构

```
app/src/main/java/com/ensemblereads/app/
├── book/         TXT / EPUB / PDF / Markdown 解析
├── data/         Room（book/chapter/segment/role/parse_cache/annotation/reading_stats/settings）
│   ├── db/       Entities · DAO · AppDatabase（v4，含迁移）
│   ├── repo/     Repo + 手动 DI AppContainer
│   └── backup/   备份导出/导入
├── tts/          DeepSeekClient（角色解析）· EdgeTtsClient（合成）· SystemTtsEngine（兜底）
│                 · RoleAllocator · FallbackTtsEngine（粘性降级）
├── player/       AudioPlaybackService（MediaSession + 焦点 + 通知）
│                 · PlaybackController · ChapterSynthesizer（边合成边播/预取/淘汰）
│                 · SegmentMerger · CachePolicy · ConnectivityRetry
└── ui/           Compose：书架 / 目录 / 阅读器 / 角色 / 设置 / 搜索 / 请求日志面板
```

关键机制：
- **边合成边播**：每段合成完即喂给 ExoPlayer，开书后很快出声，不必等整章合成完；播放列表临时耗尽不杀服务（`synthesisDone` 门控）
- **角色分配**：`RoleAllocator` 按性别/年龄/语气自动选音色，用户手动覆盖优先
- **段合并**：DeepSeek 逐句切段会爆量（长章 1000+ 段），`SegmentMerger` 合并相邻同角色短段到 ≤400
- **进度**：书架百分比为整书口径（已完成段 / 全书段数）

## 依赖

Compose BOM · Room · Media3（exoplayer/session）· OkHttp · Jsoup · pdfbox-android

## 测试

单元测试位于 `app/src/test/`（解析器 / 段合并 / 角色分配 / TTS 客户端 / 进度计算等），由维护者本地执行：

```bash
./gradlew testDebugUnitTest
```

## 许可证

Apache License 2.0
