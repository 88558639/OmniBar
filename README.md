# OmniBar

<p align="center"><img src="app/src/main/res/drawable-nodpi/app_logo.png" width="96" alt="OmniBar Logo"></p>

<p align="center"><strong>状态栏多合一 · ColorOS 17</strong></p>

OmniBar 是需要 Root + LSPosed（或兼容 legacy Xposed 框架）的 Android 模块，在 ColorOS 17 状态栏原有的电量宿主内，绘制一个把**电量、Wi-Fi、蜂窝信号**揉在一起的状态指示器：外圈电量弧 + 中心 Wi-Fi/网络类型 + 底部信号点，一眼看清设备状态。

> 产品思路参考 [lingyired/status-trio](https://github.com/lingyired/status-trio)（macOS 菜单栏多合一状态指示器），Android 侧为独立重写实现，无代码继承。

## 特性

### 状态栏指示器

- **外圈约 240° 电弧表示电量**，保留暗色底轨；未知电量不编造进度。
- **中心 Wi-Fi** 用三道弧线 + 一个点，共四层信号等级；连接中的动画、无互联网/需登录的「!」「?」大字标注、未连接时的网络类型（2G/3G/4G/5G）或 `SOS`/`sim`/占位横条都集中在同一块区域。
- **底部四点表示默认数据卡信号**；开启「双卡信号环」后，改为电池环底部的两段同心弧段分别表示主卡（外圈）与副卡（内圈）。
- **漫游**用「R」标记 + 对应信号点/环段改用漫游色（默认蓝）区分。
- **WiFi 代数显示**：连接 Wi-Fi 时在图标右下角标出当前代际 5/6/7/8，一眼判断连的是 WiFi 5 还是 WiFi 7。
- 支持锁屏与状态栏同时显示；继承 SystemUI 父级防烧屏移动。

### 状态配色

- **电池状态色**按优先级着色：充电/充满 → 严重低电量（默认 ≤10%）→ 低电量（默认 ≤20%）→ 省电模式 → 正常；数字和中心信号不强制变色。
- **充电协议配色**严格按「电池检测 app」的「快充模式」文本判定，不读瞬时功率、不猜硬件：SuperVOOC 或原装百瓦 UFCS 档位 → 紫；第三方 UFCS / PPS → 绿；PD / DCP → 蓝；普通 / 慢充 → 橙。第三方充电器绝不因功率高而显示紫色。
- 自定义颜色会按状态栏典型背景做 WCAG 可见性校正，保证对比度达标，原值仍保存。

### 更多标记

- **VPN** 连接时在电池环右上角显示盾形标记（可关闭）。
- **充电闪电 / 充满对勾**位于电量弧顶部，周围局部渐变虚化，优先于所有数字模式。
- 电量数字支持「始终显示」或「低于阈值自动显示」，互斥切换，可全部关闭。

## 屏幕截图

<p align="center">
  <img src="docs/images/settings-colors.jpg" width="300" alt="状态颜色设置">
  <img src="docs/images/settings-charge.jpg" width="300" alt="充电配色设置">
</p>

## 安装与恢复

1. 安装或覆盖最新 APK，不要清除数据。
2. LSPosed 只勾选 `com.android.systemui`，不要选 Android Framework 或所有应用。
3. 启用作用域后重新打开 OmniBar，让激活前设置迁移到共享目录。
4. 重启 SystemUI 或设备，再启用替换。
5. 异常时先在应用内关闭替换/恢复原图标；应用打不开时，在 LSPosed 停用 OmniBar 后重启。

详细说明：

- [安装、更新与恢复](docs/INSTALL.md)
- [功能与设置参数](docs/SETTINGS.md)
- [构建说明](docs/BUILD.md)
- [功能边界与验收状态](docs/COMPATIBILITY.md)
- [更新日志](CHANGELOG.md)
- [贡献指南](CONTRIBUTING.md)
- [安全说明](SECURITY.md)

## 许可证

暂未指定。
