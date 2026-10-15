// ---------------------------------------------------------------------------
// 依赖仓库
//
// 国内网络优先走阿里云镜像（快）；CI runner 在海外，直连 google() / mavenCentral()
// 更稳 —— 镜像站对海外 IP 反而可能慢或超时，而 Gradle 只在 404 时才会换下一个
// 仓库，超时是不会自动兜底的。
//
// 所以 GitHub Actions 里设了 GS_NO_MIRROR=1（见 .github/workflows/*.yml），
// 本机不设就照旧走镜像。
//
// 注意：`pluginManagement {}` 必须是本文件的第一个块，所以这里没法先声明一个
// val 再用，两处只能各自写一次判断。
// ---------------------------------------------------------------------------

pluginManagement {
    repositories {
        if (System.getenv("GS_NO_MIRROR") != "1") {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        if (System.getenv("GS_NO_MIRROR") != "1") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "GSAuxiliary"
include(":app")
