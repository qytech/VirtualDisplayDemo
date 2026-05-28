# VirtualDisplayDemo

这是一个基于 Android **VirtualDisplay API** 和 **MediaProjection** 实现的高级多屏协作演示项目。它能够实现在横屏主机（Host）上创建一个独立的、可交互的竖屏虚拟显示器，并支持在该显示器上独立运行第三方应用程序。

## 🌟 核心功能

*   **跨方向投屏**：在横屏物理设备上强制渲染 720x1280 的原生竖屏虚拟环境。
*   **独立 UI 渲染**：利用 `Presentation` API 实现副屏独立仪表盘（Dashboard），包含实时时钟和系统状态监控。
*   **系统级应用启动器**：支持扫描系统第三方应用，并利用 `ActivityOptions.setLaunchDisplayId` 将其指派到虚拟屏幕运行。
*   **双向触控交互**：主屏预览窗实时捕获触控事件，通过坐标映射算法精确注入到虚拟屏幕，实现“所见即所得”的操作。
*   **硬核旋转锁定**：通过反射系统 `IWindowManager` 接口，强制锁定副屏方向，防止第三方应用篡改显示策略。
*   **高性能架构**：全异步事件注入逻辑，方法级反射缓存，有效避免高负载下的系统 ANR。

## 🛠 技术栈

*   **UI 框架**：Jetpack Compose (Material 3)
*   **导航**：Jetpack Navigation 3
*   **并发控制**：Kotlin Coroutines
*   **核心 API**：`DisplayManager`, `MediaProjection`, `Presentation`, `InputManager` (Hidden API)
*   **系统集成**：声明 `android.uid.system` 共享用户 ID，具备系统级操作权限。

## 📋 系统要求

*   **Android 版本**：建议 Android 11 (API 30) 及以上。
*   **权限等级**：由于涉及跨屏启动和事件注入，**必须使用系统签名**进行打包。
*   **硬件适配**：针对 Rockchip (RK3576) 等高性能车载/工业主板进行了性能优化。

## 🚀 快速开始

### 1. 配置签名
将系统签名文件命名为 `SystemSignature.jks` 并放置在项目根目录下。在 `app/build.gradle.kts` 中确认签名配置已正确链接。

### 2. 使用步骤
1.  **启动投屏**：点击左侧“START”按钮，授权屏幕捕捉。
2.  **切换模式**：点击“MODE”选项可在 `PORTRAIT`（竖屏）和 `LANDSCAPE`（横屏）之间切换。
3.  **运行应用**：从左侧“APPS”列表中点击任意应用，它将出现在右侧预览框中。
4.  **交互**：直接在右侧预览框中滑动或点击，即可操控副屏应用。
5.  **返回仪表盘**：点击“DASHBOARD”按钮可随时从应用切回时钟监控界面。

## ⚠️ 注意事项

*   **兼容性**：项目大量使用了 Android 系统隐藏 API，适用于具有系统开发权限的 OEM 或定制 ROM 场景。
