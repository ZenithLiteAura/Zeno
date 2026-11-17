// ClipNest 剪巢 · 安卓工程
// 本机网络：dl.google.com / repo.maven.apache.org / github 不可达，统一走阿里云镜像
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://jitpack.io")
        google()
        mavenCentral()
    }
}

rootProject.name = "ClipNest"
include(":app")
include(":core")
