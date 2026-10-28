package dev.xantha.vss.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Checks compiled dependencies, including field/method types that import-only scans miss. */
class ModuleArchitectureTest {
    private static JavaClasses production;

    @BeforeAll
    static void importProductionOnly() {
        ArchConfiguration.get().setResolveMissingDependenciesFromClassPath(false);
        String directories = System.getProperty("vss.mainClasses");
        assertNotNull(directories, "Run architectureTest or verifyModules, not a bare JUnit launch");
        var paths = Arrays.stream(directories.split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).filter(Files::isDirectory).toList();
        assertFalse(paths.isEmpty(), "No compiled production classes supplied");
        production = new ClassFileImporter().importPaths(paths);
        assertFalse(production.isEmpty(), "Production import is incomplete");
        for (String type : new String[] {"api.VSSApi", "client.prediction.PredictionRenderer",
                "client.prediction.PredictionVoxelData", "compat.VoxyPredictionBridge",
                "networking.server.VSSServerNetworking", "config.JsonConfigStore"}) {
            assertTrue(production.contain("dev.xantha.vss." + type), type);
        }
    }

    @Test
    void predictionOwnsVoxelsAndNeverAnIndependentGpuScene() {
        for(String name:new String[]{"PredictionGpuScene","PredictionMeshBuilder","PredictionPackedMesh",
                "PredictionMesh","PredictionMeshFailure","PredictionVegetationDensity","PredictionVegetationSelection"}) {
            assertFalse(production.contain("dev.xantha.vss.client.prediction."+name),name+" is retired");
        }
        noClasses().that().haveFullyQualifiedName("dev.xantha.vss.client.prediction.PredictionVoxelData")
                .should().dependOnClassesThat().resideInAnyPackage("org.lwjgl..", "com.mojang.blaze3d..")
                .check(production);
    }

    @Test
    void apiDependsOnContractsAndValues() {
        noClasses().that().resideInAPackage("dev.xantha.vss.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dev.xantha.vss.networking..", "dev.xantha.vss.client..",
                        "dev.xantha.vss.compat..", "dev.xantha.vss.config..")
                .check(production);
    }

    @Test
    void commonAndConfigurationCannotLoadHigherModules() {
        noClasses().that().resideInAPackage("dev.xantha.vss.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dev.xantha.vss.api..", "dev.xantha.vss.config..",
                        "dev.xantha.vss.networking..", "dev.xantha.vss.client..",
                        "dev.xantha.vss.compat..", "dev.xantha.vss.mixin..")
                .check(production);
        noClasses().that().resideInAPackage("dev.xantha.vss.config..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dev.xantha.vss.api..", "dev.xantha.vss.networking..",
                        "dev.xantha.vss.client..", "dev.xantha.vss.compat..",
                        "dev.xantha.vss.mixin..")
                .check(production);
    }

    @Test
    void serverAndWireValuesCannotLoadTheClient() {
        noClasses().that().resideInAPackage("dev.xantha.vss.networking.server..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dev.xantha.vss.client..", "dev.xantha.vss.networking.client..",
                        "net.minecraft.client..", "com.mojang.blaze3d..", "org.lwjgl..")
                .check(production);
        noClasses().that().resideInAPackage("dev.xantha.vss.networking.payloads..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dev.xantha.vss.client..", "dev.xantha.vss.networking.client..",
                        "dev.xantha.vss.networking.server..", "dev.xantha.vss.compat..")
                .check(production);
    }

