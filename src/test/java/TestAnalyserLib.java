
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import lib.DependencyAnalyserLib;
import lib.reports.ClassDepsReport;
import lib.reports.PackageDepsReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class TestAnalyserLib {

    private Vertx vertx;
    private DependencyAnalyserLib dependencyAnalyser;

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path srcDir;
    private Path javaDir;
    private Path testPackageDir;
    private Path utilPackageDir;

    @BeforeEach
    void setUp() throws IOException {
        vertx = Vertx.vertx();
        dependencyAnalyser = new DependencyAnalyserLib(vertx);


        projectRoot = tempDir.resolve("test-project");
        srcDir = projectRoot.resolve("src");
        javaDir = srcDir.resolve("java");
        testPackageDir = javaDir.resolve("test");
        utilPackageDir = javaDir.resolve("util");

        Files.createDirectories(projectRoot);
        Files.createDirectories(srcDir);
        Files.createDirectories(javaDir);
        Files.createDirectories(testPackageDir);
        Files.createDirectories(utilPackageDir);

        createMainClass();
        createTestClass();
        createUtilClass();
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    void testGetClassDependencies(VertxTestContext testContext) throws Throwable {
        Path mainClassPath = javaDir.resolve("Main.java");

        dependencyAnalyser.getClassDependencies(mainClassPath)
                .onComplete(testContext.succeeding(report -> {
                    testContext.verify(() -> {
                        assertEquals("Main", report.getClassName());
                        assertEquals("test.project", report.getPackageName());
                        assertTrue(report.getDependencies().contains("java.util.List"));
                        assertTrue(report.getDependencies().contains("test.TestClass"));
                        assertTrue(report.getDependencies().contains("util.UtilClass"));
                        testContext.completeNow();
                    });
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testGetPackageDependencies(VertxTestContext testContext) throws Throwable {
        dependencyAnalyser.getPackageDependencies(testPackageDir)
                .onComplete(testContext.succeeding(report -> {
                    testContext.verify(() -> {
                        assertEquals("test", report.getPackageName());
                        assertEquals(1, report.getClasses().size());
                        ClassDepsReport classReport = report.getClasses().iterator().next();
                        assertEquals("TestClass", classReport.getClassName());
                        assertTrue(classReport.getDependencies().contains("util.UtilClass"));
                        testContext.completeNow();
                    });
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testGetProjectDependencies(VertxTestContext testContext) throws Throwable {
        dependencyAnalyser.getProjectDependencies(projectRoot)
                .onComplete(testContext.succeeding(report -> {
                    testContext.verify(() -> {
                        assertEquals("test-project", report.getProjectName());
                        assertEquals(1, report.getPackages().size());

                        PackageDepsReport javaPackage = report.getPackages().iterator().next();
                        assertEquals("java", javaPackage.getPackageName());

                        // Check that Main class was found
                        boolean foundMainClass = javaPackage.getClasses().stream()
                                .anyMatch(c -> c.getClassName().equals("Main"));
                        assertTrue(foundMainClass);

                        // Check that test and util subpackages were found
                        Set<PackageDepsReport> subPackages = javaPackage.getSubpackages();
                        assertEquals(2, subPackages.size());

                        boolean foundTestPackage = subPackages.stream()
                                .anyMatch(p -> p.getPackageName().equals("test"));
                        assertTrue(foundTestPackage);

                        boolean foundUtilPackage = subPackages.stream()
                                .anyMatch(p -> p.getPackageName().equals("util"));
                        assertTrue(foundUtilPackage);

                        testContext.completeNow();
                    });
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testErrorHandlingWithNonExistentFile(VertxTestContext testContext) throws Throwable {
        Path nonExistentFile = javaDir.resolve("NonExistent.java");

        dependencyAnalyser.getClassDependencies(nonExistentFile)
                .onComplete(testContext.failing(error -> {
                    testContext.verify(() -> {
                        assertNotNull(error);
                        testContext.completeNow();
                    });
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testErrorHandlingWithInvalidJavaFile(VertxTestContext testContext) throws Throwable {
        // Create invalid Java file
        Path invalidJavaFile = javaDir.resolve("Invalid.java");
        Files.writeString(invalidJavaFile, "This is not valid Java code");

        dependencyAnalyser.getClassDependencies(invalidJavaFile)
                .onComplete(testContext.failing(error -> {
                    testContext.verify(() -> {
                        assertNotNull(error);
                        testContext.completeNow();
                    });
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    // Helper methods to create test files

    private void createMainClass() throws IOException {
        String mainClassContent =
                "package test.project;\n\n" +
                        "import java.util.List;\n" +
                        "import java.util.ArrayList;\n" +
                        "import test.TestClass;\n" +
                        "import util.UtilClass;\n\n" +
                        "public class Main {\n" +
                        "    private TestClass testClass;\n" +
                        "    private UtilClass utilClass;\n\n" +
                        "    public List<String> doSomething() {\n" +
                        "        List<String> result = new ArrayList<>();\n" +
                        "        result.add(testClass.getMessage());\n" +
                        "        result.add(utilClass.getUtilMessage());\n" +
                        "        return result;\n" +
                        "    }\n" +
                        "}";

        Files.writeString(javaDir.resolve("Main.java"), mainClassContent);
    }

    private void createTestClass() throws IOException {
        String testClassContent =
                "package test;\n\n" +
                        "import util.UtilClass;\n\n" +
                        "public class TestClass {\n" +
                        "    private UtilClass utilClass;\n\n" +
                        "    public String getMessage() {\n" +
                        "        return \"Test message: \" + utilClass.getUtilMessage();\n" +
                        "    }\n" +
                        "}";

        Files.writeString(testPackageDir.resolve("TestClass.java"), testClassContent);
    }

    private void createUtilClass() throws IOException {
        String utilClassContent =
                "package util;\n\n" +
                        "public class UtilClass {\n" +
                        "    public String getUtilMessage() {\n" +
                        "        return \"Utility message\";\n" +
                        "    }\n" +
                        "}";

        Files.writeString(utilPackageDir.resolve("UtilClass.java"), utilClassContent);
    }
}