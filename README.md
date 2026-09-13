# Lithe · Android 工作文件夹

这是可以直接打包成 Android APK 的工程，以及已经编译好的安装包。

## 直接安装

把 `Lithe-v1.0.apk` 发到手机并安装即可（需允许“安装未知来源应用”）。

- 最低系统：Android 8.0（API 26）
- 签名：debug 签名（可正常安装使用，不上架应用商店）

## 文件夹结构

```
LitheApp/
├─ Lithe-v1.0.apk           # 已编译好的安装包
├─ app/
│  ├─ build.gradle
│  └─ src/main/
│     ├─ assets/www/index.html   # 应用页面（本地）
│     ├─ java/.../MainActivity.java
│     ├─ res/...
│     └─ AndroidManifest.xml
├─ build.gradle
├─ settings.gradle
├─ gradle.properties
└─ local.properties
```

## 功能

- 每日体重 / 体脂 / 心情打卡
- BMI、基础代谢、每日建议热量、热量预算环
- 周 / 月 / 年体重趋势图
- 拍照识别食物营养（需支持图片的模型）
- 文字记录食物、自定义食谱、AI 健康建议
- Piggy 减重教练（身份设定保存在本地，自动加载）
- 健康数据表格：步数、活动/总消耗热量（支持手机计步读取）
- 版本更新入口：可跳转 GitHub 下载最新安装包
- 数据全部保存在手机本地，可导出 / 导入备份

## AI 配置

打开应用后进入「我的 → AI 接口配置」：

- OpenAI：模型 `gpt-5-mini` / `gpt-5.1` / `gpt-5.5`（支持图片）
- DeepSeek：模型 `deepseek-v4-flash-vision-exp`（支持图片）；`deepseek-chat` / `deepseek-reasoner` 仅文本

食物拍照识别必须选择支持图片输入的模型。

## 重新打包

本机已安装工具链到：

`C:\Users\Musta\OneDrive\Desktop\_android_toolchain\`

在该文件夹下有 `jdk`、`gradle`、`sdk`。重新编译命令（PowerShell）：

```powershell
$tc = "C:\Users\Musta\OneDrive\Desktop\_android_toolchain"
$env:JAVA_HOME = "$tc\jdk\jdk-17.0.12+7"
& "$tc\gradle\gradle-8.9\bin\gradle.bat" -p "C:\Users\Musta\OneDrive\Desktop\LitheApp" assembleDebug --no-daemon --console=plain
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

## 免责声明

本应用仅用于日常健康记录。AI 建议不构成医疗诊断，如有疾病、孕期或体重快速异常变化，请及时就医。
