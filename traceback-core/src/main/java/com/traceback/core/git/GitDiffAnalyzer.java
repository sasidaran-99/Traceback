package com.traceback.core.git;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses unified diffs to compute line changes and extract configuration updates.
 */
@Component
public class GitDiffAnalyzer {

    private static final Pattern PROPERTIES_PATTERN = Pattern.compile("^([a-zA-Z0-9._-]+)\\s*[:=]\\s*(.*)$");
    private static final Pattern YAML_PATTERN = Pattern.compile("^\\s*([a-zA-Z0-9._-]+)\\s*:\\s*(.*)$");

    public record DiffStats(int added, int deleted) {}

    /**
     * Computes the number of added and deleted lines from a unified diff patch.
     */
    public DiffStats computeLineStats(String patch) {
        if (patch == null || patch.isBlank()) {
            return new DiffStats(0, 0);
        }

        int added = 0;
        int deleted = 0;

        try (BufferedReader reader = new BufferedReader(new StringReader(patch))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) {
                    continue;
                }
                if (line.startsWith("+")) {
                    added++;
                } else if (line.startsWith("-")) {
                    deleted++;
                }
            }
        } catch (Exception e) {
            // fallback
        }

        return new DiffStats(added, deleted);
    }

    /**
     * Extracts configuration changes (key -> "oldValue => newValue") from a patch if it touches a config file.
     */
    public Map<String, String> extractConfigChanges(String patch, String filePath) {
        Map<String, String> changes = new HashMap<>();
        if (patch == null || patch.isBlank() || !ChangedFile.isConfigurationPath(filePath)) {
            return changes;
        }

        Map<String, String> deletedProps = new HashMap<>();
        Map<String, String> addedProps = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(patch))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) {
                    continue;
                }

                if (line.startsWith("-")) {
                    parseConfigLine(line.substring(1).trim(), deletedProps);
                } else if (line.startsWith("+")) {
                    parseConfigLine(line.substring(1).trim(), addedProps);
                }
            }
        } catch (Exception e) {
            // fallback
        }

        // Correlate deleted and added properties
        for (Map.Entry<String, String> entry : addedProps.entrySet()) {
            String key = entry.getKey();
            String newValue = entry.getValue();
            String oldValue = deletedProps.getOrDefault(key, "<none>");
            changes.put(key, oldValue + " => " + newValue);
        }

        for (Map.Entry<String, String> entry : deletedProps.entrySet()) {
            String key = entry.getKey();
            if (!addedProps.containsKey(key)) {
                changes.put(key, entry.getValue() + " => <deleted>");
            }
        }

        return changes;
    }

    private void parseConfigLine(String line, Map<String, String> destination) {
        if (line.startsWith("#") || line.startsWith("//") || line.isBlank()) {
            return;
        }

        Matcher propMatcher = PROPERTIES_PATTERN.matcher(line);
        if (propMatcher.matches()) {
            destination.put(propMatcher.group(1).trim(), propMatcher.group(2).trim());
            return;
        }

        Matcher yamlMatcher = YAML_PATTERN.matcher(line);
        if (yamlMatcher.matches()) {
            destination.put(yamlMatcher.group(1).trim(), yamlMatcher.group(2).trim());
        }
    }
}
