## 这次改了什么

<!-- 一句话说清「改了什么」，别写「优化代码」「修 bug」这种没有信息量的词 -->

## 为什么改

<!-- 现象 / 根因 / 动机。相关背景可以引 docs/ 里的文档 -->

## 怎么验的

<!--
按可靠性从高到低勾：
- [ ] 编译过（`./gradlew :app:assembleRelease`）
- [ ] 单元测试过（`./gradlew :app:testReleaseUnitTest`）
- [ ] 静态检查过（tools/ci/check_*.py 六项）
- [ ] 真机验过（机型 / 安卓版本 / 宿主版本）
- [ ] 没验（说明为什么：这台改动是纯逻辑？还是设备不在线？）
-->

## 影响面

<!--
- 动了哪些模块 / 配置文件？
- 有没有改「判据」（判定条件、状态判定、宿主 id）？
  —— 这类改动必须过一次独立复核，见 docs/CODE-RULES.md。
- 用户能感知到的变化，写进 CHANGELOG 了吗？
-->

## Checklist

- [ ] 改动遵循 [docs/CODE-RULES.md](/docs/CODE-RULES.md)
- [ ] 文档同步（新增/删除文档要更 [docs/README.md](/docs/README.md) 索引）
- [ ] 用户可感知的改动已进 [CHANGELOG.md](/CHANGELOG.md)
- [ ] 没动 `versionCode` / `versionName`（发版时才动，见 [docs/VERSIONING.md](/docs/VERSIONING.md)）
