# Ensemble Reads — 苹果风格多角色有声小说阅读器 设计文档

**日期**：2026-09-11
**状态**：已批准（设计对话确认）
**项目位置**：`D:\cod\ensemble-reads`（全新独立 Android 项目）
**定位**：一个"看 + 多角色有声听"的小说阅读器。本地导入 TXT/EPUB，DeepSeek 自动分析角色并分配音色，Edge TTS 合成多角色有声朗读。Apple 官方设计语言风格，最终产出 APK。

## 背景

延续 legado-E（阅读 Sigma）PoC 验证成果：DeepSeek 角色解析 + 特征匹配音色 + 段落级情绪覆盖的流水线已跑通。本设计将其落地为一个独立、精简、苹果风格的新 App，不继承 legado 的复杂功能（书源/订阅/音视频播放等全部排除）。

## 功能需求

### 用户明确需求
1. **本地导入书籍**：导入 TXT/EPUB 到书架，进度保存，章节选择等阅读常用功能。
2. **从当前页开始听**：以阅读位置为起点开始朗读，播放支持倍速（0.5x~2x）。
3. **按章缓存音频**：段级边听边缓存、预取后续章节、缓存上限可配置、自动淘汰、批量缓存。
4. **每章可重新分配未分配角色**：DeepSeek 未识别/未分配的角色可手动指定音色。
5. **UI 采用苹果官方设计风格**（Apple HIG 语言），最终产出 Android APK。

### 补充功能（设计对话确认）
6. **朗读高亮跟读**：播放时当前段落高亮，文字与语音同步。
7. **完整播放控制**：暂停/继续、上一句/下一句、锁屏/后台播放（MediaSession）。
8. **睡眠定时**：定时停止朗读。
9. **DeepSeek API key 配置入口**：设置页输入，存本地。
10. **解析结果缓存**：同一章解析结果存本地，避免重复调用 DeepSeek。
11. **角色管理界面**：可给任意角色改音色（不止未分配）、合并同名角色。

## 技术栈

- Kotlin + Jetpack Compose（UI，苹果风格自绘）
- Room（本地数据库）
- Media3 / ExoPlayer（音频播放）
- OkHttp + Kotlin 协程（DeepSeek API、Edge TTS）
- epublib-core（EPUB 解析，Android 兼容，Maven 依赖）
- EncryptedSharedPreferences 或 Room（DeepSeek key 安全存储）

## 架构（包结构）

```
ensemble-reads/
├── app/
│   ├── data/                 # 数据层
│   │   ├── db/               # Room 实体/DAO/数据库
│   │   └── repo/             # BookRepo / ChapterRepo / CacheRepo
│   ├── book/                 # 书籍解析
│   │   ├── TxtParser.kt      # TXT 编码检测(GBK/UTF-8) + 章节切分
│   │   └── EpubParser.kt     # EPUB → 纯文本章节（epublib 取章节去标签）
│   ├── tts/                  # 朗读引擎（抽象接口 + 直连实现）
│   │   ├── TtsEngine.kt      # 接口
│   │   ├── DeepSeekClient.kt # 角色解析（移植 PoC）
│   │   ├── RoleAllocator.kt  # 角色分配（移植 PoC）
│   │   └── EdgeTtsClient.kt  # Edge TTS 合成（移植 PoC）
│   ├── player/               # 播放与缓存
│   │   ├── AudioPlaybackService.kt  # 前台服务(Media3)
│   │   ├── ChapterSynthesizer.kt    # 逐段合成+预取+批量缓存
│   │   └── PlaybackController.kt    # 高亮同步/倍速/睡眠定时
│   ├── ui/                   # Compose 苹果风格
│   │   ├── theme/            # iOS 观感主题（SF风格字体/大标题/圆角/深浅色）
│   │   ├── bookshelf/        # 书架
│   │   ├── reader/           # 阅读器（分页+高亮+播放条）
│   │   ├── roles/            # 角色管理
│   │   └── settings/         # 设置
│   └── di/                   # 依赖注入
```

## 数据模型（Room 表）

| 表 | 关键字段 | 用途 |
|---|---|---|
| `Book` | id、标题、文件路径、格式(TXT/EPUB)、封面路径、章节数、最后阅读章/段、创建时间 | 书架 |
| `Chapter` | id、bookId、索引、标题、内容(纯文本)、是否已缓存 | 章节 |
| `Role` | id、bookId、角色名、音色、pitch、rate | 用户手动分配的音色覆盖 |
| `Segment` | id、chapterId、段索引、speaker、文本、音频路径、时长、状态(待解析/解析中/合成中/就绪/失败)、缓存时间 | 段级缓存 |
| `ParseCache` | chapterId、分段 JSON | DeepSeek 解析结果缓存 |
| `Settings` | key、value | DeepSeek key、默认倍速、缓存上限等 |

