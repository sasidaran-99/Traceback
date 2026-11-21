package com.traceback.api.controller;

import com.traceback.core.git.GitCollector;
import com.traceback.core.persistence.ActiveInvestigationBootstrapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/**
 * Administrative controller for system maintenance, migration, and active investigation bootstrap.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ActiveInvestigationBootstrapService bootstrapService;
    private final GitCollector gitCollector;

    @Autowired
    public AdminController(ActiveInvestigationBootstrapService bootstrapService,
                           @Autowired(required = false) GitCollector gitCollector) {
        this.bootstrapService = bootstrapService;
        this.gitCollector = gitCollector != null ? gitCollector : new GitCollector();
    }

    @PostMapping("/bootstrap-active-investigation")
    public ResponseEntity<ActiveInvestigationBootstrapService.BootstrapReport> bootstrapActiveInvestigation(
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            @RequestParam(name = "backupPath", required = false) String backupPath
    ) {
        Path path = backupPath != null && !backupPath.isBlank()
                ? Path.of(backupPath)
                : Path.of("C:/Users/Sasidaran/.gemini/antigravity/brain/312124e7-6010-40db-ad58-48917858073c/scratch/complete_active_investigation_backup.json");

        Path normalized = path.toAbsolutePath().normalize();
        if (!gitCollector.isPathAuthorized(normalized)) {
            throw new SecurityException("Access denied: Backup path is not within the authorized repository roots: " + backupPath);
        }

        ActiveInvestigationBootstrapService.BootstrapReport report = bootstrapService.importFromBackup(path, force);
        return ResponseEntity.ok(report);
    }
}
