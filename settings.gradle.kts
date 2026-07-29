pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("http://depot.sankuai.com/nexus/content/groups/public/")
            isAllowInsecureProtocol = true
        }
        maven {
            url = uri("https://pixel.sankuai.com/repository/mtdp")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("http://depot.sankuai.com/nexus/content/groups/public/")
            isAllowInsecureProtocol = true
        }
        maven {
            url = uri("https://pixel.sankuai.com/repository/mtdp")
        }
    }
}

rootProject.name = "ZhiwoShiguangjian"
include(":app")
