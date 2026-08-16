// 烟测工程不共享主构建配置，避免 project 依赖掩盖发布元数据缺失。
plugins {
    id("com.android.application") version "8.13.2" apply false
}
