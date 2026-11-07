package com.traceback.core.git.workingtree;

/**
 * Functional taxonomy of uncommitted working tree changes detected in a Git repository.
 */
public enum WorkingTreeChangeType {
    /**
     * File is tracked in HEAD or index, but deleted/absent from working directory filesystem (unstaged deletion).
     */
    MISSING,

    /**
     * File is tracked and modified in the working directory compared to index (unstaged modification).
     */
    MODIFIED,

    /**
     * File physically exists in the working directory but is not tracked by Git.
     */
    UNTRACKED,

    /**
     * File is newly staged in the index (not yet in HEAD).
     */
    STAGED_ADD,

    /**
     * File modification is staged in the index.
     */
    STAGED_MODIFY,

    /**
     * File deletion is staged in the index.
     */
    STAGED_DELETE,

    /**
     * File has merge or rebase conflicts.
     */
    CONFLICT
}
