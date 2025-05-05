package lib.reports;

import java.util.HashSet;
import java.util.Set;

public class PackageDepsReport {

    public String packageName;
    public Set<PackageDepsReport> packages;
    public Set<ClassDepsReport> classes;

    public PackageDepsReport(String packageName, Set<ClassDepsReport> classes, Set<PackageDepsReport> packages) {
        this.packageName = packageName;
        this.classes = classes;
        this.packages = packages;
    }

    public PackageDepsReport(String packageName, Set<ClassDepsReport> classes) {
        this.packageName = packageName;
        this.classes = classes;
        this.packages = new HashSet<>();
    }

    public String getPackageName() {
        return packageName;
    }

    public Set<PackageDepsReport> getPackages() {
        return packages;
    }

    public Set<ClassDepsReport> getClasses() {
        return classes;
    }

    public Set<PackageDepsReport> getSubpackages() {
        return packages;
    }

}