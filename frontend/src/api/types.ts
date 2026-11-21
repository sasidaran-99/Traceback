/**
 * Traceback 2.0 TypeScript Definitions.
 * Directly reflects the Java Spring Boot backend DTO contracts.
 */

export interface HealthResponse {
  status: string;
  timestamp: string;
  service: string;
  version: string;
}

export interface ConfidenceBreakdown {
  temporalPoints: number;
  overlapPoints: number;
  agreementPoints: number;
  contradictionPenalty: number;
  rawScore?: number;
  totalRawScore?: number;
  normalizedScore: number;
  scoreReasons: string[];
  penaltyReasons: string[];
}

export interface HypothesisResponse {
  id?: string;
  hypothesisId?: string;
  rank: number;
  confidenceScore: number;
  status: string; // 'PROPOSED' | 'CONFIRMED' | 'REJECTED' | 'INSUFFICIENT_EVIDENCE'
  summary: string;
  verificationPlan?: string;
  counterEvidence?: string | null;
  evidenceIds?: string[];
  supportingEvidenceIds?: string[];
  contradictingEvidenceIds?: string[];
  matchedRules?: string[];
  ruleMatchReasons?: Record<string, string>;
  penaltyReasons?: string[];
  rankingExplanation?: string;
  confidenceBreakdown?: ConfidenceBreakdown;
  breakdown?: ConfidenceBreakdown;
  commitSha?: string;
  description?: string;
  hasStructuralOverlap?: boolean;
  hasConfigOverlap?: boolean;
  investigationId?: string;
}

export type InvestigationHypothesis = HypothesisResponse;

export type InvestigationMode = 'RUNTIME_FAILURE' | 'WORKING_TREE_FORENSICS';

export interface InvestigationResponse {
  investigationId: string;
  investigationMode?: InvestigationMode;
  serviceName: string;
  environment: string;
  status: string;
  commitsAnalyzed: number;
  errorsDetected: number;
  hypothesesCount: number;
  claimsCount: number;
  evidenceSufficient: boolean;
  topHypothesis?: HypothesisResponse | null;
  startedAt: string;
  completedAt: string;
  persistedDurable?: boolean;
  persistenceStatus?: string;
}

export interface TimelineEvent {
  eventId: string;
  timestamp: string;
  eventType: string;
  source: string;
  entityId?: string;
  title?: string;
  summary?: string;
  description?: string;
  detail?: string;
  isPreFailure?: boolean;
  evidenceId?: string;
  relatedEvidenceId?: string;
  associatedCommitSha?: string;
  metadata?: Record<string, unknown>;
}

export type EvidenceType =
  | 'COMMIT'
  | 'COMMIT_DIFF'
  | 'CONFIG_DIFF'
  | 'STACK_TRACE'
  | 'STACK_FRAME'
  | 'LOG_ERROR'
  | 'ERROR_SIGNATURE'
  | 'WORKING_TREE_CHANGE';

export interface EvidenceItem {
  evidenceId: string;
  evidenceType: EvidenceType;
  source: string;
  originalReference?: string;
  timestamp?: string;
  summary: string;
  metadata?: Record<string, unknown>;
  integrityHash?: string;
  investigationId?: string;
}

export type ClaimValidationStatus =
  | 'SUPPORTED'
  | 'PARTIALLY_SUPPORTED'
  | 'UNSUPPORTED'
  | 'CONTRADICTED'
  | 'VALIDATION_ERROR';

export interface InvestigationClaim {
  claimId: string;
  investigationId: string;
  statement: string;
  supportingEvidenceIds: string[];
  contradictingEvidenceIds: string[];
  status: ClaimValidationStatus;
  explanation: string;
  assertedAt: string;
}

export interface ExternalResearchFinding {
  findingId?: string;
  query?: string;
  title?: string;
  url?: string;
  snippet?: string;
  retrievedAt?: string;
  relevanceScore?: number;
  author?: string;
  investigationId?: string;
  publishedDate?: string;
}

export interface AgentInvestigationStep {
  stepId?: string;
  stepType?: string;
  stepNumber: number;
  stepName?: string;
  status?: string;
  description: string;
  timestamp: string;
  metadata?: Record<string, unknown>;
}

export type AgentStep = AgentInvestigationStep;

export type AgentInvestigationStatus =
  | 'CONFIRMED'
  | 'PROBABLE'
  | 'INSUFFICIENT_EVIDENCE';

export interface InvestigationAgentReport {
  reportId: string;
  investigationId: string;
  summary: string;
  primarySuspectedRootCause: string;
  alternativeHypotheses: Array<
    | {
        id: string;
        rank: number;
        confidenceScore: number;
        status: string;
        summary: string;
        verificationPlan?: string;
        counterEvidence?: string | null;
      }
    | string
  >;
  supportingEvidenceIds: string[];
  contradictoryEvidenceIds: string[];
  deterministicConfidenceBreakdown?: ConfidenceBreakdown | null;
  aiReasoningSummary: string;
  externalResearchFindings: Array<ExternalResearchFinding | string>;
  sourceUrls: string[];
  recommendedVerificationSteps: string[];
  remainingUncertainties: string[];
  finalStatus: AgentInvestigationStatus;
  validatedClaims?: InvestigationClaim[];
  investigationSteps?: AgentInvestigationStep[];
  generatedAt?: string;
  modelName?: string;
  persistedDurable?: boolean;
  persistenceStatus?: string;
}

