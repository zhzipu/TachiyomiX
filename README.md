# TachiyomiX

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84?logo=android\&logoColor=white)

![Language](https://img.shields.io/badge/language-Kotlin-7f52ff?logo=kotlin\&logoColor=white)



![License](https://img.shields.io/badge/license-Apache--2.0-blue)

一个基于 **TachiyomiSY**（→ Mihon → Tachiyomi）二次开发的 Android 漫画阅读器。

除了上游自带的在线阅读、下载、书架、追踪、备份等能力外，TachiyomiX 主要围绕四件事做了扩展：

> **① 让「自己的服务器」成为一个可读可写的图源**　**② 给阅读器加本地 AI 超分与立体场景**

完整功能见下方「[功能](#功能)」，与上游的逐项差异见「[与 Tachiyomi（官方 Mihon 系）的区别](#与-tachiyomi官方-mihon-系的区别)」。

---

## 功能

### 阅读

- **在线阅读**：从扩展图源在线追更，边下边看；**本地阅读**：打开已下载内容或本机存放的漫画。
- **六种阅读模式**：默认、左到右、右到左、纵向分页、Webtoon、连续纵向；支持双页显示与拆分、裁边、页面旋转、纵横缩放、缩放起始倍率。
- **阅读器外观**：明暗主题、页面切换动画、翻页闪光（可选颜色、时长与间隔）、屏幕常亮、显示页码与系统时间、刘海屏适配、全屏。
- **交互**：多种点按导航方式，其中包含 **9 宫格自定义点按**——9 个区域可分别指定为菜单 / 上一话 / 下一话 / 无；此外支持音量键翻页、长按操作、点按区域反转、双击缩放、底栏按钮自定义。
- **阅读行为**：自动跳过已读 / 被过滤 / 重复章节、章节切换过渡、页面预加载条数与图片缓存大小可调、阅读进度自动记录并从「历史」继续。
- **图像增强（TachiyomiX 新增）**：本地 AI 超分，见下方专节。

### 图源与扩展

- **内置图源**：无需安装即可使用的在线图源，以及把本机文件夹当作图源的本地图源。
- **扩展图源**：通过扩展仓库安装第三方图源；支持自定义图源仓库地址、批量信任 / 撤销信任、指定安装器（系统 / Shizuku / InstallerX）。
- **图源管理**：启用 / 禁用、隐藏、语言与 NSFW 过滤、图源迁移（把整本漫画从图源 A 迁到图源 B）、屏蔽列表、合并图源。
- **列表体验（TachiyomiX 新增）**：图源 / 扩展列表的语言标签栏多选筛选、拼音首字母索引栏（汉字取拼音首字母）。
- **网络图源（TachiyomiX 新增）**：WebDAV / FTP 上的自有漫画库，可读可写，见下方专节。

### 书库

- **分类**：多分类归属、拖动排序、分类内批量操作；内置「下载」分类（TachiyomiX 新增，见下方专节）。
- **书架**：列表 / 网格视图、多种排序、封面自定义、拖动排序、搜索（支持排除词与引号精确匹配等语法）、筛选（未读 / 已读 / 已下载 / 已追踪 / 未追踪）、动态分类、同一漫画多图源合并、阅读进度与未读数提示。
- **自动更新**：按间隔定时检查新章节，可限定网络类型、仅在充电时、按分类 / 分组执行，支持智能更新（跳过长期未更新的漫画）；可显示角标并发送通知。

### 下载与上传

- **下载**：章节下载队列、下载目录自定义、每本漫画单独建文件夹、自动下载新章节（可仅下未读）、删除已读章节 / 保留书签章节 / 排除指定分类、并发下载数（图源数与页数）、章节命名可附加 URL 哈希、失效下载缓存重建。
- **上传（TachiyomiX 新增）**：把已下载章节传回自己的服务器，含队列界面与自动上传，见下方专节。
- **进度可视化（TachiyomiX 新增）**：书架上直接显示下载 / 上传的页数与章节进度条，顶栏提供继续 / 暂停。

### 追踪

- 支持 MyAnimeList、AniList、Kitsu、MangaUpdates、Shikimori、Bangumi、Hikka、MangaBaka、MDList 以及自架服务 Kavita、Komga、Suwayomi。
- 追踪状态可批量修改、与本地阅读进度双向同步、在书架中按追踪状态筛选。

### 备份与同步

- **本地备份 / 恢复**：导出书库、分类、章节、阅读进度、追踪记录与设置，支持定时自动备份、创建为 `.tachibk` 文件并可直接分享。
- **云同步**：把书库数据同步到远端服务，支持定时同步与手动触发。
- **阿里云盘（TachiyomiX 新增）**：扫码登录后把备份同步到阿里云盘。

### 隐私与安全

- **应用锁**：生物识别 / 设备凭据解锁，可限定生效的时间段与星期、设置退出后的锁定延迟。
- **安全屏幕**：禁止截屏 / 最近任务预览，通知内容可隐藏。
- **数据库加密**：SQLCipher 加密书库数据库（AES-256 等）；下载内容与 CBZ 可单独加密。
- **应用伪装（TachiyomiX 新增）**：桌面图标与名称可切换为计算器 / 记事本 / 学习，见下方专节。

### 网络

- DNS over HTTPS（多家服务商可选）、User-Agent 自定义、手动 HTTP 代理。
- **内置 Clash（TachiyomiX 新增）**：应用内 mihomo 内核，见下方专节。

### 界面

- Material 3 主题、浅色 / 深色 / 跟随系统、纯黑模式（AMOLED）、动态取色（Monet，设备支持时可选）、多套预设配色。
- 底部导航可隐藏「更新」「历史」标签页、切换底栏文字显示；平板双栏布局；日期格式与相对时间可调。
- 多语言（含简体中文、繁體中文、English 等）。

---

## 与 Tachiyomi（官方 Mihon 系）的区别

### 总览

| 能力   | 官方 Tachiyomi / Mihon | TachiyomiX                                                                          |
| ---- | -------------------- | ----------------------------------------------------------------------------------- |
| 图源来源 | 在线图源 + 本地已下载内容       | 增加**网络图源**：把 WebDAV / FTP 服务器上的漫画库直接作为图源                                            |
| 数据流向 | 单向（服务器 → 本地）         | **双向**：下载完可**上传**回自己的服务器，书架里直接管理上传队列                                                |
| 书架分类 | 用户自建分类               | 增加**内置「下载」分类**，自动收纳已下载漫画，并在书架上显示下载 / 上传进度条                                          |
| 图像处理 | 无                    | **本地 AI 超分**（waifu2x / Real-CUGAN / Real-ESRGAN / Anime4K），支持 Vulkan GPU 与骁龙 NPU 后端 |
| 模型管理 | —                    | **外置模型包**：模型以独立 APK 分发，宿主不含模型、不随宿主升级                                                |
| 阅读呈现 | 2D 分页 / 滚动           | 增加**立体空间场景（Spatial depth）**：单页深度估计 + 陀螺仪视差                                          |
| 网络   | 手动 HTTP 代理 / DoH     | 增加**内置 Clash（mihomo）内核**，节点选择、延迟测试、订阅刷新                                             |
| 桌面入口 | 固定图标                 | **应用伪装**：桌面图标 / 名称可切换为计算器、记事本、学习                                                    |
| 备份同步 | 本地 / 部分云盘            | 增加**阿里云盘**同步（扫码登录）                                                                  |
| 检索体验 | 搜索                   | 图源 / 扩展列表**语言标签栏**多选筛选、**拼音首字母索引栏**、**搜索历史**                                        |
| 阅读手势 | 预设点按区域               | **九宫格自定义点按导航**                                                                      |
| 应用更新 | 内置更新器                | **移除内置更新器**（`INCLUDE_UPDATER = false`），自行分发                                         |
| 下载兼容 | 压缩包 / 图片目录           | **网络图源侧已移除压缩包兼容**，一话 = 一个图片目录                                                       |

> 基线版本：`UPSTREAM_VERSION = 0.20.1`，包名 `com.tachiyomi.x`，`minSdk 26`（Android 8.0）。

---

## 1. 网络图源（WebDAV / FTP）

上游的图源是「别人网站上的内容」，TachiyomiX 增加了一个 **属于你自己的图源**：把 WebDAV 或 FTP 服务器上的一块目录当作漫画库，像普通图源一样浏览、搜索、在线阅读，并且**可以写入**。

- **协议**：WebDAV 与 FTP。两者共享同一套目录约定与读写逻辑，只有传输层不同（`RemoteFileSystem` 抽象）；协议由 URL 的 scheme 决定，未写时才用下拉框补默认值。
- **只填一个「服务器地址」**：完整 URL，scheme 与端口都写在 URL 里，例如  
  `https://example.com/remote.php/dav/files/user`、`http://192.168.1.10:5005`、`ftp://192.168.1.10:2121`。  
  目录会创建在该 URL 之下，已有路径段会被保留。
- **目录约定**（根目录名固定为 `TachiyomiX manga`）：
  ```text
  TachiyomiX manga/
  ├── config.json              # 库索引：{"mangas":[{"name","folder"}]}，唯一真相
  ├── a1b2c3/                  # 随机 6 位目录 = 一本漫画
  │   ├── config.json          # 漫画元信息 + 章节列表
  │   ├── cover.png
  │   ├── No.0001/001.jpg      # 一章 = 一个图片目录
  │   └── No.0002/001.jpg
  └── ...
  ```
  不在 `config.json` 索引里的目录不会被识别，因此可以直接手工整理服务器上的文件夹。
- **测试连接**：创建库根（幂等）+ 列一次目录 + 校验写权限，会把「地址/凭据错误」和「连不上」分开报出。
- **代理绕过**：默认开启，直连服务器、忽略内置 Clash 与手动 HTTP 代理（局域网 NAS 走远程代理节点会报 502）。
- 在线阅读时远端图片流直接作为响应体返回，**不落本地缓存**。

## 2. 上传：把下载的内容送回去

- **上传队列**：下载完成的章节可以加入上传队列，有独立的队列界面（等待确认 / 排队 / 上传中 / 完成 / 失败 / 已取消）。
- **自动上传**：开关打开后，书架「下载」分类里的漫画**一话下完就传**（下载与上传并行，不必等整本下完）。
- **断点与恢复**：队列会落盘，进程重启后可继续；暂停不会打断当前正在上传的一话。
- **同名合并**：服务器上已存在同名漫画时会询问是「合并（只补缺失章节）」还是「新建文件夹」。该询问带超时兜底，避免单消费者队列被永久堵死。
- **上传接口唯一**：所有创建动作都走 `NetworkLibraryClient`，保证目录、`config.json`、索引三者一致。

## 3. 书架里的「下载」分类

不是虚拟视图，而是**真实的数据库分类**：

- 下载漫画时自动进入、删除下载时自动移出；可以参与拖动排序，但**不可重命名 / 删除**，被删掉会自动重建。
- 在书架上为其中的漫画显示 **4 条进度条**：下载页数 → 下载章节 → 上传页数 → 上传章节（下载组与上传组各用一种颜色区分归属）。
- 顶栏左上角提供**继续 / 暂停**，可分别针对下载、上传，或两者同时；选择上传时还可以选择「继续之前的任务」或「上传全部已下载章节」。
- 从该分类删除时使用独立的二次确认弹窗。
- 备份与同步会正确处理这个分类（不会被当成普通分类导出或误删）。

## 4. 本地 AI 超分

阅读器内置推理链路（ncnn + Vulkan 计算着色器），对页面做实时放大与降噪。

- **模型**：waifu2x、Real-CUGAN、Real-ESRGAN，以及 Anime4K（GLSL / ACNet）。模型清单由已安装的模型包决定。
- **处理后端**：
  - `Vulkan` — 通用 GPU 通路，可调 GPU 性能模式、tile 尺寸、FP16/FP32/INT8/BF16 精度；
  - `Qualcomm NPU` — 走骁龙 HTP，仅在设备支持且模型声明支持 NPU 时可选；
  - `Noval Ai` — 预留入口，尚未实现。
- **可调项**：倍率、降噪档位、风格（anime / photo）、精度、处理分辨率上限、超过指定分辨率时跳过处理、预加载页数（也支持「整章增强」）、显示处理状态。
- 增强结果有独立缓存，并按「成品」在画面上叠加 Super-Resolution 水印。
- 增强设置同时可从阅读器底栏对话框与自定义滤镜标签页进入，两处共用同一份实现。

## 5. 外置模型包

**宿主不内置任何模型**，模型以独立 APK 的形式安装到设备上：

- 模型包通过系统安装器安装，宿主不参与安装流程；模型包内以 `modelpack.json` 描述符声明模型名、倍率、降噪档位、精度、支持的后端、模型文件路径等。
- 装一个显示一个，卸载即消失；**新增模型不需要更新宿主 APK**。
- 设置路径：`设置 → 高级 → 模型包`。

模型包的完整源码（每个模型一个独立仓库，可直接构建出对应 APK）：

| 模型 | 仓库 |
| --- | --- |
| waifu2x 基础 | [TachiyomiX-ModelPack-waifu2x](https://github.com/zhzipu/TachiyomiX-ModelPack-waifu2x) |
| waifu2x Upconv7 | [TachiyomiX-ModelPack-waifu2x-upconv7](https://github.com/zhzipu/TachiyomiX-ModelPack-waifu2x-upconv7) |
| Real-CUGAN | [TachiyomiX-ModelPack-realcugan](https://github.com/zhzipu/TachiyomiX-ModelPack-realcugan) |
| Real-CUGAN Pro | [TachiyomiX-ModelPack-realcugan-pro](https://github.com/zhzipu/TachiyomiX-ModelPack-realcugan-pro) |
| Real-CUGAN Nose | [TachiyomiX-ModelPack-realcugan-nose](https://github.com/zhzipu/TachiyomiX-ModelPack-realcugan-nose) |
| Real-ESRGAN | [TachiyomiX-ModelPack-realesrgan](https://github.com/zhzipu/TachiyomiX-ModelPack-realesrgan) |
| SPAN NomosUni | [TachiyomiX-ModelPack-span-nomosuni](https://github.com/zhzipu/TachiyomiX-ModelPack-span-nomosuni) |
| sudo UltraCompact | [TachiyomiX-ModelPack-sudo-ultracompact](https://github.com/zhzipu/TachiyomiX-ModelPack-sudo-ultracompact) |
| ACNet | [TachiyomiX-ModelPack-acnet](https://github.com/zhzipu/TachiyomiX-ModelPack-acnet) |
| Anime4K | [TachiyomiX-ModelPack-anime4k](https://github.com/zhzipu/TachiyomiX-ModelPack-anime4k) |

## 6. 立体空间场景（Spatial depth）

对单页做深度估计，把平面漫画变成可用陀螺仪与手势观察的立体场景：

- 深度模型为 **Depth Anything V3**，首次使用需下载（约 101 MB）并针对当前骁龙设备编译，之后无需重复。
- 支持陀螺仪视差（灵敏度可调、可重新居中）、旋转锚点、旋转角度、深度强度、双指缩放。
- 硬件要求较高：需要 Android 12+ 与受支持的骁龙 HTP；目前**仅支持单页分页阅读**。

## 7. 内置 Clash（mihomo）代理

- 通过 `libmihomo-android` 在应用内加载 mihomo 内核，仅监听本机 `127.0.0.1` 的混合端口，**不使用系统 VPN**。
- 支持订阅地址、手写配置、节点列表选择、批量延迟测试、刷新订阅。
- 与上游的手动 HTTP 代理互斥（启用其一会自动关闭另一个），切换后会清理连接池。

## 8. 应用伪装

- 通过 `activity-alias` 切换**桌面图标与名称**：默认图标 / 计算器 / 记事本 / 学习。
- 只影响桌面入口，应用内 Activity、包名与实际应用名不变。

## 9. 其他改动

- **阿里云盘同步**：扫码登录，配合上游的同步能力把书库数据备份到阿里云盘。
- **图源 / 扩展列表增强**：语言标签栏（多选筛选）、拼音首字母索引栏（汉字取拼音首字母，支持中英混排）。
- **搜索历史**：图源内搜索与全局搜索都会记录历史，可一键回填或清空。
- **阅读器九宫格自定义点按**：9 个区域可分别指定为菜单 / 上一话 / 下一话 / 无。
- **启动引言**：冷启动时短暂显示一句随机名言。
- **移除内置更新器**，改由自行分发安装包。

---

## 继承自 TachiyomiSY 的差异

相对官方 Mihon，TachiyomiX 同样保留了 TachiyomiSY 的既有扩展，包括合并图源；自定义图源仓库（扩展商店）；数据节省（带宽英雄）；数据库加密；下载清理；语言标签栏之外的各类书架 / 浏览增强等。

这部分不在本文档的详细说明范围内，可参考 TachiyomiSY 的文档。

---

## 从源码构建

要求：Android SDK（`compileSdk 37`）、NDK `26.1.10909125`、CMake `3.22.1`，以及 **ncnn Android Vulkan SDK**（AI 超分依赖）。

ncnn SDK 路径按以下优先级取：

1. Gradle 属性 `-PncnnSdkDir=...`
2. `local.properties` 中的 `ncnn.sdk.dir=...`
3. 环境变量 `NCNN_SDK_DIR`
4. 仓库内 `third_party/ncnn-<version>-android-vulkan/`

可选：配置 Qualcomm AI Runtime（QNN）SDK 以启用 NPU 通路（`qnn.sdk.dir` / `QNN_SDK_ROOT`，未配置时原生侧以 `MIHON_ENABLE_QNN=0` 编译）。

```bash
./gradlew :app:assembleRelease
```

默认按 ABI 拆包（armeabi-v7a / arm64-v8a / x86 / x86_64 + 通用包），产物名为 `TachiyomiX-<version>-<abi>.apk`。

模型包源码不在本仓库内：每个模型都是独立工程（见「[外置模型包](#5-外置模型包)」的仓库列表），在各自仓库目录下执行 `./gradlew assembleRelease` 即可产出该模型的 APK。

---

## 已知限制

- **网络图源不兼容压缩包**：一话必须是章节目录下的图片文件。上传端若本地是 CBZ，会先解页再逐文件上传，因此服务器上永远是图片目录。
- **立体空间场景**仅支持单页分页阅读，且需要 Android 12+ 与骁龙 HTP。
- **AI 超分**依赖庞大的原生库与模型文件，低端设备体验受限；模型须另行安装模型包。
- 内置更新器已移除，请自行跟进版本。
- 首次使用立体场景需要下载模型并等待首次编译。

---

## 致谢与许可

- 本项目基于 [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY) 开发，遵循 **Apache License 2.0**。
- 用到的第三方组件包括 ncnn、Anime4K、Real-CUGAN / Real-ESRGAN / waifu2x 模型、Depth Anything V3、libmihomo（Clash）、Apache Commons Net、OkHttp、jsoup、pinyin4j 等，版权归各自作者所有。
