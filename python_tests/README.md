# Python host-side tests

这些测试只使用 Python 标准库，不需要 Java、Gradle、Android SDK 或模拟器。

运行：

```text
python3 -m unittest discover -s python_tests -v
```

覆盖范围：

- PRD 单价与单位换算用例；
- 标准单位价与相同 SKU 按件价的比较边界；
- SQLite 五张核心表、字段类型、外键、索引和 NULL 语义；
- Android 权限、备份、API 基线和三入口配置；
- 浏览器原型的当前页编辑与商品历史路由静态契约。
- 原生识别入口可达性、AI任务完整状态、DeepSeek请求约束和Key加密契约；
- 真实记录列表/详情/历史入口、设置控件、CSV/ZIP迁移边界与导入安全契约。

这些测试不能替代 Kotlin 编译、Android 资源编译、APK 安装或系统 API 测试。
