package lib;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import lib.reports.ClassDepsReport;
import lib.reports.PackageDepsReport;
import lib.reports.ProjectDepsReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DependencyAnalyserLib {

    // Configuration constants
    private static final String ENTRY_POINT_FOLDER_NAME = "java";
    public static final String DEFAULT_PACKAGE = "java";
    public static final String SRC = "src";
    private static final String JAVA_FILE_EXTENSION = ".java";

    private final Vertx vertx;

    public DependencyAnalyserLib(Vertx vertx) {
        this.vertx = vertx;
    }

    public Future<ClassDepsReport> getClassDependencies(Path classSrcFile) {
        return readSourceFile(classSrcFile)
                .compose(this::parseCompilationUnitAsync)
                .compose(this::visitAST)
                .map(packageAndDeps -> {
                    String packageName = packageAndDeps.keySet().stream()
                            .findFirst()
                            .orElse(DEFAULT_PACKAGE);
                    String className = getFileNameWithoutExtension(classSrcFile);
                    return new ClassDepsReport(className, packageName, packageAndDeps.get(packageName));
                });
    }

    private String getFileNameWithoutExtension(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.replace(JAVA_FILE_EXTENSION, "");
    }

    private Future<String> readSourceFile(Path path) {
        Promise<String> promise = Promise.promise();
        vertx.fileSystem().readFile(path.toString(), ar -> {
            if (ar.succeeded()) {
                promise.complete(ar.result().toString("UTF-8"));
            } else {
                promise.fail(ar.cause());
            }
        });
        return promise.future();
    }

    private Future<Map<String,Set<String>>> visitAST(CompilationUnit cu) {
        return vertx.executeBlocking(() -> {
            Map<String, Set<String>> packageAndDependencies = new HashMap<>();
            Set<String> usedTypes = collectTypeDependencies(cu);

            String packageName = cu.getPackageDeclaration()
                    .map(NodeWithName::getNameAsString)
                    .orElse(DEFAULT_PACKAGE);

            packageAndDependencies.put(packageName, usedTypes);
            return packageAndDependencies;
        });
    }

    private Set<String> collectTypeDependencies(CompilationUnit cu) {
        Set<String> usedTypes = new HashSet<>();

        // imports
        cu.getImports().forEach(importDecl -> {
            if (!importDecl.isAsterisk()) {
                usedTypes.add(importDecl.getNameAsString());
            }
        });

        // type references from extends classes
        cu.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).forEach(type -> {
            if (type.getScope().isPresent()) {
                String qualifiedName = type.getScope().get().toString() + "." + type.getName().toString();
                usedTypes.add(qualifiedName);
            } else {
                usedTypes.add(type.getNameAsString());
            }
        });

        // field types
        cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).forEach(field -> {
            field.getVariables().forEach(var -> {
                String typeName = var.getType().toString();
                if (!isJavaPrimitive(typeName)) {
                    usedTypes.add(typeName);
                }
            });
        });

        // method return types and parameter types
        cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).forEach(method -> {
            String returnType = method.getType().toString();
            if (!isJavaPrimitive(returnType) && !returnType.equals("void")) {
                usedTypes.add(returnType);
            }

            method.getParameters().forEach(param -> {
                String paramType = param.getType().toString();
                if (!isJavaPrimitive(paramType)) {
                    usedTypes.add(paramType);
                }
            });
        });

        // local variable
        cu.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(var -> {
            String typeName = var.getType().toString();
            if (!isJavaPrimitive(typeName)) {
                usedTypes.add(typeName);
            }
        });

        usedTypes.removeIf(type -> type.startsWith("java.lang."));

        return usedTypes;
    }

    private boolean isJavaPrimitive(String type) {
        return type.equals("byte") || type.equals("short") || type.equals("int") ||
                type.equals("long") || type.equals("float") || type.equals("double") ||
                type.equals("boolean") || type.equals("char") || type.equals("void");
    }

    private Future<CompilationUnit> parseCompilationUnitAsync(String sourceCode) {
        return vertx.executeBlocking(() -> StaticJavaParser.parse(sourceCode), false);
    }

    public Future<PackageDepsReport> getPackageDependencies(Path packageSrcFolder) {
        String packageName = packageSrcFolder.getFileName().toString();

        //Java files
        Future<Set<Path>> javaFiles = getPathsByPredicate(
                packageSrcFolder,
                path -> Files.isRegularFile(path) && path.toString().endsWith(JAVA_FILE_EXTENSION)
        );

        //subdirectories
        Future<Set<Path>> subDirectories = getPathsByPredicate(
                packageSrcFolder,
                Files::isDirectory
        );

        Future<Set<ClassDepsReport>> classReports = javaFiles
                .compose(files -> collectResults(
                        files.stream()
                                .map(this::getClassDependencies)
                                .collect(Collectors.toList())
                ));

        Future<Set<PackageDepsReport>> packageReports = subDirectories
                .compose(dirs -> collectResults(
                        dirs.stream()
                                .map(this::getPackageDependencies)
                                .collect(Collectors.toList())
                ));
        return CompositeFuture.all(classReports, packageReports)
                .map(cf -> new PackageDepsReport(
                        packageName,
                        cf.resultAt(0),
                        cf.resultAt(1)
                ));
    }

    private Future<Set<Path>> getPathsByPredicate(Path directory, Predicate<Path> predicate) {
        return vertx.executeBlocking(() -> {
            try (Stream<Path> paths = Files.list(directory)) {
                return paths.filter(predicate).collect(Collectors.toSet());
            } catch (IOException e) {
                throw new RuntimeException("Error accessing directory: " + directory, e);
            }
        });
    }

    private <T> Future<Set<T>> collectResults(List<Future<T>> futures) {
        if (futures.isEmpty()) {
            return Future.succeededFuture(new HashSet<>());
        }

        return CompositeFuture.all(new ArrayList<>(futures))
                .map(cf -> {
                    Set<T> results = new HashSet<>();
                    for (int i = 0; i < cf.size(); i++) {
                        results.add(cf.resultAt(i));
                    }
                    return results;
                });
    }

    public Future<ProjectDepsReport> getProjectDependencies(Path projectFolder) {
        return findSrcFolder(projectFolder)
                .compose(this::findEntryPointFolder)
                .compose(entryPoint -> {
                    Future<Set<ClassDepsReport>> rootClasses = processRootJavaFiles(entryPoint);

                    Future<Set<PackageDepsReport>> packageReports = processSubdirectories(entryPoint);

                    return CompositeFuture.all(rootClasses, packageReports)
                            .map(cf -> {
                                Set<ClassDepsReport> classes = cf.resultAt(0);
                                Set<PackageDepsReport> subPackages = cf.resultAt(1);

                                String rootPackageName = entryPoint.getFileName().toString();
                                PackageDepsReport rootPackage = new PackageDepsReport(rootPackageName, classes, subPackages);

                                Set<PackageDepsReport> packages = new HashSet<>();
                                packages.add(rootPackage);

                                return new ProjectDepsReport(projectFolder.getFileName().toString(), packages);
                            });
                });
    }

    private Future<Set<ClassDepsReport>> processRootJavaFiles(Path directory) {
        return getPathsByPredicate(
                directory,
                path -> Files.isRegularFile(path) && path.toString().endsWith(JAVA_FILE_EXTENSION)
        ).compose(javaFiles ->
                collectResults(javaFiles.stream()
                        .map(this::getClassDependencies)
                        .collect(Collectors.toList()))
        );
    }

    private Future<Set<PackageDepsReport>> processSubdirectories(Path directory) {
        return getPathsByPredicate(directory, Files::isDirectory)
                .compose(dirs ->
                        collectResults(dirs.stream()
                                .map(this::getPackageDependencies)
                                .collect(Collectors.toList()))
                );
    }

    private Future<Path> findSrcFolder(Path startingPath) {
        return vertx.executeBlocking(() -> {
            try (Stream<Path> firstLevelDirs = Files.list(startingPath)) {
                return firstLevelDirs
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().equals(SRC))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException(SRC + " folder not found in " + startingPath));
            } catch (IOException e) {
                throw new RuntimeException("Error accessing file system: " + e.getMessage(), e);
            }
        });
    }

    private Future<Path> findEntryPointFolder(Path startingPath) {
        return vertx.executeBlocking(() -> {
            try (Stream<Path> paths = Files.walk(startingPath)) {
                return paths
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().equals(ENTRY_POINT_FOLDER_NAME))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException(ENTRY_POINT_FOLDER_NAME + " folder not found in " + startingPath));
            } catch (IOException e) {
                throw new RuntimeException("Error accessing file system: " + e.getMessage(), e);
            }
        });
    }
}