export interface InvestigationRequest {
  repositoryPath?: string;
  investigationMode?: InvestigationMode;
  stackTrace?: string;
  logFilePath?: string;
  logContent?: string;
  serviceName: string;
  environment: string;
  windowMinutes?: number;
  maxCommits?: number;
}

export interface AgentInvestigationRequest {
  investigationId?: string;
  investigationMode?: InvestigationMode;
  repositoryPath?: string;
  stackTrace?: string;
  logFilePath?: string;
  logContent?: string;
  serviceName?: string;
  environment?: string;
  windowMinutes?: number;
  maxCommits?: number;
}

// ============================================================================
// Stage 4.3.7.1: Dev Authentication & Remediation Discovery Types
// ============================================================================

export type DevPersona =
  | 'ALICE_ADMIN'
  | 'BOB_ADMIN'
  | 'CHARLIE_OPERATOR'
  | 'DANA_VIEWER';

export interface DevTokenRequest {
  persona: DevPersona;
}

export interface DevTokenResponse {
  token: string;
  tokenType: string;
  expiresIn: number;
  issuer: string;
  subject: string;
  roles: string[];
}

export interface AuthUser {
  persona?: DevPersona;
  subject: string;
  roles: string[];
  issuer: string;
  expiresAt: number; // Unix epoch seconds
  rawToken: string;
}

export type RemediationOperationType =
  | 'BRANCH_PATCH_PROMOTION'
  | 'ROLLBACK_COMMIT'
  | 'CHERRY_PICK_FIX';

export type RemediationOperationStatus =
  | 'CREATED'
  | 'VALIDATED'
  | 'PRE_GIT_CAS_INTENT'
  | 'GIT_CAS_SUCCEEDED'
  | 'PERSISTED'
  | 'RECOVERY_REQUIRED'
  | 'FAILED'
  | 'CANCELLED';

export type RemediationFailureClassification =
  | 'NONE'
  | 'PRECONDITION_FAILED'
  | 'TARGET_REF_FROZEN'
  | 'CAS_MISMATCH'
  | 'GIT_IO_FAILURE'
  | 'CONCURRENCY_CONFLICT'
  | 'TIMEOUT'
  | 'RECOVERY_FAILED'
  | 'INTERNAL_ERROR';

export interface RemediationOperationResponse {
  id: string;
  investigationId: string;
  idempotencyKey: string;
  repositoryPath: string | null;
  targetRef: string;
  expectedOldSha: string;
  proposedNewSha: string;
  operationType: RemediationOperationType;
  status: RemediationOperationStatus;
  workerId?: string | null;
  fencingToken?: number | null;
  leaseExpiresAt?: string | null;
  initiatedBy?: string | null;
  failureClassification?: RemediationFailureClassification | null;
  failureDetails?: string | null;
  recoveryStatus?: string | null;
  createdAt: string;
  updatedAt: string;
}

export type RemediationBreakGlassDecision = 'KEEP_FROZEN';

export type RemediationBreakGlassStatus =
  | 'PENDING_SECOND_APPROVAL'
  | 'EXECUTED_KEPT_FROZEN'
  | 'REJECTED'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'STALE_INVALIDATED';

export interface RemediationBreakGlassResponse {
  id: string;
  operationId: string;
  targetRef: string;
  expectedOldSha: string;
  proposedNewSha: string;
  snapshotGitSha: string;
  operationFencingToken: number;
  requestedDecision: RemediationBreakGlassDecision;
  status: RemediationBreakGlassStatus;
  rationale: string;
  evidenceDigest?: string | null;
  evidenceBundleRef?: string | null;
  evidenceBundleVersion?: string | null;
  initiatorIssuer: string;
  initiatorSubject: string;
  createdAt: string;
  expiresAt: string;
  approverIssuer?: string | null;
  approverSubject?: string | null;
  resolvedAt?: string | null;
  rejectionReason?: string | null;
  executionNotes?: string | null;
  cancelledByIssuer?: string | null;
  cancelledBySubject?: string | null;
  cancelledAt?: string | null;
  cancellationReason?: string | null;
}

export interface BreakGlassFinalizationResponse {
  decisionApplied: boolean;
  gitRecoveryExecuted: boolean;
  refFrozen: boolean | null;
  operationStatus: string;
  status: RemediationBreakGlassStatus;
  message: string;
  request: RemediationBreakGlassResponse;
}

export interface ProposeBreakGlassRequest {
  rationale: string;
  evidenceDigest?: string;
  evidenceBundleRef?: string;
  evidenceBundleVersion?: string;
}

export interface FinalizeBreakGlassRequest {
  decision: RemediationBreakGlassDecision;
  executionNotes?: string;
}

export interface RejectBreakGlassRequest {
  rejectionReason: string;
}

export interface CancelBreakGlassRequest {
  cancellationReason: string;
}

