pluginManagement {
    repositories {
        // 国内网络环境优先使用镜像，避免 repo.maven.apache.org / repo1.maven.org 403 导致 AGP 依赖解析失败。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://repo.huaweicloud.com/repository/maven/")

        // 保留官方仓库作为兜底；如果你的网络访问 Maven Central 会 403，可临时注释下面三行。
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 依赖解析同样优先使用国内镜像。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://repo.huaweicloud.com/repository/maven/")

        // 官方仓库兜底；遇到 403 时可临时注释。
        google()
        mavenCentral()
    }
}

rootProject.name = "TamronDebugApp"
include(":app")
