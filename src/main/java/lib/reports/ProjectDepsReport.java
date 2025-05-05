package lib.reports;

import java.util.Set;

public class ProjectDepsReport {

    private final String projectName;
    private final Set<PackageDepsReport> packages;

    public ProjectDepsReport(String projectName, Set<PackageDepsReport> packages) {
        this.projectName = projectName;
        this.packages = packages;
    }

    public String getProjectName() {
        return projectName;
    }

    public Set<PackageDepsReport> getPackages() {
        return packages;
    }
}