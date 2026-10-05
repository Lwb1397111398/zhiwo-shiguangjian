pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 内网镜像只在本机走：GitHub Actions（CI=true）直连 google()/mavenCentral()，
        // 美团内网域名在海外 runner 上会拖到 502/超时。条件必须内联，pluginManagement
        // 先于脚本体执行，引用文件级 val 会 Unresolved reference。
        if (System.getenv("CI") != "true") {
            maven {
                url = uri("http://depot.sankuai.com/nexus/content/groups/public/")
                isAllowInsecureProtocol = true
            }
            maven {
                url = uri("https://pixel.sankuai.com/repository/mtdp")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (System.getenv("CI") != "true") {
            maven {
                url = uri("http://depot.sankuai.com/nexus/content/groups/public/")
                isAllowInsecureProtocol = true
            }
            maven {
                url = uri("https://pixel.sankuai.com/repository/mtdp")
            }
        }
    }
}

rootProject.name = "ZhiwoShiguangjian"
include(":app")
