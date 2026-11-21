package com.traceback.core.git;

import java.util.Collections;
import java.util.Map;

/**
 * Details of a file modified within a Git commit.
 */
public record ChangedFile(
        String filePath,
        String changeType,
        int linesAdded,
        int linesDeleted,
        String patch,
        boolean isConfigFile,
        Map<String, String> configChanges
) {
    public ChangedFile {
        if (configChanges == null) {
            configChanges = Collections.emptyMap();
        }
    }

    public static boolean isConfigurationPath(String path) {
        if (path == null) return false;
        if (isSourceCodePath(path)) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".properties")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".xml")
                || lower.endsWith(".json")
                || lower.endsWith(".env")
                || lower.endsWith(".conf")
                || lower.endsWith(".ini")
                || lower.endsWith(".toml")
                || lower.endsWith(".cfg")
                || lower.endsWith("dockerfile")
                || lower.endsWith(".cnf")
                || ((lower.contains("application.") || lower.contains("config.")) && !isSourceCodePath(path));
    }

    public static boolean isSourceCodePath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".java")
                || lower.endsWith(".kt")
                || lower.endsWith(".groovy")
                || lower.endsWith(".scala")
                || lower.endsWith(".py")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".cs")
                || lower.endsWith(".go")
                || lower.endsWith(".rs")
                || lower.endsWith(".rb")
                || lower.endsWith(".php")
                || lower.endsWith(".c")
                || lower.endsWith(".cpp")
                || lower.endsWith(".h")
                || lower.endsWith(".hpp")
                || lower.endsWith(".swift");
    }
}
