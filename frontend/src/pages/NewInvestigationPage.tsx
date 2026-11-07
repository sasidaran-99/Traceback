import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Play,
  Sparkles,
  Server,
  Layers,
  AlertCircle,
  HelpCircle,
  FolderOpen,
} from 'lucide-react';
import { investigationApi } from '../api/investigationApi';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';

export const NewInvestigationPage: React.FC = () => {
  const navigate = useNavigate();

  // Workflow mode: 'new' | 'existing'
  const [workflow, setWorkflow] = useState<'new' | 'existing'>('new');

  // Form State
  const [repositoryPath, setRepositoryPath] = useState('');
  const [serviceName, setServiceName] = useState('order-service');
  const [environment, setEnvironment] = useState('production');
  const [logContent, setLogContent] = useState('');
  const [stackTrace, setStackTrace] = useState('');
  const [windowMinutes, setWindowMinutes] = useState(60);
  const [maxCommits, setMaxCommits] = useState(50);

  // Existing workflow state
  const [existingId, setExistingId] = useState('');

  // Execution & Error State
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // Quick Preset Handlers
  const defaultExampleRepoPath = import.meta.env.VITE_DEFAULT_REPO_PATH || 'C:/Users/developer/projects/TraceBack';

  const loadHikariCPExample = () => {
    setServiceName('order-service');
    setEnvironment('production');
    setRepositoryPath(defaultExampleRepoPath);
    setLogContent(`2026-10-10 14:15:22.100 ERROR 1420 --- [HikariPool-1] org.postgresql.Driver : java.sql.SQLTimeoutException: Connection timeout after 30000ms.
    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
    at com.zaxxer.hikari.HikariDataSource.getConnection(HikariDataSource.java:100)
    at com.traceback.order.OrderService.processPayment(OrderService.java:45)
    at com.traceback.order.OrderController.checkout(OrderController.java:28)`);
    setStackTrace(`java.sql.SQLTimeoutException: Connection timeout after 30000ms.
    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
    at com.traceback.order.OrderService.processPayment(OrderService.java:45)`);
  };

  const loadPaymentRegressionExample = () => {
    setServiceName('payment-service');
    setEnvironment('production');
    setRepositoryPath(defaultExampleRepoPath);
    setLogContent(`2026-10-10 14:16:35.100 ERROR 100 --- [http-nio-8080-exec-1] c.t.payment.PaymentService : java.lang.ArithmeticException: / by zero
    at com.traceback.payment.FeeCalculator.calculateFee(FeeCalculator.java:38)
    at com.traceback.payment.PaymentService.charge(PaymentService.java:55)
    at com.traceback.payment.PaymentController.process(PaymentController.java:32)`);
    setStackTrace(`java.lang.ArithmeticException: / by zero
    at com.traceback.payment.FeeCalculator.calculateFee(FeeCalculator.java:38)
    at com.traceback.payment.PaymentService.charge(PaymentService.java:55)`);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      if (workflow === 'existing') {
        const trimmed = existingId.trim();
        if (!trimmed) {
          throw new Error('Please provide an existing Investigation ID');
        }
        const report = await investigationApi.startAgentInvestigation({
          investigationId: trimmed,
        });
        navigate(`/investigations/${report.investigationId}`);
      } else {
        if (!repositoryPath.trim()) {
          throw new Error('Repository path is required for Git commit analysis');
        }
        if (!logContent.trim() && !stackTrace.trim()) {
          throw new Error('Please provide either log content or a stack trace');
        }

        const report = await investigationApi.startAgentInvestigation({
          repositoryPath: repositoryPath.trim(),
          serviceName: serviceName.trim(),
          environment: environment.trim(),
          logContent: logContent.trim() || undefined,
          stackTrace: stackTrace.trim() || undefined,
          windowMinutes: Number(windowMinutes),
          maxCommits: Number(maxCommits),
        });

        navigate(`/investigations/${report.investigationId}`);
      }
    } catch (err: unknown) {
      setErrorMessage(err instanceof Error ? err.message : 'Investigation failed');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="p-6 max-w-4xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-dark-700/80">
        <div>
          <h2 className="text-lg font-mono font-bold text-dark-100 flex items-center gap-2">
            <Sparkles className="w-5 h-5 text-brand-amber" />
            Launch Failure Investigation
          </h2>
          <p className="text-xs text-dark-400 mt-1">
            Dispatch the Traceback autonomous agent across Git commits, logs, and technical research.
          </p>
        </div>

        {/* Workflow Switcher */}
        <div className="flex items-center p-1 rounded-xl bg-dark-900 border border-dark-700 font-mono text-xs">
          <button
            type="button"
            onClick={() => setWorkflow('new')}
            className={`px-3 py-1.5 rounded-lg transition-colors ${
              workflow === 'new'
                ? 'bg-dark-800 text-brand-amber border border-dark-750 font-semibold'
                : 'text-dark-400 hover:text-dark-200'
            }`}
          >
            Workflow A: New Analysis
          </button>
          <button
            type="button"
            onClick={() => setWorkflow('existing')}
            className={`px-3 py-1.5 rounded-lg transition-colors ${
              workflow === 'existing'
                ? 'bg-dark-800 text-brand-amber border border-dark-750 font-semibold'
                : 'text-dark-400 hover:text-dark-200'
            }`}
          >
            Workflow B: Existing ID
          </button>
        </div>
      </div>

      {/* Quick Example Presets (Workflow A) */}
      {workflow === 'new' && (
        <div className="p-3.5 rounded-xl border border-dark-700 bg-dark-900/60 flex flex-wrap items-center justify-between gap-3 text-xs">
          <span className="text-dark-400 font-mono flex items-center gap-1.5">
            <HelpCircle className="w-3.5 h-3.5 text-brand-amber" />
            Quick Example Presets:
          </span>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={loadHikariCPExample}
              className="px-2.5 py-1 rounded bg-dark-800 text-dark-200 hover:bg-dark-750 border border-dark-700 text-xs font-mono transition-colors"
            >
              Scenario B: HikariCP Pool Timeout
            </button>
            <button
              type="button"
              onClick={loadPaymentRegressionExample}
              className="px-2.5 py-1 rounded bg-dark-800 text-dark-200 hover:bg-dark-750 border border-dark-700 text-xs font-mono transition-colors"
            >
              Scenario A: Division by Zero
            </button>
          </div>
        </div>
      )}

      {/* Error alert */}
      {errorMessage && (
        <div className="p-4 rounded-xl border border-rose-500/30 bg-rose-500/10 text-xs text-rose-300 flex items-start gap-2.5">
          <AlertCircle className="w-4 h-4 text-rose-400 shrink-0 mt-0.5" />
          <div>
            <strong className="font-semibold block font-mono">Submission Error:</strong>
            <span>{errorMessage}</span>
          </div>
        </div>
      )}

      {/* Investigation Form */}
      <form onSubmit={handleSubmit} className="rounded-xl border border-dark-700 bg-dark-900/80 p-6 space-y-5">
        {workflow === 'existing' ? (
          /* Workflow B: Existing Investigation ID */
          <div className="space-y-3">
            <label className="block text-xs font-mono font-medium text-dark-200">
              Existing Investigation ID (UUID) <span className="text-rose-400">*</span>
            </label>
            <input
              type="text"
              required
              value={existingId}
              onChange={(e) => setExistingId(e.target.value)}
              placeholder="e.g. 3ef41c49-ac62-467e-ac8f-eddd3581e154"
              className="w-full px-3.5 py-2.5 rounded-lg border border-dark-700 bg-dark-950 font-mono text-xs text-dark-100 placeholder:text-dark-600 focus:outline-none focus:border-brand-amber"
            />
            <p className="text-[11px] text-dark-400">
              Dispatches the autonomous AI agent on an existing deterministic investigation record.
            </p>
          </div>
        ) : (
          /* Workflow A: Full Investigation Setup */
          <>
            {/* Repository Path */}
            <div className="space-y-1.5">
              <label className="block text-xs font-mono font-medium text-dark-200 flex items-center justify-between">
                <span>
                  Local Repository Path <span className="text-rose-400">*</span>
                </span>
                <span className="text-[11px] text-dark-500 font-sans font-normal">
                  Filesystem path on machine running Spring Boot
                </span>
              </label>
              <div className="relative">
                <input
                  type="text"
                  required
                  value={repositoryPath}
                  onChange={(e) => setRepositoryPath(e.target.value)}
                  placeholder="e.g. C:/Users/developer/projects/TraceBack or /workspace/TraceBack"
                  className="w-full pl-9 pr-3.5 py-2.5 rounded-lg border border-dark-700 bg-dark-950 font-mono text-xs text-dark-100 placeholder:text-dark-600 focus:outline-none focus:border-brand-amber"
                />
                <FolderOpen className="w-4 h-4 text-dark-500 absolute left-3 top-3" />
              </div>
              <p className="text-[11px] text-dark-500">
                Must be an accessible directory containing a <code className="text-dark-400 font-mono">.git</code> folder on the Spring Boot server host (or container volume mount). Not a browser file upload.
              </p>
            </div>

            {/* Service & Environment */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <label className="block text-xs font-mono font-medium text-dark-200">
                  Service Name <span className="text-rose-400">*</span>
                </label>
                <div className="relative">
                  <input
                    type="text"
                    required
                    value={serviceName}
                    onChange={(e) => setServiceName(e.target.value)}
                    placeholder="order-service"
                    className="w-full pl-9 pr-3 py-2 rounded-lg border border-dark-700 bg-dark-950 font-mono text-xs text-dark-100 placeholder:text-dark-600 focus:outline-none focus:border-brand-amber"
                  />
                  <Server className="w-3.5 h-3.5 text-dark-500 absolute left-3 top-2.5" />
                </div>
              </div>

              <div className="space-y-1.5">
                <label className="block text-xs font-mono font-medium text-dark-200">
                  Environment <span className="text-rose-400">*</span>
                </label>
                <div className="relative">
                  <input
                    type="text"
                    required
                    value={environment}
                    onChange={(e) => setEnvironment(e.target.value)}
                    placeholder="production"
                    className="w-full pl-9 pr-3 py-2 rounded-lg border border-dark-700 bg-dark-950 font-mono text-xs text-dark-100 placeholder:text-dark-600 focus:outline-none focus:border-brand-amber"
                  />
                  <Layers className="w-3.5 h-3.5 text-dark-500 absolute left-3 top-2.5" />
                </div>
              </div>
            </div>

            {/* Crash Logs Input */}
            <div className="space-y-1.5">
              <label className="block text-xs font-mono font-medium text-dark-200 flex items-center justify-between">
                <span>Application Log Content</span>
                <span className="text-[11px] text-dark-500 font-sans font-normal">
                  Standard Spring multiline logs or JSON telemetry
                </span>
              </label>
              <textarea
                rows={5}
                value={logContent}
                onChange={(e) => setLogContent(e.target.value)}
                placeholder="2026-10-10 14:15:00.000 ERROR ... java.sql.SQLTimeoutException: Connection timeout after 30000ms."
                className="w-full p-3 rounded-lg border border-dark-700 bg-dark-950 font-mono text-xs text-dark-100 placeholder:text-dark-600 focus:outline-none focus:border-brand-amber leading-relaxed"
              />
            </div>

            {/* Stack Trace Input */}
            <div className="space-y-1.5">
              <label className="block text-xs font-mono font-medium text-dark-200 flex items-center justify-between">
                <span>Java Stack Trace (Optional / Root Cause Extract)</span>
                <span className="text-[11px] text-dark-500 font-sans font-normal">
                  Class, method, and line coordinate mapping
                </span>
              </label>
              <textarea
                rows={4}
                value={stackTrace}
                onChange={(e) => setStackTrace(e.target.value)}
                placeholder="com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
at com.traceback.order.OrderService.processPayment(OrderService.java:45)"
                className="w-full p-3 rounded-lg border border-dark-700 bg-dark-950 font-mono text-xs text-dark-100 placeholder:text-dark-600 focus:outline-none focus:border-brand-amber leading-relaxed"
              />
            </div>

            {/* Window & Commit Limits */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2 border-t border-dark-800">
              <div className="space-y-1">
                <label className="block text-xs font-mono text-dark-300">
                  Pre-Incident Window (Minutes)
                </label>
                <input
                  type="number"
                  min={5}
                  max={1440}
                  value={windowMinutes}
                  onChange={(e) => setWindowMinutes(Number(e.target.value))}
                  className="w-full px-3 py-1.5 rounded-lg border border-dark-700 bg-dark-950 font-mono text-xs text-dark-100 focus:outline-none focus:border-brand-amber"
                />
              </div>

              <div className="space-y-1">
                <label className="block text-xs font-mono text-dark-300">
                  Max Commits to Examine
                </label>
                <input
                  type="number"
                  min={1}
                  max={500}
                  value={maxCommits}
                  onChange={(e) => setMaxCommits(Number(e.target.value))}
                  className="w-full px-3 py-1.5 rounded-lg border border-dark-700 bg-dark-950 font-mono text-xs text-dark-100 focus:outline-none focus:border-brand-amber"
                />
              </div>
            </div>
          </>
        )}

        {/* Submit Button */}
        <div className="pt-4 border-t border-dark-700/80 flex items-center justify-between">
          <span className="text-[11px] font-mono text-dark-500">
            Calls POST /api/investigations/agent &bull; Grounded in deterministic registry
          </span>

          <button
            type="submit"
            disabled={isSubmitting}
            className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl font-mono text-xs font-semibold bg-brand-amber text-dark-950 hover:bg-amber-400 transition-colors shadow-md disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSubmitting ? (
              <>
                <LoadingSpinner size="sm" className="p-0 text-dark-950" />
                <span>Autonomous Agent Investigating...</span>
              </>
            ) : (
              <>
                <Play className="w-4 h-4 fill-current" />
                <span>Start Investigation</span>
              </>
            )}
          </button>
        </div>
      </form>
    </div>
  );
};
