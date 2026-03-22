package de.crazj.sprayz

import io.papermc.paper.plugin.loader.PluginClasspathBuilder
import io.papermc.paper.plugin.loader.PluginLoader
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver

class DependencyLoader : PluginLoader {
    override fun classloader(classpathBuilder: PluginClasspathBuilder) {
        // Maven-Resolver einrichten
        val resolver = MavenLibraryResolver()
//        resolver.addRepository(
//            RemoteRepository.Builder(
//                "mavenCentral", "default",
//                MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR
//            ).build()
//        )
//        resolver.addRepository(
//            RemoteRepository.Builder(
//                "codemc", "default",
//                "https://repo.codemc.io/repository/maven-public/"
//            ).build()
//        )

//        // KSpigot
//        resolver.addDependency(Dependency(DefaultArtifact("net.axay:kspigot:1.21.0"), null))

//        // nbt api
//        resolver.addDependency(Dependency(DefaultArtifact("de.tr7zw:item-nbt-api-plugin:2.15.5"), null));
//
//        classpathBuilder.addLibrary(
//            resolver
//        )
    }
}