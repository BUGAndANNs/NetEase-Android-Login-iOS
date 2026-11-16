# Xposed 模块入口：由 assets/xposed_init 按类名反射加载，必须保留类名与无参构造
-keep class cn.ratwl.androidloginios.xposed.XposedEntry { *; }

# Hook 层整包保留：
#  - ChannelHook 按方法名反射代理 getChannel / getCustomCpsChannel / getPlatform
#  - PlatformHook.getPlatform 会被 SDK 通过反射回调
#  - Diag / HookLogger 内有反射取 ActivityThread 的代码，保类名便于排错
-keep class cn.ratwl.androidloginios.xposed.** { *; }

# 编译期依赖，运行期由框架提供
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
