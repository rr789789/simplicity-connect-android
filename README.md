# 矿用传感器监测

基于 Silicon Labs Android BLE 示例精简并重构的矿用接收器、压力传感器和倾角传感器现场监测工具。

## 功能

- 接收器8槽实时监测、绑定、配置回读与验收报告
- 压力/倾角传感器直连、速率、功耗、零点、休眠和MAC配置
- 客户/维护权限、井下高对比模式、审计记录和工业调试包
- 本地GBL及HTTPS Silicon Labs OTA
- 本地分钟聚合历史，保留90天

## 自动构建

所有推送由 GitHub Actions 自动运行测试、Lint 并生成 Debug APK。创建 `v*` 标签时还会生成正式签名 Release APK。

Release构建需要配置以下仓库Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

正式包名为 `com.zg.sensormonitor`，Debug包名为 `com.zg.sensormonitor.debug`。
