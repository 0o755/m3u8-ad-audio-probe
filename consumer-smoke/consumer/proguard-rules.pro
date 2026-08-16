# 只保留真实消费者入口，让 SDK 的 consumer rules 和调用图决定内部保留范围。
-keep class io.github.fongmi.adaudio.probe.smoke.OneLineIntegration { *; }
-keep interface io.github.fongmi.adaudio.probe.smoke.OneLineIntegration$HostPlayer { *; }
