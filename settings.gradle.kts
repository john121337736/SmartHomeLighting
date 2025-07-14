pluginManagement {
    repositories {
        maven { url=uri("https://maven.aliyun.com/repository/google") }
        maven { url=uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url=uri("https://maven.aliyun.com/repository/google") }
        maven { url=uri("https://maven.aliyun.com/repository/public") }
        maven { url=uri("https://repo.eclipse.org/content/repositories/paho-releases/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "Smart Home Lighting"
include(":app")
