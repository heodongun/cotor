plugins {
    id("org.gradle.toolchains.foojay-resolver") version "0.8.0"
}

rootProject.name = "cotor"

toolchainManagement {
    jvm {
        javaRepositories {
            repository("foojay") {
                resolverClass.set(org.gradle.toolchains.foojay.FoojayToolchainResolver::class.java)
            }
        }
    }
}
