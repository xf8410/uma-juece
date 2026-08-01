# 仓库用途审计（新增，不覆盖旧 README）

## 实际用途

Android 赛马娘育成辅助浮窗应用。代码目录、Manifest、资源和 Gradle 文件证明它是 App 工程；它消费本机插件提供的数据并负责展示/本地评估，不是游戏数据采集 SO 本体。

## 关键目录

- `app/src/main/java/`：Activity、浮窗服务、评估与通信代码。
- `app/src/main/res/`：Android UI/资源。
- `app/src/main/AndroidManifest.xml`：组件和权限。
- `.github/workflows/`：构建自动化。

## 关联性

- 上游运行时数据：hlpatch 本地 HTTP/SO。
- 静态数据：可能消费 uma-data 发布数据。
- 算法研究：与 uma-train/其他决策实验有关，但不能把研究仓库的目标当成本 App 已集成事实。

## 状态

- **实际**：存在完整 Android 工程骨架及实现文件。
- **未完成/待实机确认**：README 中版本兼容、推送+轮询、所有拉面杯字段和 AI 推荐准确性，不能仅凭目录认定全部完成；需以当前构建和实机验收为准。
- **猜测**：项目整体“完成”没有可验证定义；当前更准确称为持续开发的可构建应用。

旧 README 的版本和功能结论保留为历史陈述，后续纠正只追加证据。