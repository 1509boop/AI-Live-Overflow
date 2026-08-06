# AI-Live-Overflow 🐣

让你的 AI 跳出对话框——Android 悬浮桌宠。

基于博主 [Vael-KY](https://github.com/Vael-KY/AI-Live-Overflow) 的开源架构实现。

## 功能
- 透明的悬浮窗桌宠(WebView 渲染,纯 CSS 动画)
- 拖拽 / 单击 / 双击 / 长按 手势
- 前台 App 感知(打开不同应用触发不同反应)
- 通过 Supabase 接收 AI(解七)实时推送内容
- GitHub Actions 自动构建 APK

## 构建
推送代码到 main 分支,GitHub Actions 自动构建 APK,在 Actions 页面的 Artifacts 下载 `app-debug.apk`。

## Supabase 配置
1. 建表 `pet_msg`:`id`,`content`,`mood`,`created_at`
2. 建表 `gesture_log`:`gesture_type`,`created_at`
3. 建表 `app_usage`:`package_name`,`created_at`
4. 把项目 URL 和 anon key 填入 `util/SupabaseClient.kt`

## 安装
1. 下载 APK 安装(需允许「悬浮窗」权限)
2. 首次启动允许悬浮窗、后台运行、电池白名单
3. 打开应用点「启动桌宠」