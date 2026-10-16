# xposed-repo/ —— LSPosed 模块仓库的元文件源

这个目录**不参与 APK 构建**，只存 LSPosed 模块仓库
（`Xposed-Modules-Repo/io.github.SansKritO4.GSAuxiliary`）里那几个元文件的**母本**。
`tools/publish.py` 会把它们同步过去，保证两边不会各改各的。

| 模块仓库里的文件 | 母本在哪 | 说明 |
|---|---|---|
| `README.md` | 仓库根的 `README.md` | 两个仓库必须逐字一致 |
| `SUMMARY` | **本目录的 `SUMMARY`** | 模块页标题下面那行**一句话简介** |
| `SOURCE_URL` | 由 `publish.py` 按源码仓库地址生成 | `https://github.com/SansKritO4/GSAuxiliary` |
| `icon.svg` | `assets/icon.svg` | 由 `tools/gen_launcher_icons.py` 生成，与 APK 内 launcher 图标同源 |

## ⚠️ 关于 `SUMMARY`

它**只能是一句话简介**，不能写注释、不能写多行名字 —— 文件内容会被原样显示在
网站与 LSPosed App 的模块页上。模块的**显示名**来自仓库的 Description 字段
（只能在浏览器 About 齿轮里改，API 权限不够），不在这个文件里。

改完本目录的任何文件后，跑一次同步即可：

```bash
python tools/publish.py --sync-only
```
