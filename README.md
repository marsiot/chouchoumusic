# 臭臭音乐 · chouchou music

<p align="center">
  <img src="chouchou.jpg" width="160" alt="chouchou music"/>
</p>

<p align="center">
  <b>一个由 AI 帮你打标签的本地 Android 音乐播放器</b><br/>
  <i>把音乐按"场景"组织——跑步、冥想、深夜、专注……让歌曲自己找到它该在的地方</i>
</p>

---

## 为什么做这个

主流播放器要么逼你联网订阅、要么按"专辑/艺人"硬分类，对很多人来说都没什么用。**我只是想要一个能放本地 mp3 的播放器**，并且希望"想跑步"的时候点一下，列表里全是节奏 130+ 的歌；"想冥想"的时候点一下，列表里全是慢板纯乐。

打标签是个麻烦事——除非交给 AI。

## 核心特性

### AI 智能场景分类

- 支持 **6 家国内 LLM 服务商**：智谱 GLM-4-Flash（免费）、Moonshot、DeepSeek、通义千问、硅基流动、OpenRouter，外加自定义 OpenAI 兼容接口
- AI 根据歌名 + 歌手判断是否适合某个场景，分类结果以 **sidecar `.lrc`** 形式保存在歌曲旁边——换设备只要把音乐目录拷过去，标签就跟着走
- 多家 provider 配置后形成**故障转移池**，一家失败自动换下一家
- 已分类的歌**不会重复调 API**，省 token
- **场景描述**也喂给 AI 做先验提示（`跑步 = BPM 130+，有动感`），分类更准

### 每个场景都是一份"歌单"

- 内置 `跑步` `冥想` 两个开箱即用的场景，**也可以无限增加自定义场景**（午休、做饭、夜跑、写代码……）
- 每个场景可设**歌曲数量上限**（默认 10 首，可拨到"不限"）——AI 扫够目标数量就停，不浪费 token
- **「更换曲目」**按钮：让 AI 从候选池里换一批新歌进来，旧的进入 evicted 集合避免被立即重选
- 内置场景也可以删除/改名，所有场景平等对待

### 手工修正永远高于 AI

底部菜单里点一下场景标签就能立即生效。**任何被你手工动过的歌都会被锁定**，后续 AI 重扫一律跳过——你的偏好永远是权威。

### 网易云歌词自动匹配

- 自动从网易云抓 LRC，存进 sidecar
- 匹配错了？歌词页右上角有个🔍按钮，手工填正确的歌名/歌手重新搜
- 歌词在迷你播放器和详情页都能同步滚动

### 后台播放

- 前台 Service + 通知，按返回键不会停音乐——回到桌面 / 切别的 App 时音乐继续
- 退出 App 时保存当前曲目和播放位置；下次进 App 自动恢复到上次的位置（暂停状态）

### 文件管理

- 在 App 内**重命名、移动、复制、删除**音乐文件（sidecar `.lrc` 会跟着一起搬）
- 可以建/删文件夹
- 拖拽歌曲调整在文件夹/场景内的顺序——顺序持久化保存

### UI 细节

- 黑底 + 暖沙金强调色，深色友好
- 正在播放的行有**动态均衡器**（3 根金色小柱子随节拍起伏）
- 把当前的播放来源（文件夹/场景）高亮显示在主列表上
- 迷你播放器：歌名 + 歌手 + 实时一句歌词 + 可拖动进度条 + 时间标签
- 选择多个歌曲、批量删除
- 长按拖拽改顺序

## 技术栈

| 模块 | 选型 |
|---|---|
| 语言 | Java 8 |
| minSdk / targetSdk | 24 / 34 |
| 构建 | Gradle 8.2 / AGP 8.2.2 |
| UI | AppCompat 1.6 + Material 1.11 + RecyclerView 1.3 |
| 播放 | `android.media.MediaPlayer` |
| AI HTTP | `HttpURLConnection` + 单线程 ExecutorService |
| 数据 | SharedPreferences + 自定义 sidecar `.lrc` 格式 + JSON classCache |
| 不用 | Kotlin、Room、Retrofit、Coroutines、Hilt、Compose、ExoPlayer——全是 Android 原生 API |

## Sidecar `.lrc` 格式

每首歌旁边一个同名 `.lrc` 文件，开头一行自定义元数据，后面是普通时间戳歌词：

```
[chouchou:scenes=跑步,健身;classified=1;manual=1;lyrics=1]
[ti:歌名]
[ar:歌手]
[00:12.34]这是第一句歌词
[00:18.50]这是第二句
```

含义：
- `scenes=` 当前生效的场景标签（逗号分隔）
- `classified=1` AI 已经评判过这首歌
- `manual=1` 用户手工修正过，AI 不许再动
- `lyrics=1` 歌词已尝试匹配（避免重复抓取）

## 编译 & 运行

```bash
# 一键编译 + 装到连接的手机 + 启动
./build_and_run.bat

# 仅编译
gradle assembleDebug

# 调试崩溃（持续抓 logcat 写入 debug-logs/）
./debug_crash.bat
```

具体脚本说明见 [SCRIPTS.md](SCRIPTS.md)。

## 配置 AI

进 App → 右上角设置 → 启用至少一家 LLM → 填 API Key → 保存。

各家 API Key 申请入口（按价格友好度从上到下）：

| 服务商 | 免费额度 | Key 申请 |
|---|---|---|
| 智谱 GLM-4-Flash | **完全免费** | https://open.bigmodel.cn/usercenter/proj-mgmt/apikeys |
| 通义千问 Qwen-Turbo | 有免费额度 | https://dashscope.console.aliyun.com/apiKey |
| 硅基流动 | 有免费模型 | https://cloud.siliconflow.cn/account/ak |
| OpenRouter | 聚合（含免费 Llama） | https://openrouter.ai/keys |
| DeepSeek | 便宜（不免费） | https://platform.deepseek.com/api_keys |
| Moonshot Kimi | 付费 | https://platform.moonshot.cn/console/api-keys |

新手推荐直接配智谱，零成本可用。

## 项目状态

这是一个**个人使用的玩具项目**——为了一个用得顺手的本地播放器写的，**不商用、不上 Play Store、不接受 issue 跟踪**。如果你也想用，clone 下来改包名/图标自己编一个就行。代码逻辑都集中在 `MainActivity.java` 里，没有过度抽象，方便随手改。

## License

随你便用，自留个种就行。
