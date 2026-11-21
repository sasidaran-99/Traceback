import type {
  HypothesisResponse,
  InvestigationAgentReport,
  InvestigationResponse,
} from '../api/types';

/**
 * A normalized, authoritative investigation record unified across
 * deterministic telemetry and autonomous AI agent reports.
 */
export interface AuthoritativeInvestigationItem {
  investigationId: string;
  serviceName: string;
  environment: string;
  /**
   * Authoritative verdict for this investigation:
   * - 'CONFIRMED' (agent or deterministic verified proof)
   * - 'PROBABLE' (candidate contributing change with strong overlap)
   * - 'INSUFFICIENT_EVIDENCE' (inconclusive / abstention)
   * - 'FAILED' (execution failure)
   * - 'REPORT_PENDING' (deterministic completed, agent report not yet run)
   */
  verdict: 'CONFIRMED' | 'PROBABLE' | 'INSUFFICIENT_EVIDENCE' | 'FAILED' | 'REPORT_PENDING' | string;
  commitsAnalyzed: number;
  errorsDetected: number;
  hypothesesCount: number;
  claimsCount: number;
  evidenceSufficient: boolean;
  topHypothesis?: HypothesisResponse | null;
  /**
   * Primary root-cause description:
   * 1. Authoritative AI Agent Report: primarySuspectedRootCause
   * 2. Deterministic top hypothesis: summary
   * 3. Neutral status description (never fabricated)
   */
  primaryRootCause?: string;
  startedAt?: string;
  completedAt?: string | null;
  hasAgentReport: boolean;
  report?: InvestigationAgentReport | null;
  rawInvestigation?: InvestigationResponse | null;
}

export interface VerdictMetrics {
  totalAnalyzed: number;
  confirmedCount: number;
  probableCount: number;
  insufficientCount: number;
  failedCount: number;
  pendingReportCount: number;
  totalHypotheses: number;
  totalErrors: number;
  totalCommits: number;
}

/**
 * Merges raw deterministic investigations and agent reports by investigationId,
 * preventing double-counting and determining the authoritative verdict for each investigation.
 */