    @Test
    void injectedServerServicesCannotCallTheCompositionRoot() {
        String[] services = {
            "storage.PersistentColumnWriter", "preload.ExistingColumnPreloader",
            "request.ColumnStorageReadPipeline", "request.ColumnRequestBatchHandler",
            "request.ClientControlMessageHandler", "session.PlayerSessionManager",
            "sending.QueuedColumnSender", "sending.GeneratedColumnFlusher",
            "generation.ChunkyGenerationService", "sending.ColumnDeliveryService",
            "session.PlayerSessionLookup"
        };
        for (String service : services) {
            String type = "dev.xantha.vss.networking.server." + service;
            assertTrue(production.contain(type), type);
            noClasses().that().haveFullyQualifiedName(type)
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "dev.xantha.vss.networking.server.VSSServerNetworking")
                    .check(production);
        }
    }

    @Test
    void serverFacadeExceptionsCannotExpandImplicitly() {
        Set<String> adapters = Set.of(
                "dev.xantha.vss.networking.server.VSSServerNetworking",
                "dev.xantha.vss.networking.server.command.VSSServerCommands",
                "dev.xantha.vss.networking.server.command.VSSChunkyCommands",
                "dev.xantha.vss.networking.server.broadcast.FarPlayerBroadcaster",
                "dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster");
        var service = new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                "server implementation outside the documented adapters") {
            @Override public boolean test(com.tngtech.archunit.core.domain.JavaClass type) {
                String name = type.getName();
                return name.startsWith("dev.xantha.vss.networking.server.")
                        && adapters.stream().noneMatch(adapter -> name.equals(adapter) || name.startsWith(adapter + "$"));
            }
        };
        noClasses().that(service).should().dependOnClassesThat().haveFullyQualifiedName(
                "dev.xantha.vss.networking.server.VSSServerNetworking").check(production);
    }

    @Test
    void extensionsAreSafeForTheServer() {
        noClasses().that().resideInAnyPackage("dev.xantha.vss.api.worldgen..", "dev.xantha.vss.api.column..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "net.minecraft.client..", "com.mojang.blaze3d..", "org.lwjgl..")
                .check(production);
    }

    @Test
    void predictionAndCompatibilityUseTheSessionContract() {
        noClasses().that().resideInAnyPackage("dev.xantha.vss.client.prediction..",
                        "dev.xantha.vss.compat..")
                .should().dependOnClassesThat().resideInAPackage(
                        "dev.xantha.vss.networking.client..")
                .check(production);
    }

    @Test
    void planningAlgorithmsCannotReadGlobalClientOrNetworkingState() {
        noClasses().that().haveNameMatching(
                        "dev\\.xantha\\.vss\\.client\\.prediction\\.Prediction(PlanPreparation|DemandPreparation|PlanningValidity)(\\$.*)?")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "dev.xantha.vss.client.prediction.ClientPredictionState").check(production);
        noClasses().that().haveNameMatching(
                        "dev\\.xantha\\.vss\\.client\\.prediction\\.Prediction(PlanPreparation|DemandPreparation|PlanningValidity)(\\$.*)?")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dev.xantha.vss.networking.client..", "dev.xantha.vss.compat..", "net.minecraft.client..")
                .check(production);
    }

    @Test
    void jsonStoreIsIndependentOfTheModLoader() {
        noClasses().that().haveFullyQualifiedName("dev.xantha.vss.config.JsonConfigStore")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "net.neoforged..", "net.minecraftforge..", "net.minecraft..")
                .check(production);
    }

    @Test
    void everyProductionPackageDocumentsItsBoundary() throws Exception {
        Path sourceRoot = Path.of(System.getProperty("vss.mainSources"));
        Set<Path> packages = new TreeSet<>();
        try (var files = Files.walk(sourceRoot.resolve("dev/xantha/vss"))) {
            files.filter(file -> file.toString().endsWith(".java"))
                    .forEach(file -> packages.add(file.getParent()));
        }
        assertTrue(packages.size() >= 34);
        for (Path directory : packages) {
            Path contract = directory.resolve("package-info.java");
            assertTrue(Files.isRegularFile(contract), "Missing package contract: " + directory);
            String text = Files.readString(contract);
            for (String section : new String[] {"职责", "依赖", "线程", "生命周期"}) {
                assertTrue(text.contains(section), contract + " missing " + section);
            }
        }
    }

    @Test
    void boundaryCheckerRejectsAnActualForbiddenDependency() {
        ArchRule rule = noClasses().should().dependOnClassesThat()
                .resideInAPackage("dev.xantha.vss.networking..");
        assertTrue(rule.evaluate(new ClassFileImporter().importClasses(CoupledFixture.class)).hasViolation());
        assertFalse(rule.evaluate(new ClassFileImporter().importClasses(LeafFixture.class)).hasViolation());
    }

    private static final class CoupledFixture {
        private dev.xantha.vss.networking.server.VSSServerNetworking networking;
    }
    private static final class LeafFixture { private String label; }
}
