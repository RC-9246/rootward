# 在本机联动自动化应用或智能插座

归根 Shizuku 版可以把当前养护策略分享给一个 Android 应用。它允许没有硬件停充接口的手机参与插座自动化。归根只提供建议，控制插座、确认通断和处理网络离线由接收方负责。此功能无需 Shizuku，也无需归根联网。

## 配置

1. 在归根「本机记录 → 连接本机自动化应用」中填写接收方的 Android 包名，保存并开启记录。以常见 Tasker 发行版为例，包名为 `net.dinglisch.android.taskerm`，请以实际安装版本为准。
2. 在接收方监听广播动作 `io.local.rootward.shizuku.POLICY_CHANGED`。在 Tasker 中可使用系统的「收到 Intent」事件。广播定向发送给所填包名，没有载荷；收到后查询最新状态。策略改变和持续监测期间都会发通知。
3. 查询 `content://io.local.rootward.shizuku.automation/v1/state`。在 Tasker 中可使用「SQL 查询 → Table URI」，投影填 `request`，选择条件、参数、排序留空。可先把查询值显示在通知中核对，再配置对应插座动作。[Tasker 的 Intent 事件](https://tasker.joaoapps.com/userguide/en/intents.html) · [Tasker 的 Table URI 查询](https://tasker.joaoapps.com/userguide/en/help/ah_sql_query.html)

| 查询结果 | 接收方可执行的动作 |
| --- | --- |
| `pause` | 关闭指定充电插座并通过插座的状态读回核对。达到停充上限、等待回落或触发温控都可能产生此建议。 |
| `allow` | 允许给充电器供电。手动提前补电仍遵守归根的温度保护，手机或充电器本身也可能限制充电。 |
| `unknown`、空结果、授权拒绝、查询失败 | 停止根据之前的值切换，按用户为该插座设置的离线策略处理。重试时重新查询。 |

广播本身不能证明当前状态。接收方应在动作执行前从上述 URI 查询，查询可以重复。对同一状态使用「设为开／设为关」，重复事件应产生相同结果。若要在手机失联时也能恢复供电，请在插座或家庭控制器中设置独立超时；手机关机后无法发送通知。插座关断会同时切掉外部供电，手机自然使用电池。

## 只读协议 v1

| 列 | 类型及含义 |
| --- | --- |
| `request` | `pause`、`allow`、`unknown` |
| `phase` | `waiting` 等恢复下限、`filling` 允许充向上限、`manual` 已手动提前一轮 |
| `level` | 最近观测的电量百分比，缺测为空 |
| `lower`、`upper` | 配置的恢复下限、停充上限，单位为 % |
| `observed_at` | 本机采样的 Unix 毫秒时间戳 |

仅选定的调用包可查询，指定投影可以只读所需的列。停止记录、尚无样本或样本已超过 150 秒时返回空结果。撤销共享后原包失去读取权限。协议不接受写入，不开放历史、电池序列号或 root 命令。
