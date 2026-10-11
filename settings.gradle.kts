pluginManagement {
    repositories {
        // 阿里云镜像：国内本机加速用；GitHub Actions 上镜像偶发 502 会让整个构建
        // 直接失败（Gradle 会 disable 掉出错的 repo），因此 CI（env CI=true）跳过镜像。
        if (System.getenv("CI").isNullOrEmpty()) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI").isNullOrEmpty()) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        // 腾讯导航SDK Maven仓库
        maven { url = uri("https://oss.sonatype.org/content/groups/public/") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/staging/") }
    }
}

rootProject.name = "Navipilot"
include(":app")
