pluginManagement {
 resolutionStrategy { eachPlugin { if (requested.id.id == "com.android.application") useModule("com.android.tools.build:gradle:" + requested.version); if (requested.id.id == "org.jetbrains.kotlin.android") useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:" + requested.version) } }
 repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "BYD Vehicle Bridge"
include(":app")
