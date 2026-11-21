import React, { useState, useEffect, useMemo } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import {
  Play,
  Sparkles,
  Server,
  Layers,
  AlertCircle,
  FolderOpen,
  GitBranch,
  Terminal,
  Info,
  ChevronRight,
  Clock,
  Sliders,
  ChevronDown,
  ChevronUp,
  CheckCircle2,
} from 'lucide-react';
import { investigationApi } from '../api/investigationApi';
import type { InvestigationMode } from '../api/types';
import { LoadingSpinner } from '../components/shared/LoadingSpinner';
import { StackTraceEditor } from '../components/investigation/StackTraceEditor';
import { InvestigationOverviewPanel } from '../components/investigation/InvestigationOverviewPanel';

export const NewInvestigationPage: React.FC = () => {
  const navigate = useNavigate();

  // Workflow selection
  const [workflow, setWorkflow] = useState<'new' | 'existing'>('new');

  // Investigation Mode
  const [investigationMode, setInvestigationMode] = useState<InvestigationMode>('RUNTIME_FAILURE');

  // Form State: genuine empty values with honest placeholders
  const [repositoryPath, setRepositoryPath] = useState('');
  const [serviceName, setServiceName] = useState('');
  const [environment, setEnvironment] = useState('');
  const [logContent, setLogContent] = useState('');
  const [stackTrace, setStackTrace] = useState('');
  const [windowMinutes, setWindowMinutes] = useState(60);
  const [maxCommits, setMaxCommits] = useState(50);
  const [showAdvanced, setShowAdvanced] = useState(false);

  // Autocomplete suggestions loaded from existing investigations
  const [knownServices, setKnownServices] = useState<string[]>([]);
  const [knownEnvironments, setKnownEnvironments] = useState<string[]>([]);

  // Existing workflow state
  const [existingId, setExistingId] = useState('');

  // Execution & Error State
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [repoPathTouched, setRepoPathTouched] = useState(false);
  const [hasAttemptedSubmit, setHasAttemptedSubmit] = useState(false);

  // Load existing services & environments for datalist autocomplete
  useEffect(() => {
    if (typeof investigationApi.getAllInvestigations === 'function') {
      investigationApi
        .getAllInvestigations()
        .then((invs) => {
          if (Array.isArray(invs)) {
            const services = Array.from(new Set(invs.map((i) => i.serviceName).filter(Boolean)));
            const envs = Array.from(new Set(invs.map((i) => i.environment).filter(Boolean)));
            setKnownServices(services);
            setKnownEnvironments(envs);
          }
        })
        .catch(() => {});
    }
  }, []);

  // Quick Preset Handlers (Demo Fixtures)
  const defaultExampleRepoPath =
    import.meta.env.VITE_DEFAULT_REPO_PATH || 'C:/Users/developer/projects/TraceBack';

  const loadHikariCPExample = () => {
    setInvestigationMode('RUNTIME_FAILURE');
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
    setInvestigationMode('RUNTIME_FAILURE');
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

  // Validation readiness
  const validationErrors = useMemo(() => {
    const errors: string[] = [];
    if (workflow === 'new') {
      if (!repositoryPath.trim()) errors.push('Local path is required to start investigation');
      if (investigationMode === 'RUNTIME_FAILURE' && !logContent.trim() && !stackTrace.trim()) {
        errors.push('Provide either log content or a stack trace for runtime failure investigations');
      }
    } else {
      if (!existingId.trim()) errors.push('Enter a valid Investigation ID');
    }
    return errors;
  }, [workflow, repositoryPath, investigationMode, logContent, stackTrace, existingId]);

  const isFormValid = validationErrors.length === 0;

  const showRepoPathError =
    (hasAttemptedSubmit || repoPathTouched) && workflow === 'new' && !repositoryPath.trim();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setHasAttemptedSubmit(true);
    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      if (workflow === 'existing') {
        const trimmed = existingId.trim();
        if (!trimmed) {
          throw new Error('Please enter a valid Investigation ID to analyze');
        }
        const report = await investigationApi.startAgentInvestigation({
          investigationId: trimmed,
        });
        const targetId = report.investigationId || trimmed;
        navigate(`/investigations/${targetId}`);
        return;
      }

      // Validation
      if (!repositoryPath.trim()) {
        throw new Error('Local repository path is required');
      }

      if (investigationMode === 'RUNTIME_FAILURE' && !logContent.trim() && !stackTrace.trim()) {
        throw new Error('Please provide either log content or a stack trace for runtime failure investigations');
      }

      const report = await investigationApi.startAgentInvestigation({
        repositoryPath: repositoryPath.trim(),
        serviceName: serviceName.trim() || 'order-service',
        environment: environment.trim() || 'production',
        logContent: logContent.trim() || undefined,
        stackTrace: stackTrace.trim() || undefined,
        windowMinutes: Number(windowMinutes) || 60,
        maxCommits: Number(maxCommits) || 50,
        investigationMode,
      });

      if (!report.investigationId) {
        throw new Error('Backend did not return an investigationId');
      }

      navigate(`/investigations/${report.investigationId}`);
    } catch (err: unknown) {
      setErrorMessage(err instanceof Error ? err.message : 'Investigation failed to start');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="max-w-7xl mx-auto space-y-6 font-sans">
      {/* Top Header & Breadcrumb */}
      <div className="space-y-2">
        <nav aria-label="Breadcrumb" className="flex items-center gap-2 text-xs font-mono text-[#828D9E]">
          <Link to="/dashboard" className="hover:text-[#7FA3C4] transition-colors">
            Platform
          </Link>
          <ChevronRight className="w-3 h-3 text-[#434D5C]" />
          <Link to="/history" className="hover:text-[#7FA3C4] transition-colors">
            Investigations
          </Link>
          <ChevronRight className="w-3 h-3 text-[#434D5C]" />
          <span className="text-[#E4E8EE] font-medium">New Investigation</span>
        </nav>

        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h1 className="text-xl font-mono font-bold text-[#E4E8EE] tracking-tight flex items-center gap-2">
              <Sparkles className="w-5 h-5 text-[#7FA3C4]" />
              <span>New Investigation</span>
              <span className="text-xs font-normal text-[#B0B8C4] font-mono px-2 py-0.5 rounded bg-[#1A2129] border border-[#2A323C]">
                Launch Failure Investigation
              </span>
            </h1>
            <p className="text-xs text-[#B0B8C4] mt-0.5 font-sans">
              Provide the failure details and repository context to begin a structured investigation.
            </p>
          </div>

          {/* Workflow Mode Switcher */}
          <div className="flex items-center p-1 rounded-lg bg-[#0E1216] border border-[#2A323C] font-mono text-xs">
            <button
              type="button"
              onClick={() => setWorkflow('new')}
              className={`px-3 py-1.5 rounded-md transition-colors cursor-pointer ${
                workflow === 'new'
                  ? 'bg-[#1A2129] text-[#E4E8EE] border border-[#7FA3C4]/50 font-medium shadow-xs'
                  : 'text-[#828D9E] hover:text-[#E4E8EE]'
              }`}
            >
              <span className="sr-only">Workflow A: New Analysis</span>
              <span>New Repository Analysis</span>
            </button>
            <button
              type="button"
              onClick={() => setWorkflow('existing')}
              className={`px-3 py-1.5 rounded-md transition-colors cursor-pointer ${
                workflow === 'existing'
                  ? 'bg-[#1A2129] text-[#E4E8EE] border border-[#7FA3C4]/50 font-medium shadow-xs'
                  : 'text-[#828D9E] hover:text-[#E4E8EE]'
              }`}
            >
              <span className="sr-only">Workflow B: Existing ID</span>
              <span>Re-analyze Existing ID</span>
            </button>
          </div>
        </div>
      </div>

      {/* Error Alert */}
      {errorMessage && (
        <div className="p-4 rounded-xl border border-[#C86A6A]/40 bg-[#141A20] text-xs text-[#C86A6A] flex items-start gap-3 border-l-[3px] border-l-[#C86A6A] shadow-xs">
          <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
          <div className="space-y-0.5">
            <strong className="font-semibold block font-mono">Submission Error:</strong>
            <span className="font-sans leading-relaxed">{errorMessage}</span>
          </div>
        </div>
      )}

      {/* Autocomplete Datalists */}
      <datalist id="service-options">
        {knownServices.map((svc) => (
          <option key={svc} value={svc} />
        ))}
        {knownServices.length === 0 && (
          <>
            <option value="order-service" />
            <option value="payment-service" />
            <option value="auth-service" />
          </>
        )}
      </datalist>

      <datalist id="env-options">
        {knownEnvironments.map((env) => (
          <option key={env} value={env} />
        ))}
        {knownEnvironments.length === 0 && (
          <>
            <option value="production" />
            <option value="staging" />
            <option value="development" />
          </>
        )}
      </datalist>

      {/* Main Two-Column Investigation Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start pb-20">
        {/* Left Column (Main Form): 8 cols */}
        <div className="lg:col-span-8 space-y-6">
          <form onSubmit={handleSubmit} noValidate className="space-y-6">
            {workflow === 'existing' ? (
              /* Workflow B: Existing Investigation ID */
              <div className="rounded-xl border border-[#2A323C] bg-[#141A20] p-6 space-y-4 shadow-xs">
                <div className="flex items-center gap-2 pb-3 border-b border-[#1F262E]">
                  <Terminal className="w-4 h-4 text-[#7FA3C4]" />
                  <h2 className="text-sm font-mono font-bold text-[#E4E8EE]">
                    Re-Analyze Existing Investigation
                  </h2>
                </div>

                <div className="space-y-2">
                  <label className="block text-xs font-mono font-medium text-[#E4E8EE]">
                    Existing Investigation ID (UUID) <span className="text-[#C86A6A]">*</span>
                  </label>
                  <input
                    type="text"
                    required
                    value={existingId}
                    onChange={(e) => setExistingId(e.target.value)}
                    placeholder="e.g. 3ef41c49-ac62-467e-ac8f-eddd3581e154"
                    className="w-full px-3.5 py-2.5 rounded-lg border border-[#2A323C] bg-[#0C1014] font-mono text-xs text-[#E4E8EE] placeholder:text-[#828D9E] focus:outline-none focus:border-[#7FA3C4] transition-colors"
                  />
                  <p className="text-[11px] text-[#828D9E] font-sans">
                    Dispatches the autonomous AI agent on an existing deterministic investigation record to synthesize new hypotheses and claims.
                  </p>
                </div>
              </div>
            ) : (
              /* Workflow A: Full Investigation Setup */
              <>
                {/* 1. Target Repository (Prominent Anchor) */}
                <div className="rounded-xl border-2 border-[#3D4856] bg-[#141A20] p-6 space-y-4 shadow-sm">
                  <div className="flex items-center justify-between pb-3 border-b border-[#1F262E]">
                    <div className="flex items-center gap-2">
                      <FolderOpen className="w-4 h-4 text-[#7FA3C4]" />
                      <h2 className="text-sm font-mono font-bold text-[#E4E8EE]">
                        1. Target Repository <span className="text-[#C86A6A]">*</span>
                      </h2>
                    </div>
                    <span className="text-[10px] font-mono text-[#4FA88A] bg-[#4FA88A]/10 border border-[#4FA88A]/30 px-2 py-0.5 rounded">
                      Grounding Anchor
                    </span>
                  </div>

                  <div className="space-y-1.5">
                    <label className="block text-xs font-mono font-medium text-[#E4E8EE] flex items-center justify-between">
                      <span>Local Git Repository Path</span>
                      <span className="text-[11px] text-[#828D9E] font-sans font-normal">
                        Absolute filesystem path on the machine running Spring Boot
                      </span>
                    </label>
                    <div className="relative">
                      <input
                        type="text"
                        value={repositoryPath}
                        onChange={(e) => setRepositoryPath(e.target.value)}
                        onBlur={() => setRepoPathTouched(true)}
                        placeholder="e.g. C:/Users/developer/projects/TraceBack or /workspace/TraceBack"
                        className={`w-full pl-9 pr-3.5 py-2.5 rounded-lg border ${
                          showRepoPathError
                            ? 'border-[#C86A6A] focus:border-[#C86A6A]'
                            : 'border-[#2A323C] focus:border-[#7FA3C4]'
                        } bg-[#0C1014] font-mono text-xs text-[#E4E8EE] placeholder:text-[#828D9E] focus:outline-none transition-colors`}
                      />
                      <FolderOpen className="w-4 h-4 text-[#7FA3C4] absolute left-3 top-3" />
                    </div>

                    {showRepoPathError && (
                      <p className="text-xs text-[#C86A6A] flex items-center gap-1.5 font-sans">
                        <AlertCircle className="w-3.5 h-3.5 shrink-0" />
                        <span>Local path is required to start investigation</span>
                      </p>
                    )}

                    <p className="text-[11px] text-[#828D9E] font-sans">
                      Must contain a valid <code className="text-[#B0B8C4] font-mono">.git</code> folder. Commits and file trees are inspected directly.
                    </p>
                  </div>
                </div>

                {/* 2. Failure Details & Telemetry */}
                <div className="rounded-xl border border-[#2A323C] bg-[#141A20] p-6 space-y-5 shadow-xs">
                  <div className="flex items-center justify-between pb-3 border-b border-[#1F262E]">
                    <div className="flex items-center gap-2">
                      <Terminal className="w-4 h-4 text-[#7FA3C4]" />
                      <h2 className="text-sm font-mono font-bold text-[#E4E8EE]">
                        2. Failure Telemetry &amp; Service Scope
                      </h2>
                    </div>
                    <span className="text-[10px] font-mono text-[#828D9E]">
                      Primary Diagnostic Focus
                    </span>
                  </div>

                  {/* Service & Environment Inputs with Autocomplete */}
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div className="space-y-1.5">
                      <label className="block text-xs font-mono font-medium text-[#E4E8EE]">
                        Service Name
                      </label>
                      <div className="relative">
                        <input
                          type="text"
                          list="service-options"
                          value={serviceName}
                          onChange={(e) => setServiceName(e.target.value)}
                          placeholder="e.g. order-service"
                          className="w-full pl-9 pr-3 py-2.5 rounded-lg border border-[#2A323C] bg-[#0C1014] font-mono text-xs text-[#E4E8EE] placeholder:text-[#828D9E] focus:outline-none focus:border-[#7FA3C4] transition-colors"
                        />
                        <Server className="w-3.5 h-3.5 text-[#828D9E] absolute left-3 top-3" />
                      </div>
                      <span className="text-[10px] text-[#828D9E]">Type or select an existing service (defaults to order-service)</span>
                    </div>

                    <div className="space-y-1.5">
                      <label className="block text-xs font-mono font-medium text-[#E4E8EE]">
                        Environment
                      </label>
                      <div className="relative">
                        <input
                          type="text"
                          list="env-options"
                          value={environment}
                          onChange={(e) => setEnvironment(e.target.value)}
                          placeholder="e.g. production"
                          className="w-full pl-9 pr-3 py-2.5 rounded-lg border border-[#2A323C] bg-[#0C1014] font-mono text-xs text-[#E4E8EE] placeholder:text-[#828D9E] focus:outline-none focus:border-[#7FA3C4] transition-colors"
                        />
                        <Layers className="w-3.5 h-3.5 text-[#828D9E] absolute left-3 top-3" />
                      </div>
                      <span className="text-[10px] text-[#828D9E]">Target deployment environment (defaults to production)</span>
                    </div>
                  </div>

                  {/* Mode Selector Toggle with High-Visibility Cards */}
                  <div className="space-y-2">
                    <label className="block text-xs font-mono font-medium text-[#E4E8EE]">
                      Investigation Mode <span className="text-[#C86A6A]">*</span>
                    </label>
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                      <button
                        type="button"
                        onClick={() => setInvestigationMode('RUNTIME_FAILURE')}
                        className={`p-4 rounded-xl text-left border-2 transition-all cursor-pointer ${
                          investigationMode === 'RUNTIME_FAILURE'
                            ? 'border-[#7FA3C4] bg-[#1A2129] text-[#E4E8EE] ring-1 ring-[#7FA3C4]/40 shadow-sm'
                            : 'border-[#2A323C] bg-[#0E1216] text-[#B0B8C4] hover:border-[#3D4856]'
                        }`}
                      >
                        <div className="flex items-center justify-between mb-1.5">
                          <span className="font-mono text-xs font-bold flex items-center gap-2 text-[#E4E8EE]">
                            <Terminal className="w-4 h-4 text-[#7FA3C4] shrink-0" />
                            Runtime Failure
                          </span>
                          <span
                            className={`text-[10px] font-mono px-2 py-0.5 rounded border ${
                              investigationMode === 'RUNTIME_FAILURE'
                                ? 'bg-[#7FA3C4]/20 text-[#7FA3C4] border-[#7FA3C4]/40 font-bold'
                                : 'bg-[#1A2129] text-[#828D9E] border-[#2A323C]'
                            }`}
                          >
                            Telemetry Required
                          </span>
                        </div>
                        <p className="text-[11px] text-[#828D9E] font-sans leading-snug">
                          Correlate runtime error logs, exception stack traces, and commits to determine root cause.
                        </p>
                      </button>

                      <button
                        type="button"
                        onClick={() => setInvestigationMode('WORKING_TREE_FORENSICS')}
                        className={`p-4 rounded-xl text-left border-2 transition-all cursor-pointer ${
                          investigationMode === 'WORKING_TREE_FORENSICS'
                            ? 'border-[#7FA3C4] bg-[#1A2129] text-[#E4E8EE] ring-1 ring-[#7FA3C4]/40 shadow-sm'
                            : 'border-[#2A323C] bg-[#0E1216] text-[#B0B8C4] hover:border-[#3D4856]'
                        }`}
                      >
                        <div className="flex items-center justify-between mb-1.5">
                          <span className="font-mono text-xs font-bold flex items-center gap-2 text-[#E4E8EE]">
                            <GitBranch className="w-4 h-4 text-[#7FA3C4] shrink-0" />
                            Working-Tree Forensics
                          </span>
                          <span
                            className={`text-[10px] font-mono px-2 py-0.5 rounded border ${
                              investigationMode === 'WORKING_TREE_FORENSICS'
                                ? 'bg-[#7FA3C4]/20 text-[#7FA3C4] border-[#7FA3C4]/40 font-bold'
                                : 'bg-[#1A2129] text-[#828D9E] border-[#2A323C]'
                            }`}
                          >
                            Logs Optional
                          </span>
                        </div>
                        <p className="text-[11px] text-[#828D9E] font-sans leading-snug">
                          Investigate uncommitted repository modifications, missing tracked files, and directory anomalies without logs.
                        </p>
                      </button>
                    </div>

                    {investigationMode === 'WORKING_TREE_FORENSICS' && (
                      <div className="p-3.5 rounded-lg border border-[#2A323C] border-l-[3px] border-l-[#7FA3C4] bg-[#141A20] text-xs text-[#B0B8C4] flex items-start gap-2.5 mt-2">
                        <Info className="w-4 h-4 text-[#7FA3C4] shrink-0 mt-0.5" />
                        <div>
                          <strong className="font-semibold block font-mono text-[11px] text-[#E4E8EE]">
                            Working-Tree Forensics Mode Active
                          </strong>
                          <span className="text-[11px] text-[#828D9E] font-sans">
                            Application logs and stack traces are optional. TraceBack will inspect the working tree and compare local disk state against Git HEAD to detect missing tracked files, unstaged modifications, and directory anomalies.
                          </span>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Stack Trace Editor with Live Client-Side Parsing Preview */}
                  <div className="space-y-1.5">
                    <StackTraceEditor
                      value={stackTrace}
                      onChange={setStackTrace}
                      isOptional={investigationMode === 'WORKING_TREE_FORENSICS'}
                      placeholder={
                        investigationMode === 'WORKING_TREE_FORENSICS'
                          ? 'Optional in working-tree mode. Leave empty for repository-only forensics, or paste stack trace if available.'
                          : 'com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)\nat com.traceback.order.OrderService.processPayment(OrderService.java:45)'
                      }
                    />
                  </div>

                  {/* Application Log Content Input */}
                  <div className="space-y-1.5">
                    <label className="block text-xs font-mono font-medium text-[#E4E8EE] flex items-center justify-between">
                      <span>
                        Application Log Content{' '}
                        {investigationMode === 'WORKING_TREE_FORENSICS' ? (
                          <span className="text-[#7FA3C4] font-normal font-sans">(Optional)</span>
                        ) : (
                          <span className="text-[#828D9E] font-normal font-sans">
                            (Required if stack trace absent)
                          </span>
                        )}
                      </span>
                      <span className="text-[11px] text-[#828D9E] font-sans font-normal">
                        Contributes error signature &amp; timestamp evidence
                      </span>
                    </label>
                    <textarea
                      rows={4}
                      value={logContent}
                      onChange={(e) => setLogContent(e.target.value)}
                      placeholder={
                        investigationMode === 'WORKING_TREE_FORENSICS'
                          ? 'Optional in working-tree mode. Leave empty for repository-only investigation, or paste logs if available.'
                          : '2026-10-10 14:15:00.000 ERROR ... java.sql.SQLTimeoutException: Connection timeout after 30000ms.'
                      }
                      className="w-full p-3 rounded-lg border border-[#2A323C] bg-[#0C1014] font-mono text-xs text-[#E4E8EE] placeholder:text-[#828D9E] focus:outline-none focus:border-[#7FA3C4] transition-colors leading-relaxed"
                    />
                  </div>
                </div>

                {/* 3. Advanced Configuration (Collapsible) */}
                <div className="rounded-xl border border-[#2A323C] bg-[#141A20] p-5 shadow-xs space-y-4">
                  <button
                    type="button"
                    onClick={() => setShowAdvanced(!showAdvanced)}
                    className="w-full flex items-center justify-between text-xs font-mono text-[#E4E8EE] hover:text-[#7FA3C4] transition-colors cursor-pointer"
                  >
                    <div className="flex items-center gap-2">
                      <Sliders className="w-4 h-4 text-[#7FA3C4]" />
                      <span className="font-bold">Advanced Tuning Parameters</span>
                      <span className="text-[10px] text-[#828D9E]">
                        (Search Depth: {maxCommits} commits, Window: {windowMinutes}m)
                      </span>
                    </div>
                    {showAdvanced ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                  </button>

                  {showAdvanced && (
                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-3 border-t border-[#1F262E]">
                      <div className="space-y-1">
                        <label className="block text-xs font-mono font-medium text-[#B0B8C4] flex items-center gap-1.5">
                          <Clock className="w-3.5 h-3.5 text-[#7FA3C4]" />
                          <span>Pre-Incident Window (Minutes)</span>
                        </label>
                        <input
                          type="number"
                          min={5}
                          max={1440}
                          value={windowMinutes}
                          onChange={(e) => setWindowMinutes(Number(e.target.value))}
                          className="w-full px-3 py-2 rounded-lg border border-[#2A323C] bg-[#0C1014] font-mono text-xs text-[#E4E8EE] focus:outline-none focus:border-[#7FA3C4] transition-colors"
                        />
                        <p className="text-[10px] text-[#828D9E] font-sans">
                          Commits within window receive temporal proximity scoring (&le;15m bonus).
                        </p>
                      </div>

                      <div className="space-y-1">
                        <label className="block text-xs font-mono font-medium text-[#B0B8C4] flex items-center gap-1.5">
                          <Sliders className="w-3.5 h-3.5 text-[#7FA3C4]" />
                          <span>Max Commits to Examine</span>
                        </label>
                        <input
                          type="number"
                          min={1}
                          max={500}
                          value={maxCommits}
                          onChange={(e) => setMaxCommits(Number(e.target.value))}
                          className="w-full px-3 py-2 rounded-lg border border-[#2A323C] bg-[#0C1014] font-mono text-xs text-[#E4E8EE] focus:outline-none focus:border-[#7FA3C4] transition-colors"
                        />
                        <p className="text-[10px] text-[#828D9E] font-sans">
                          Bounds the commit search depth to preserve analysis performance.
                        </p>
                      </div>
                    </div>
                  )}
                </div>
              </>
            )}

            {/* Action Footer Bar */}
            <div className="p-4 rounded-xl border border-[#2A323C] bg-[#141A20] flex flex-wrap items-center justify-between gap-4 shadow-xs">
              <div className="flex items-center gap-2 text-xs font-mono">
                <span
                  className={`w-2.5 h-2.5 rounded-full shrink-0 ${
                    isFormValid ? 'bg-[#4FA88A]' : 'bg-[#828D9E]'
                  }`}
                />
                <span className="text-[#B0B8C4]">
                  {isFormValid ? (
                    <span className="text-[#4FA88A] font-semibold flex items-center gap-1">
                      <CheckCircle2 className="w-3.5 h-3.5 inline" /> Ready to dispatch investigation
                    </span>
                  ) : (
                    <span className="text-[#828D9E]">
                      Awaiting required inputs
                    </span>
                  )}
                </span>
              </div>

              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => navigate('/dashboard')}
                  className="px-4 py-2.5 rounded-lg font-sans text-xs text-[#B0B8C4] hover:text-[#E4E8EE] bg-transparent hover:bg-[#1A2129] border border-[#2A323C] transition-colors cursor-pointer"
                >
                  Cancel
                </button>

                <button
                  type="submit"
                  disabled={isSubmitting}
                  className="inline-flex items-center gap-2 px-6 py-2.5 rounded-lg font-sans text-xs font-medium bg-[#3A6C96] hover:bg-[#457CAB] text-white transition-colors shadow-xs disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                >
                  {isSubmitting ? (
                    <>
                      <LoadingSpinner size="sm" className="p-0 text-white" />
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
            </div>
          </form>
        </div>

        {/* Right Column (Contextual Overview Panel): 4 cols */}
        <div className="lg:col-span-4 sticky top-20">
          <InvestigationOverviewPanel
            serviceName={serviceName}
            environment={environment}
            investigationMode={investigationMode}
            repositoryPath={repositoryPath}
            windowMinutes={windowMinutes}
            maxCommits={maxCommits}
            hasStackTrace={!!stackTrace.trim()}
            hasLogContent={!!logContent.trim()}
            onLoadHikariCP={loadHikariCPExample}
            onLoadPaymentRegression={loadPaymentRegressionExample}
          />
        </div>
      </div>
    </div>
  );
};
