# V File Manager v0.3.0

原生 Kotlin Android 文件管理器，Android API 26+，使用系统 SAF 文件夹授权。

## 新版
- V Applications 深蓝/青蓝配色，与 V Hub、V Network Lab 一致
- 列表/网格视图切换，图片缩略图（限制大小，3 线程加载、LRU 缓存）
- 图片内置预览，UTF-8 文本/代码查看编辑，音视频通过系统应用打开
- 文件类型筛选：全部/图片/视频/音频/文档
- 收藏文件、文件夹和最近访问记录（本地存储，保留最近 30 条）
- 保留 v0.2.0 的多选、批量操作、分享、ZIP 压缩/解压等功能

## 构建
推送至 GitHub `main` 后 GitHub Actions 自动执行 `:app:assembleDebug`。在 Actions 的 Artifacts 下载 `V-File-Manager-v0.3.0-debug`。

## 限制
- SAF 不允许访问部分受保护的 Android 系统目录。
- 文件筛选与搜索只针对当前文件夹；不是全盘搜索。
- 内置文本编辑限 1 MB，默认 UTF-8；不包含编译器。
- 大型图片预览会占用内存，部分格式会使用系统应用打开。
- 当前缩略图按需异步加载，但大量文件的视图仍采用 LinearLayout/GridLayout，未实现 RecyclerView 虚拟列表。
- Debug APK 覆盖安装需要签名一致。

注意：本源码包已做静态检查，未在本环境完成 Android Gradle 编译，首次构建若失败请提供 Actions 日志。
