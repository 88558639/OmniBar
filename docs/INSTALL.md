# 安装、更新与恢复

> 本模块会进入 `com.android.systemui`。Root、LSPosed、SystemUI 注入和重启存在风险，安装者应准备好现有框架的恢复方式。

## 首次安装

本项目基于 ColorOS 17 / Android 15 机型开发，已在一加 15 / `PLK110` 上验证；其他 Android/ColorOS 版本不作保证，其他机型需自行验收。当前框架为 libxposed API 102（LSPosed）。

1. 从 [Releases](https://github.com/88558639/OmniBar/releases) 下载并校验最新 APK。
2. 在 LSPosed 启用模块，并且只选择 `com.android.systemui`。
3. 启用作用域后重手机。


诊断回执只证明 SystemUI 读到了配置或报告了状态，不等于人工真机验收。状态不完整时模块会保留原图标。

## 恢复

- 删除模块，重启手机

模块不自动获取 Root、不自动重启，不替换 QS/AOD，也不会把预览状态写入真实网络、电量或 SIM 状态。
