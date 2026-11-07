package com.traceback.core.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Collects Git commit history, file diffs, and configuration modifications from local Git repositories.
 * Enforces strict boundary authorization to prevent arbitrary filesystem access or directory traversal.
 */
@Component
public class GitCollector {

    private static final Logger log = LoggerFactory.getLogger(GitCollector.class);

    private final GitDiffAnalyzer diffAnalyzer;
    private final GitCommitAnalyzer commitAnalyzer;
    private final List<Path> allowedRoots;

    public GitCollector() {
        this(new GitDiffAnalyzer(), new GitCommitAnalyzer(), (List<String>) null);
    }

    @Autowired
    public GitCollector(
            @Value("${traceback.security.allowed-repo-roots:}") String allowedRootsConfig
    ) {
        this(new GitDiffAnalyzer(), new GitCommitAnalyzer(), parseRoots(allowedRootsConfig));
    }

    public GitCollector(List<String> allowedRoots) {
        this(new GitDiffAnalyzer(), new GitCommitAnalyzer(), allowedRoots);
    }

    public GitCollector(GitDiffAnalyzer diffAnalyzer, GitCommitAnalyzer commitAnalyzer) {
        this(diffAnalyzer, commitAnalyzer, (List<String>) null);
    }

    public GitCollector(GitDiffAnalyzer diffAnalyzer, GitCommitAnalyzer commitAnalyzer, List<String> allowedRoots) {
        this.diffAnalyzer = diffAnalyzer != null ? diffAnalyzer : new GitDiffAnalyzer();
        this.commitAnalyzer = commitAnalyzer != null ? commitAnalyzer : new GitCommitAnalyzer();
        this.allowedRoots = new ArrayList<>();
        initAllowedRoots(allowedRoots);
    }

