# Router 跳转契约表

> **一句话**：宿主内部跳转走的 **router action** 清单与 extras 契约，真机测试时
> `am start -a <action>` 直达页面，免掉一层层模拟点击的导航铺垫；数据由探针
> LAUNCH 钩子采集、`tools/probe/parse_launch.py` 生成，**做新功能时边做边补**。

数据来源是**运行时观察**（探针记录的 startActivity 真值），不是静态反编译——
每条都是宿主真实执行过的跳转，可信度等同探针日志本身。

---

## 怎么用（真机测试直达）

```bash
ADBW="bash tools/adbw.sh"

# 无必填 extras 的页面，一条命令直达（已在 8.6.0 实测落点）：
"$ADBW" shell "su -c 'am start -a router_app_main'"        # 首页
"$ADBW" shell "su -c 'am start -a aistare_index'"          # 智能盯盘

# 带基本类型 extras 的（如 news.detail 的 String 参数）：
"$ADBW" shell "su -c \"am start -a news.detail --es url <url> --es title <标题>\""
```

注意：

- 以下命令都包在 `su -c` 里（root shell 可启动非导出组件），连接走
  `tools/adbw.sh`（自动重连 + mDNS 发现端口）。
- **extras 里是自定义对象（Bundle 装着 Parcelable/Serializable）的 action，
  `am` 传不了对象**——表里标了「手点」，从上游页面点一层进去，或走模块自己的
  跳转契约（见 [FEATURE-DIRECT-QUOTE.md](../FEATURE-DIRECT-QUOTE.md)）。
- 测试目的若是**验证模块注入后的点击/手势行为**，模拟点击仍然必要；直达只省
  「导航铺垫」环节。

### 直达失败排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `SecurityException` / Permission Denial | 没走 root shell | 确认 `su -c` 包裹，且设备 root 授权弹窗已允许 |
| 页面秒退 / 白屏 | 该页依赖 extras（对象或必填基本类型） | `am` 补不了，回退从上游页手点一层 |
| 跳到了但内容空（如策略详情空列表） | 少了 id 类 extras（如 `adapter_key` 里的 Strategy） | 这类页只省导航铺垫，数据仍需手点带上下文进入 |

### 探针重装后的连带操作

探针模块重装后，LSPosed 数据库里登记的 `apk_path` 会失效（`/data/app/~~随机`
路径变化），表现为宿主启动后探针不再输出日志：

1. `bash tools/adbw.sh shell "pm path com.wb.probe"` 拿新路径
2. `python tools/enable_lsposed.py <拉下来的db> com.wb.probe <新路径> com.guosen.android`
3. db 推回 `/data/adb/lspd/config/`，强停金太阳重开

<!-- TABLE:BEGIN -->

| router action | 次数 | 落点 Activity | extras（key: 类型） |
|---|---|---|---|
| `news.detail` | 4 | NewsDetailAct×4 | node_level: Integer, proxy: Bundle, title: String, url: String |
| `router_ai_stk_stg_list` | 3 | AIStkStgListAct×3 | node_level: Integer |
| `quotes.stockDetail` | 3 | StockFragAct×3 | adapter_key: Bundle, node_level: Integer, stockIndex: Integer |
| `router_ai_stg_stk_detail` | 2 | AIStkStgDetailAct×2 | adapter_key: Bundle, node_level: Integer |
| `router_app_main` | 2 | MainAct×2 | index: Integer, node_level: Integer |
| `router_tradelogin` | 1 | TradeLoginAct×1 | NEED_TRANSITION_WHILE_NOACCOUNT: Boolean, adapter_key: Bundle, is_option: Boolean, node_level: Integer, support_bio: Boolean, support_first_bio: Boolean, support_open_bio: Boolean |
| `router_trade_online_index` | 1 | TradeOnLineAct×1 | node_level: Integer, sticky_strategy_id: null, tabIndex: Integer |
| `router_ai_stk_index` | 1 | AIStkIndexAct×1 | node_level: Integer, sticky_strategy_id: null |
| `aistare_index` | 1 | AIStareIndexAct×1 | node_level: Integer, sticky_strategy_id: null |
| `aistare_his` | 1 | AIStareHisAct×1 | node_level: Integer |

<!-- TABLE:END -->

---

## 怎么更新（边做功能边补）

做任何新功能的真机环节，探针都会顺带记录你点过的每一次跳转。收尾时：

```bash
# 1. 拉探针日志（GSRecon TAG，目标进程的 LSPosed 日志）
bash tools/adbw.sh shell "su -c 'grep GSRecon /data/adb/lspd/log/modules_*.log'" \
    > .work/probe-launch-raw.txt

# 2. 重跑解析器（自动合并进本文件 TABLE 段）
python tools/probe/parse_launch.py .work/probe-launch-raw.txt
```

合并策略是**只增不删**：App 升级后某条 action 消失或改名，把旧行标记成
`[8.6.x 起消失]` 留档，别直接删——降级排查时要靠它对比新旧路由表。

## 背景与依据

- **为什么有这张表**：宿主是梆梆加固，静态反编译拿不到业务代码；但跳转契约
  在运行时是明文的，探针 LAUNCH 钩子（挂 `Activity.startActivity*`）直接记录。
  与其破壳（绕过技术措施，法律红线 + native 成本高），不如动态观察。
- **探针怎么跑**：见 [`../../tools/probe/README.md`](../../tools/probe/README.md)；
  页面类名清单见 [`activities-guosen.txt`](activities-guosen.txt)。
- **实测记录**（2026-10-17，8.6.0）：`router_app_main` / `router_ai_stk_index` /
  `router_ai_stk_stg_list` / `aistare_index` / `aistare_his` 五条
  `am start -a` 裸跳验证通过（探针 ACTIVITY 日志确认落点）。
