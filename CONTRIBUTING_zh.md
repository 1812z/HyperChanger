# HyperChanger 贡献指南

HyperChanger 的贡献内容采用 Apache License 2.0。提交贡献即表示你同意其遵循 [LICENSE](LICENSE) 中的条款；历史 MIT 授权致敬说明见 [NOTICE](NOTICE)。

感谢你为 HyperChanger 做出贡献。请遵循以下约定，以便维护者能够高效审查并稳定发布。

## 开始前

1. 在 GitHub 上 Fork 本仓库。
2. 将你的 Fork 克隆到本地：

   ```bash
   git clone https://github.com/YOUR_USERNAME/HyperChanger.git
   cd HyperChanger
   ```

3. 为一个独立改动创建分支：

   ```bash
   git checkout -b feat/your-feature-name
   ```

## 开发环境

- Android Studio Narwhal（2025.1）或更高版本
- JDK 17+
- Android SDK 37
- 适用时，准备兼容的真机或模拟器

在 Android Studio 中打开项目，等待 Gradle 同步完成，然后执行以下命令验证改动：

```bash
./gradlew assembleDebug
```

## 修改规范

- 每个提交只包含一个逻辑明确的改动。
- 使用简洁的英文提交信息，例如：

  ```text
  feat: add lock screen shortcut option
  fix: avoid System UI restart loop
  docs: clarify LSPosed setup
  ```

- 遵循项目已有的 Kotlin 与 Java 代码风格；项目使用 Kotlin 官方代码风格。
- 除非改动明确更新支持范围，否则请保持与声明的最低 SDK 兼容。
- 不要在 Pull Request 中提交 APK、签名文件、生成的构建目录、设备日志或个人配置文件。

## 提交 Pull Request

1. 构建分支，并在所有相关作用域上测试。
2. 将分支推送到你的 Fork：

   ```bash
   git push origin feat/your-feature-name
   ```

3. 向本仓库的 `main` 分支发起 Pull Request。
4. 说明改动内容、测试方式和兼容性影响。界面改动请附上截图；与 Hook 相关的修复请附上必要的 Logcat 输出。

对于较大的行为调整、新的 Hook 目标或 API 变更，请先创建 Issue 讨论方案。

## 反馈问题

提交 Bug 报告时，请尽量提供：

- 设备型号、Android 版本和 HyperOS 版本
- HyperChanger 与 LSPosed 的版本
- 已启用的模块作用域和相关设置
- 清晰的复现步骤
- 预期行为与实际行为
- 已移除敏感信息的相关 Logcat 输出

## 行为准则

请保持尊重、建设性且聚焦项目的沟通方式。
