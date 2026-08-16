# 两套 ServiceLoader 均按原类名调用 public 无参构造器，只保留官方工厂这一处反射入口。
-keep,allowoptimization class io.github.fongmi.adaudio.probe.adapter.media3.v1_9.Media3ProbeAdapterFactory {
    public <init>();
}
