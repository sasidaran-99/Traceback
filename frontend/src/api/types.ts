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
  rawScore: number;
  normalizedScore: number;
  scoreReasons: string[];
  penaltyReasons: string[];
}

export interface HypothesisResponse {
  id: string;
  rank: number;
  confidenceScore: number;
  status: string; // 'PROPOSED' | 'CONFIRMED' | 'REJECTED' | 'INSUFFICIENT_EVIDENCE'
  summary: string;
  verificationPlan?: string;
  counterEvidence?: string | null;
  evidenceIds: string[];
  contradictingEvidenceIds: string[];
  matchedRules: string[];
  ruleMatchReasons: Record<string, string>;
  penaltyReasons: string[];
  rankingExplanation?: string;
  confidenceBreakdown?: ConfidenceBreakdown;
}

export interface InvestigationResponse {
  investigationId: string;
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
}

export interface TimelineEvent {
  eventId: string;
  timestamp: string;
  eventType: string;
  source: string;
  entityId: string;
  title: string;
  description: string;
  isPreFailure: boolean;
  evidenceId?: string;
  metadata: Record<string, unknown>;
}

export type EvidenceType =
  | 'COMMIT'
  | 'COMMIT_DIFF'
  | 'CONFIG_DIFF'
  | 'STACK_TRACE'
  | 'STACK_FRAME'
  | 'LOG_ERROR'
  | 'ERROR_SIGNATURE';

export interface EvidenceItem {
  evidenceId: string;
  evidenceType: EvidenceType;
  source: string;
  originalReference: string;
  timestamp?: string;
  summary: string;
  metadata: Record<string, unknown>;
  integrityHash?: string;
}

export type ClaimValidationStatus =
  | 'SUPPORTED'
  | 'PARTIALLY_SUPPORTED'
  | 'UNSUPPORTED'
  | 'CONTRADICTED';

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
  findingId: string;
  query: string;
  title: string;
  url: string;
  snippet: string;
  retrievedAt: string;
  relevanceScore: number;
}

export interface AgentInvestigationStep {
  stepNumber: number;
  stepName: string;
  description: string;
  timestamp: string;
  metadata: Record<string, unknown>;
}

export type AgentInvestigationStatus =
  | 'CONFIRMED'
  | 'PROBABLE'
  | 'INSUFFICIENT_EVIDENCE';

export interface InvestigationAgentReport {
  reportId: string;
  investigationId: string;
  summary: string;
  primarySuspectedRootCause: string;
  alternativeHypotheses: Array<{
    id: string;
    rank: number;
    confidenceScore: number;
    status: string;
    summary: string;
    verificationPlan?: string;
    counterEvidence?: string | null;
  }>;
  supportingEvidenceIds: string[];
  contradictoryEvidenceIds: string[];
  deterministicConfidenceBreakdown?: ConfidenceBreakdown | null;
  aiReasoningSummary: string;
  externalResearchFindings: ExternalResearchFinding[];
  sourceUrls: string[];
  recommendedVerificationSteps: string[];
  remainingUncertainties: string[];
  finalStatus: AgentInvestigationStatus;
  validatedClaims: InvestigationClaim[];
  investigationSteps: AgentInvestigationStep[];
  generatedAt: string;
}

export interface InvestigationRequest {
  repositoryPath?: string;
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
  repositoryPath?: string;
  stackTrace?: string;
  logFilePath?: string;
  logContent?: string;
  serviceName?: string;
  environment?: string;
  windowMinutes?: number;
  maxCommits?: number;
}