## 朗读引擎接口（抽象层）

```kotlin
interface TtsEngine {
    suspend fun parseSegments(chapterId: Long, text: String): List<Segment>      // DeepSeek 角色解析
    suspend fun allocateVoices(segments: List<Segment>,
                               overrides: Map<String, Role>): Map<String, Voice> // 角色分配(自动+用户覆盖)
    suspend fun synthesize(text: String, voice: Voice): File                     // Edge TTS 合成
}
```

直连实现 `DeepSeekTtsEngine` 封装三个客户端（移植 PoC 的 deepseek_parser / role_allocator / tts_synth 逻辑）。将来切换情感 TTS（EmotiVoice / 云服务）只需新增 `TtsEngine` 实现，UI 与播放层不变。

角色特征 schema（DeepSeek 输出）：`speaker / text / gender(male|female|unknown) / age(少年|青年|中年|老年|未知) / tone(沉稳|温柔|活泼|冷酷|威严|凶狠|憨厚|俏皮|焦急|中性)`。

## 朗读工作流

```
点"从当前页开始听"
 → 当前章：Segment 表有就绪段? 有→直接播；无→
    ParseCache 命中? 有→复用；无→DeepSeek 解析(存 Segment+ParseCache)
 → allocateVoices：自动特征匹配 + Role 表用户覆盖
 → ChapterSynthesizer 逐段合成(Edge TTS) → 存音频文件 → 段状态就绪 → Media3 播放
    （播一段放一段 = 边听边缓存）
 → 预取队列：在缓存上限内继续合成后续章节
 → PlaybackController 按段索引回调 → 阅读器高亮跟读
 → 倍速：Media3 playbackParameters(0.5x~2x)
 → 睡眠定时：定时器到期暂停
```

## 缓存策略

- **缓存上限可配置**（默认 100 章，设置页自定义）。
- **自动淘汰**：新增缓存导致超上限时，自动删除"最早缓存"的章节及其段文件（按缓存时间淘汰）。
- **边听边缓存**：当前章逐段合成即播，不等待整章。
- **预取**：在缓存上限内，后台预合成当前章后续章节（数量受上限约束）。
- **批量缓存**：章节选择页支持多选章节手动批量合成（进入合成队列）。
- 缓存管理：设置页显示占用、支持一键清空。

## UI 设计（苹果风格）

四屏：**书架 / 阅读器 / 角色管理 / 设置**，Compose 实现 iOS 观感（大标题、TabBar 底部导航、圆角卡片、SF 风格字体、深浅色模式、模糊背景）。

- **书架**：大标题 + 分段控件(全部/未读完) + 封面圆角网格 + 导入按钮。
- **阅读器**：分页正文 + 顶部章节标题/返回 + 底部播放条(上一句/播放暂停/下一句/倍速) + 当前段高亮 + 角色配置入口。
- **角色管理**：章节角色列表(角色名/当前音色/次数)，未分配角色标⚠️，点击弹底部音色选择(7 音色 + pitch/rate 试听)，保存后重合成该章。
- **设置**：DeepSeek API key 输入(密文)、默认倍速、缓存上限、缓存管理(占用/清空)、预取章数。

## 错误处理

- **Edge TTS 失败**：单段重试 3 次（退避）→ 仍失败标记 FAILED 跳过该段，继续播放后续段。
- **DeepSeek 失败**：重试 1 次 → 失败提示"解析失败可重试"，该章保留可重新解析。
- **离线**：阅读（本地文件）正常；朗读提示需联网。
- **导入失败**：格式不支持/损坏 → 书架提示错误。
- **后台预取失败**：静默重试，不打断播放。

## 测试策略

| 层 | 内容 |
|---|---|
| 单元测试 | TxtParser（编码检测/章节切分）、EpubParser（EPUB→纯文本）、RoleAllocator（特征匹配/撞色降级/tone_params，移植 PoC 测试）、DeepSeek 响应容错解析、缓存淘汰逻辑 |
| 集成测试 | mock 网络的 TtsEngine 流程（解析→分配→合成顺序）、播放状态机 |
| 手动验证 | 真机：导入→阅读→朗读→高亮→倍速→边听边缓存→预取→批量缓存→角色重分配→超限淘汰 |

> 用户规则：测试代码由我编写，测试运行由用户执行。

## 非目标（明确不做）

- 不做书源/订阅/联网找书。
- 不做 EPUB 复杂排版渲染（统一纯文本章节化）。
- 不做音视频播放、TTS 之外的其他多媒体。
- 不做 Web 端、多平台；仅 Android APK。
- MVP 不做服务端中转（直连 + 抽象接口预留）。
