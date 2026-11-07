package com.traceback.core.git.workingtree;

/**
 * Git staging state of a working tree change.
 */
public enum WorkingTreeStagingState {
    /**
     * Change exists only in working directory (work tree != index).
     */
    UNSTAGED,

    /**
     * Change is staged in the Git index (index != HEAD).
     */
    STAGED,

    /**
     * File has both staged changes and unstaged working-tree changes.
     */
    PARTIALLY_STAGED,

    /**
     * File is untracked (neither in index nor in HEAD).
     */
    UNTRACKED,

    /**
     * File is in conflict state.
     */
    CONFLICTING
}
