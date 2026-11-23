# 慢听英语

适用于 Android 13 及以上的离线英语听力播放器，首版以 REDMI K80 为使用目标。

在听力库点「导入」，选取 MP3、M4A 或 WAV 文件，填写标题并粘贴或导入英文原文。录音会复制到应用内部存储，原文和播放进度也保存在本机。点任意录音即可播放。播放页提供调速、前后跳转十秒、切换录音和显示或隐藏原文。

播放开始后，可以锁屏或切换应用。应用通过 Android Media3 的前台媒体服务在后台播放，系统媒体卡片可控制暂停和继续。应用内含一段合成语音示例，可以直接用来试播。

## 构建

用 Android Studio 打开本目录，安装 Android SDK 35 后运行 `./gradlew assembleDebug`。生成的安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。需要 JDK 17 或更新版本。
