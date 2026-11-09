# OpenCode Mobile

Android 端 AI 编程助手的个人学习项目，使用 Java 开发界面，探索在 Android 上集成开源 OpenCode SDK/Core 与 Bun 运行时。

## 功能

- 配置兼容 OpenAI 的模型接口，管理多个会话并展示 Markdown 消息。
- 通过 Android Storage Access Framework 授权项目目录，在应用私有目录中镜像文件并同步变更。
- 使用 Android Keystore 保护模型配置；本地 Core 限制非文件类工具调用。

目前已在 Android 15 / arm64-v8a 上验证运行时启动、Core 状态与 SDK 会话创建；使用真实模型完成一次对话仍需进一步验证。

## 目录说明

- `app/src/main/java/`：Android 应用 Java 源码。
- `app/src/main/res/`：Android 界面资源。
- `app/src/main/assets/licenses/`：OpenCode 和 Bun 的许可说明。
- `reference/android-host.ts`：本地 SDK 宿主入口的参考代码。
- `upstream-source.json`：上游版本与运行时信息。

## 当前限制

本仓库是**源码展示版本，不是完整构建包**。为了避免提交大型运行文件和完整的第三方源码，未包含：

- Android 版 Bun 运行库 `app/src/main/jniLibs/arm64-v8a/libopencode-bun.so`。
- 由 OpenCode 源码及其 Android 适配生成的 `app/src/main/assets/opencode-host.js`。
- 含本项目 Android 适配改动的完整 OpenCode 上游源码。

因此，仅下载此仓库无法直接构建完整可运行版本；`reference/android-host.ts` 也不足以单独重建宿主文件。后续需要补充完整且可复现的依赖获取和构建流程。

## 上游与许可

OpenCode 上游为 [anomalyco/opencode](https://github.com/anomalyco/opencode)，本项目参考版本与提交号见 `upstream-source.json`。上游采用 MIT 许可，许可文本见 `app/src/main/assets/licenses/OPENCODE-LICENSE`；Bun 相关许可见同目录的 `BUN-LICENSE.md`。
