import org.gradle.api.JavaVersion
import org.gradle.api.Project
import java.io.File
import java.util.Properties

object Version {
    val java = JavaVersion.VERSION_17

    const val compileSdkVersion = 37
    // Watch port: Android 8.1 (API 27) is the lowest supported wearable baseline.
    const val minSdk = 27
    const val targetSdk = 36

    private const val defaultNdkVersion = "28.2.13676358"
    private const val defaultCMakeVersion = "3.28.0+"

    fun getNdkVersion(): String {
        return defaultNdkVersion
    }

    fun getCMakeVersion(): String {
        return defaultCMakeVersion
    }

    fun getLocalProperty(project: Project, propertyName: String): String? {
        val rootProject = project.rootProject
        val localProp = File(rootProject.projectDir, "local.properties")
        if (!localProp.exists()) {
            return null
        }
        val localProperties = Properties()
        localProp.inputStream().use {
            localProperties.load(it)
        }
        return localProperties.getProperty(propertyName, null)
    }

}
