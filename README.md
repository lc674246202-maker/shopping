# ShoppingRecord Android

个人智能购物记录 App 的原生 Android 工程，严格以 `购物记录App_产品需求与技术设计_PRD_V1.0.md` 为开发基线。

## 当前阶段

- Kotlin 原生 Android，XML + 系统 View。
- 最低 Android 10（API 29）。
- Android 内置 `SQLiteOpenHelper`，已建立 PRD 规定的 5 张核心表及必要索引。
- 已实现确定性价格计算核心与 PRD 单价用例测试。
- 已建立“识别 / 记录 / 设置”三个主入口的原生页面骨架。
- 已接入 Android 系统图片选择器：支持 1—5 张图片、预览和逐张移除，不申请完整相册权限。
- 已建立本地识别任务草稿，以及用户最终确认订单的 SQLite 事务保存层和强重复检查。
- 已实现识别结果在当前页直接编辑：订单及商品字段自动回填，缺失项保持空白，包装单价和标准单价由本地规则自动计算。
- 已接通 DeepSeek `deepseek-flash` 视觉请求、严格 JSON 解析、图片大小限制、错误分类及任务重试/取消/中断恢复。
- 已使用 Android Keystore + AES/GCM 加密保存个人 API Key，支持保存、测试连接、更新和清除。
- 已实现按订单/按商品记录列表、本地搜索、平台与日期筛选、订单详情、记录编辑删除及双模式历史价格。
- 已实现 UTF-8 CSV 导出、带 SHA-256 校验的完整 ZIP 迁移包、关系校验、导入预览和空库事务恢复。
- 已关闭系统自动云备份和设备迁移数据抽取路径。

本地源码阶段已经形成“选图 → DeepSeek识别 → 当前页人工确认 → SQLite保存 → 记录/历史查询 → 导出迁移”的完整调用链。尚未执行云端 Kotlin/Android 资源真实编译、模拟器运行、真实 API Key 与订单截图验证，因此目前不能描述为已交付可运行 APK。

## 使用流程

1. 在“设置”中填写个人 DeepSeek API Key，可先测试连接。
2. 在“识别”中选择 1—5 张订单截图，确认联网与图片隐私提示后开始识别。
3. 已识别字段会自动填入当前页；未识别字段保持空白。修改后的人工结果优先。
4. 包装单价和标准单价由本地精确规则计算，确认后保存到 SQLite。
5. 在“记录”中按订单或商品搜索、编辑、删除，并切换标准单位价/相同SKU按件价。
6. 在“设置”中导出 CSV 或完整 ZIP；ZIP 恢复首版只支持空数据库，并在导入前显示校验预览。

API Key 不写入源码、日志、CSV 或 ZIP。图片只在主动识别时发送给 DeepSeek；App 不提供账号、自动云备份或多设备同步。

## 构建基线

- Android Gradle Plugin 9.5.1
- Gradle 9.6.0
- JDK 17
- compileSdk / targetSdk 36

工程由云端构建环境执行；用户电脑不需要安装 Android Studio、Android SDK、Gradle 或模拟器。

## 云端构建命令

```text
./gradlew testDebugUnitTest assembleDebug
```

下一步在云端执行真实构建，处理编译器发现的问题，并完成模拟器、真机、Release签名和 APK 体积验证。

仓库已包含 `.github/workflows/android-build.yml`。推送到 GitHub 私有仓库后会自动使用 JDK 17 运行单元测试并构建 Debug APK，构建产物保留为 GitHub Actions Artifact；工作流不读取或保存 DeepSeek API Key。

## Python 主机侧测试

无需安装 Android 开发环境：

```text
python3 -m unittest discover -s python_tests -v
```

当前结果记录在 `TEST_REPORT.md`。