export function buildAuthoritativeInvestigations(
  investigations: InvestigationResponse[] = [],
  agentReports: InvestigationAgentReport[] = []
): AuthoritativeInvestigationItem[] {
  const reportsByInvId = new Map<string, InvestigationAgentReport>();
  for (const report of agentReports || []) {
    if (report && report.investigationId) {
      reportsByInvId.set(report.investigationId, report);
    }
  }

  const invsById = new Map<string, InvestigationResponse>();
  for (const inv of investigations || []) {
    if (inv && inv.investigationId) {
      invsById.set(inv.investigationId, inv);
    }
  }

  // Deduplicate: each unique investigation ID is evaluated exactly once
  const allIds = Array.from(new Set([
    ...Array.from(invsById.keys()),
    ...Array.from(reportsByInvId.keys()),
  ]));

  return allIds.map((id) => {
    const report = reportsByInvId.get(id);
    const inv = invsById.get(id);

    let verdict: string;

    // Check if deterministic execution failed
    if (inv && (inv.status === 'FAILED' || inv.status === 'ERROR')) {
      verdict = 'FAILED';
    } else if (report && report.finalStatus) {
      const norm = report.finalStatus.trim().toUpperCase();
      if (norm === 'CONFIRMED' || norm === 'PROBABLE' || norm === 'INSUFFICIENT_EVIDENCE') {
        verdict = norm;
      } else {
        verdict = norm;
      }
    } else if (inv) {
      // Deterministic investigation with no AI agent report
      if (inv.status === 'INSUFFICIENT_EVIDENCE' || inv.evidenceSufficient === false) {
        verdict = 'INSUFFICIENT_EVIDENCE';
      } else if (inv.status === 'COMPLETED') {
        verdict = 'REPORT_PENDING';
      } else {
        verdict = (inv.status || 'UNKNOWN').toUpperCase();
      }
    } else {
      verdict = 'UNKNOWN';
    }

    // Determine authoritative primary root cause description without fabricating hypotheses
    const primaryRootCause =
      report?.primarySuspectedRootCause ||
      inv?.topHypothesis?.summary ||
      (verdict === 'CONFIRMED'
        ? 'Confirmed working tree forensic anomaly'
        : verdict === 'PROBABLE'
        ? 'Probable contributing change identified'
        : verdict === 'INSUFFICIENT_EVIDENCE'
        ? 'Inconclusive telemetry / Honest abstention'
        : verdict === 'REPORT_PENDING'
        ? 'Investigation completed / Report pending'
        : verdict === 'FAILED'
        ? 'Investigation failed'
        : undefined);

    return {
      investigationId: id,
      serviceName: inv?.serviceName || 'unknown-service',
      environment: inv?.environment || 'production',
      verdict,
      commitsAnalyzed: inv?.commitsAnalyzed || 0,
      errorsDetected: inv?.errorsDetected || 0,
      hypothesesCount: (inv?.investigationMode === 'WORKING_TREE_FORENSICS' || report?.primarySuspectedRootCause?.toLowerCase().includes('working tree'))
        ? (inv?.hypothesesCount ?? 0)
        : (inv?.hypothesesCount && inv.hypothesesCount > 0)
        ? inv.hypothesesCount
        : (report?.alternativeHypotheses && report.alternativeHypotheses.length > 0)
        ? report.alternativeHypotheses.length + 1
        : (inv?.commitsAnalyzed && inv.commitsAnalyzed > 0)
        ? inv.commitsAnalyzed
        : (report ? 1 : 0),
      claimsCount: inv?.claimsCount ?? (report?.validatedClaims?.length || 0),
      evidenceSufficient: inv ? inv.evidenceSufficient : (report?.finalStatus !== 'INSUFFICIENT_EVIDENCE'),
      topHypothesis: inv?.topHypothesis || null,
      primaryRootCause,
      startedAt: inv?.startedAt || report?.generatedAt || undefined,
      completedAt: inv?.completedAt || report?.generatedAt || undefined,
      hasAgentReport: !!report,
      report: report || null,
      rawInvestigation: inv || null,
    };
  });
}

/**
 * Calculates aggregated verdict and telemetry metrics from authoritative investigation items.
 * Guarantees:
 * - Confirmed count includes only CONFIRMED.
 * - Probable count includes only PROBABLE.
 * - Insufficient count includes INSUFFICIENT_EVIDENCE.
 * - Missing reports and failed investigations are handled explicitly.
 * - No duplicate reports or investigations are counted twice.
 */
export function calculateVerdictMetrics(
  items: AuthoritativeInvestigationItem[]
): VerdictMetrics {
  let confirmedCount = 0;
  let probableCount = 0;
  let insufficientCount = 0;
  let failedCount = 0;
  let pendingReportCount = 0;
  let totalHypotheses = 0;
  let totalErrors = 0;
  let totalCommits = 0;

  for (const item of items) {
    totalHypotheses += item.hypothesesCount || 0;
    totalErrors += item.errorsDetected || 0;
    totalCommits += item.commitsAnalyzed || 0;

    switch (item.verdict) {
      case 'CONFIRMED':
        confirmedCount++;
        break;
      case 'PROBABLE':
        probableCount++;
        break;
      case 'INSUFFICIENT_EVIDENCE':
        insufficientCount++;
        break;
      case 'FAILED':
        failedCount++;
        break;
      case 'REPORT_PENDING':
        pendingReportCount++;
        break;
      default:
        break;
    }
  }

  return {
    totalAnalyzed: items.length,
    confirmedCount,
    probableCount,
    insufficientCount,
    failedCount,
    pendingReportCount,
    totalHypotheses,
    totalErrors,
    totalCommits,
  };
}
