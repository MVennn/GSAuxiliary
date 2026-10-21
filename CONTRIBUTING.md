# 参与贡献

> **一句话**：这是国信金太阳的 LSPosed 增强模块；改动前先读
> [docs/CODE-RULES.md](docs/CODE-RULES.md)（编码纪律）与
> [docs/DOC-RULES.md](docs/DOC-RULES.md)（文档规则），本文件只讲「怎么提」。

先说一句要紧的：本模块只做**界面显示与操作便捷**的增强，不碰交易、不做自动化下单、
不荐股。提 Issue / PR 时请确认自己的建议在这条线内。

## 提 Issue

- **开关失灵 / 显示异常 / 崩溃** → 用「问题反馈（Bug）」模板。
  **一定要贴 LSPosed 日志**，没有日志的问题多半只能来回追问：

  ```bash
  adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -50"
  ```

- **想要新功能** → 用「功能建议」模板。先说**场景**（在哪个页面、现在要几步、
  想变成什么样），场景越具体越容易判断该不该做。

排错步骤见 [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)。

## 提 Pull Request

1. 从 `main` 开分支（一条改动一个分支）。
2. 改动前过一遍 [docs/CODE-RULES.md](docs/CODE-RULES.md) 的「新功能自检清单」。
3. 提交前至少跑过：

   ```bash
   ./gradlew :app:assembleRelease          # 编译过
   ./gradlew :app:testReleaseUnitTest      # 单测过
   python tools/ci/check_invariants.py     # 静态检查六项（见 tools/README.md）
   ```

4. PR 描述里把「怎么验的」写清楚——**真机没验就明说没验**，别含糊。

## 几条硬规矩

| 规矩 | 在哪定义 |
|---|---|
| 每条规则只在一篇文档里定义，别处只留链接 | [docs/DOC-RULES.md](docs/DOC-RULES.md) §2 |
| 改了**判据**（判定条件 / 宿主 id / 状态判定）必须过一次独立复核 | [docs/CODE-RULES.md](docs/CODE-RULES.md) |
| 发版才动 `versionCode` / `versionName` | [docs/VERSIONING.md](docs/VERSIONING.md) |
| 每次交付都要编一个 release APK 给用户装 | [docs/RELEASE.md](docs/RELEASE.md) §0.1 |
| 用户可感知的改动要进 [CHANGELOG.md](CHANGELOG.md) | [docs/DOC-RULES.md](docs/DOC-RULES.md) §5 |
| 改免责全文要同时改中英两个规范源 | [docs/MAINTENANCE.md](docs/MAINTENANCE.md) §3 L3 |

## 免责声明

提交 Issue / PR 即表示你已知悉：本模块会 Hook 国信金太阳，可能违反其用户协议并导致
账号风控或封禁，相关风险由你自己承担；开发者不承担因使用本模块产生的任何责任。
全文见 [docs/DISCLAIMER.md](docs/DISCLAIMER.md)。