    private static List<String> parseRoots(String config) {
        if (config == null || config.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = config.split("[,;]");
        List<String> list = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    private void initAllowedRoots(List<String> roots) {
        if (roots != null && !roots.isEmpty()) {
            for (String r : roots) {
                addRoot(Path.of(r));
            }
        } else {
            // Default restricted boundary: current project root and system temp directory
            addRoot(Path.of("."));
            addRoot(Path.of(System.getProperty("java.io.tmpdir")));
        }
        log.info("GitCollector initialized with {} authorized repository root(s): {}",
                this.allowedRoots.size(), this.allowedRoots);
    }

    private void addRoot(Path path) {
        Path norm = path.toAbsolutePath().normalize();
        if (!this.allowedRoots.contains(norm)) {
            this.allowedRoots.add(norm);
        }
        try {
            Path real = norm.toRealPath();
            if (!this.allowedRoots.contains(real)) {
                this.allowedRoots.add(real);
            }
        } catch (IOException ignored) {
        }
    }

    public List<Path> getAllowedRoots() {
        return Collections.unmodifiableList(allowedRoots);
    }

    public boolean isPathAuthorized(Path path) {
        if (allowedRoots.isEmpty()) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            if (normalized.equals(root) || normalized.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates that the provided path exists, is within an authorized repository root,
     * is a valid directory, and contains a Git repository.
     * Prevents unauthorized directory traversal, symlink breakout, or arbitrary host filesystem inspection.
     */
    public Path validateRepositoryPath(String repositoryPath) {
        if (repositoryPath == null || repositoryPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Repository path cannot be null or empty");
        }

        Path realPath;
        try {
            realPath = Path.of(repositoryPath).toRealPath();
        } catch (IOException e) {
            // Path does not exist or cannot be accessed on disk.
            // Check normalized path against authorized roots to safely reject unauthorized probes
            Path raw = Path.of(repositoryPath).toAbsolutePath().normalize();
            if (!isPathAuthorized(raw)) {
                log.warn("Access denied: Repository path {} is outside authorized roots", repositoryPath);
                throw new SecurityException("Access denied: Repository path is not within the authorized repository roots");
            }
            throw new IllegalArgumentException("Repository path does not exist or is inaccessible");
        }

        // Boundary check on canonical real path
        if (!isPathAuthorized(realPath)) {
            log.warn("Access denied: Resolved real path {} is outside authorized roots", realPath);
            throw new SecurityException("Access denied: Repository path is not within the authorized repository roots");
        }

        if (!Files.isDirectory(realPath)) {
            throw new IllegalArgumentException("Repository path is not a directory");
        }

        Path gitDir = realPath.resolve(".git");
        if (!Files.exists(gitDir)) {
            throw new IllegalArgumentException("Directory is not a Git repository (missing .git directory)");
        }

        return realPath;
    }

    /**
     * Extracts commit evidence from the target Git repository up to maxCommits.
     *
     * @param repositoryPath local filesystem path to the repository
     * @param maxCommits     maximum number of commits to retrieve
     * @return chronological list of commit evidence (newest first)
     */
    public List<CommitEvidence> collectCommits(String repositoryPath, int maxCommits) {
        Path repoPath = validateRepositoryPath(repositoryPath);
        List<CommitEvidence> commits = new ArrayList<>();

        try (Git git = Git.open(repoPath.toFile())) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository)) {
                ObjectId head = repository.resolve("HEAD");
                if (head == null) {
                    log.warn("Repository at {} has no HEAD (empty repo)", repoPath);
                    return Collections.emptyList();
                }

                RevCommit startCommit = walk.parseCommit(head);
                walk.markStart(startCommit);

                int count = 0;
                for (RevCommit commit : walk) {
                    if (count++ >= maxCommits) {
                        break;
                    }
                    commits.add(analyzeCommit(repository, commit));
                }
            }
        } catch (IOException e) {
            log.error("Failed to inspect Git repository at {}", repoPath, e);
            throw new IllegalStateException("Failed to read Git history: " + e.getMessage(), e);
        }

        return commits;
    }

    /**
     * Analyzes an individual commit, extracting its diffs against its parent (or empty tree for initial commit).
     */
    public CommitEvidence analyzeCommit(Repository repository, RevCommit commit) {
        String fullSha = commit.getName();
        String shortSha = commit.abbreviate(7).name();
        PersonIdent authorIdent = commit.getAuthorIdent();
        String authorName = authorIdent != null ? authorIdent.getName() : "Unknown";
        String authorEmail = authorIdent != null ? authorIdent.getEmailAddress() : "unknown@example.com";
        Instant commitTime = Instant.ofEpochSecond(commit.getCommitTime());
        String message = commit.getFullMessage().trim();

        List<ChangedFile> changedFiles = new ArrayList<>();
        int totalAdded = 0;
        int totalDeleted = 0;
        boolean hasConfig = false;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter df = new DiffFormatter(out)) {

            df.setRepository(repository);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            AbstractTreeIterator parentTree = getParentTreeIterator(repository, commit);
            AbstractTreeIterator commitTree = getCommitTreeIterator(repository, commit);

            List<DiffEntry> diffs = df.scan(parentTree, commitTree);

            for (DiffEntry diff : diffs) {
                out.reset();
                df.format(diff);
                String patch = out.toString(StandardCharsets.UTF_8);

                String filePath = diff.getNewPath();
                if (filePath == null || filePath.equals("/dev/null")) {
                    filePath = diff.getOldPath();
                }

                GitDiffAnalyzer.DiffStats stats = diffAnalyzer.computeLineStats(patch);
                totalAdded += stats.added();
                totalDeleted += stats.deleted();

                boolean isConfigFile = ChangedFile.isConfigurationPath(filePath);
                Map<String, String> configChanges = isConfigFile
                        ? diffAnalyzer.extractConfigChanges(patch, filePath)
                        : Collections.emptyMap();

                if (!configChanges.isEmpty()) {
                    hasConfig = true;
                }

                changedFiles.add(new ChangedFile(
                        filePath,
                        diff.getChangeType().name(),
                        stats.added(),
                        stats.deleted(),
                        patch,
                        isConfigFile,
                        configChanges
                ));
            }
        } catch (IOException e) {
            log.warn("Error reading diff for commit {}", fullSha, e);
        }

        boolean hasCode = changedFiles.stream()
                .anyMatch(cf -> cf.filePath().endsWith(".java") || cf.filePath().endsWith(".kt") || cf.filePath().endsWith(".ts"));

        return new CommitEvidence(
                fullSha,
                shortSha,
                authorName,
                authorEmail,
                commitTime,
                message,
                changedFiles,
                totalAdded,
                totalDeleted,
                hasConfig,
                hasCode
        );
    }

    private AbstractTreeIterator getCommitTreeIterator(Repository repository, RevCommit commit) throws IOException {
        CanonicalTreeParser treeParser = new CanonicalTreeParser();
        try (ObjectReader reader = repository.newObjectReader()) {
            treeParser.reset(reader, commit.getTree().getId());
        }
        return treeParser;
    }

    private AbstractTreeIterator getParentTreeIterator(Repository repository, RevCommit commit) throws IOException {
        if (commit.getParentCount() == 0) {
            return new EmptyTreeIterator();
        }
        RevCommit parent = commit.getParent(0);
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit parentCommit = revWalk.parseCommit(parent.getId());
            RevTree tree = parentCommit.getTree();
            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }
            return treeParser;
        }
    }

    public GitDiffAnalyzer getDiffAnalyzer() {
        return diffAnalyzer;
    }

    public GitCommitAnalyzer getCommitAnalyzer() {
        return commitAnalyzer;
    }
}
