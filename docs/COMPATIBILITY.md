# 功能边界与验收状态

## ColorOS 17 兼容性范围

| 条件 | 值 |
| --- | --- |
| 开发范围 | ColorOS 17 / Android 15 |
| 本次真机验证 | `PLK110`（一加 15） |
| 框架验证环境 | libxposed API 102（LSPosed） |
| 作用域 | 仅 `com.android.systemui` |

运行时不再按精确型号、固件构建号或 SystemUI 版本阻断。项目基于 ColorOS 17 机型开发，其他 Android/ColorOS 版本不作保证；不同 ColorOS 17 机型仍需自行真机验收。反射契约不满足或运行时异常时，模块仍会回退并保留原图标；`minSdk=34` 只是安装边界。

## 覆盖与排除

绘制位于 SystemUI 原有电池宿主内，覆盖主状态栏和锁屏状态栏的电量、Wi-Fi、蜂窝组合状态；锁屏可单独关闭。组合图标继承父级防烧屏移动和系统布局协调，但项目不增加独立移动计时器，不能保证 OLED 不老化。

明确排除：QS/控制中心独立图标、AOD、其他应用、Android Framework 全局注入和 SystemUI 之外的进程。

## 版本修复

- 有效电量百分比不再因充放电 status 为 UNKNOWN 而丢失；仍可驱动进度、低电量颜色和自动数字，但不伪造充电/充满/暂停标记。
- Wi-Fi 互联网状态只使用当前 Wi-Fi Network capabilities；缺少当前 capabilities 时暂时显示连接中，避免混入蜂窝验证。
- 辅助功能描述只在替换 active 时接管，释放时恢复最新原生描述和重要性（包括 null）；非 active 宿主不改写。
- 刷新广播使用 signature permission；合并刷新请求不推迟首次 deadline，执行期间的新请求最多安排一次后续刷新。
- 充电配色严格按「快充模式」文本判定，不读瞬时功率、不猜硬件规格，第三方充电器绝不因功率高而误判为原装。

## 证据边界

| 项目 | 状态 |
| --- | --- |
| 本地构建与 Lint | 已通过 |
| 本地 JUnit/Robolectric 测试 | 已通过 |
| 一加 15 真机验证 | 已通过 |
| 全机型/全固件支持 | 未声明 |
| 真实 hook、锁屏、重启恢复、网络切换 | 仍需设备逐项验收 |

公开文档不得把本地测试描述成全量真机验收，也不得宣传全机型支持。

## 源码入口

- `app/src/main/java/com/omnibar/app/config/IndicatorConfig.kt`：默认值和边界。
- `app/src/main/java/com/omnibar/app/hook/ModuleEntry.kt`：gate、作用域宿主和回退。
- `app/src/main/java/com/omnibar/app/hook/ColorOsAdapter.kt`：ColorOS 17 状态栏适配。
- `app/src/main/java/com/omnibar/app/model/BatterySnapshot.kt`：电量解析。
- `app/src/main/java/com/omnibar/app/hook/SystemStateSource.kt`：Wi-Fi/蜂窝/充电协议状态。
- `app/src/main/AndroidManifest.xml`：Xposed 声明和刷新权限。
