import React, { useState, useMemo } from 'react';
import { Maximize2, Minimize2, Trash2, Code, CheckCircle2, AlertCircle } from 'lucide-react';

interface StackTraceEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  isOptional?: boolean;
}

interface ParsedTraceInfo {
  exceptionType?: string;
  message?: string;
  topFrame?: {
    className: string;
    methodName: string;
    fileName: string;
    lineNumber: number;
  };
  frameCount: number;
}

export const StackTraceEditor: React.FC<StackTraceEditorProps> = ({
  value,
  onChange,
  placeholder,
  isOptional = false,
}) => {
  const [isExpanded, setIsExpanded] = useState(false);

  const lineCount = value.trim() ? value.trim().split('\n').length : 0;

  // Genuine stack trace parsing without inventing data
  const parsedInfo: ParsedTraceInfo | null = useMemo(() => {
    const text = value.trim();
    if (!text) return null;

    let exceptionType: string | undefined;
    let message: string | undefined;
    let topFrame: ParsedTraceInfo['topFrame'] | undefined;
    let frameCount = 0;

    const lines = text.split('\n');

    // Regex for exception type and message
    const exceptionRegex = /([a-zA-Z0-9_$.]+(?:Exception|Error|Throwable))(?::\s*(.*))?/;
    // Regex for stack frame: at com.example.MyClass.myMethod(MyClass.java:42)
    const frameRegex = /at\s+([a-zA-Z0-9_$.]+)\.([a-zA-Z0-9_$]+)\(([^:]+):(\d+)\)/;

    for (const line of lines) {
      const trimmed = line.trim();
      if (!exceptionType) {
        const exMatch = trimmed.match(exceptionRegex);
        if (exMatch) {
          exceptionType = exMatch[1];
          message = exMatch[2]?.trim();
        }
      }

      const frameMatch = trimmed.match(frameRegex);
      if (frameMatch) {
        frameCount++;
        if (!topFrame) {
          topFrame = {
            className: frameMatch[1],
            methodName: frameMatch[2],
            fileName: frameMatch[3],
            lineNumber: parseInt(frameMatch[4], 10),
          };
        }
      }
    }

    if (!exceptionType && !topFrame && frameCount === 0) {
      return null;
    }

    return {
      exceptionType,
      message,
      topFrame,
      frameCount,
    };
  }, [value]);

  return (
    <div className="rounded-lg border border-[#2A323C] bg-[#0C1014] overflow-hidden focus-within:border-[#7FA3C4] transition-colors font-mono">
      {/* Editor Header Bar */}
      <div className="px-3.5 py-2 bg-[#0E1216] border-b border-[#1F262E] flex items-center justify-between text-xs select-none">
        <div className="flex items-center gap-2 text-[#B0B8C4]">
          <Code className="w-3.5 h-3.5 text-[#7FA3C4]" />
          <span className="font-semibold text-[#E4E8EE]">Java Stack Trace</span>
          {isOptional ? (
            <span className="text-[10px] text-[#B0B8C4] px-1.5 py-0.2 rounded bg-[#1A2129] border border-[#2A323C]">
              Optional
            </span>
          ) : (
            <span className="text-[10px] text-[#828D9E]">
              Root Cause Telemetry
            </span>
          )}
        </div>

        <div className="flex items-center gap-2">
          {lineCount > 0 && (
            <span className="text-[10px] text-[#828D9E]">
              {lineCount} {lineCount === 1 ? 'line' : 'lines'}
            </span>
          )}

          {value && (
            <button
              type="button"
              onClick={() => onChange('')}
              className="p-1 rounded text-[#828D9E] hover:text-[#C86A6A] hover:bg-[#1A2129] transition-colors"
              title="Clear Stack Trace"
            >
              <Trash2 className="w-3 h-3" />
            </button>
          )}

          <button
            type="button"
            onClick={() => setIsExpanded(!isExpanded)}
            className="p-1 rounded text-[#828D9E] hover:text-[#E4E8EE] hover:bg-[#1A2129] transition-colors flex items-center gap-1 text-[11px]"
            title={isExpanded ? 'Collapse Editor' : 'Expand Editor'}
          >
            {isExpanded ? (
              <>
                <Minimize2 className="w-3 h-3" />
                <span className="hidden sm:inline">Compact</span>
              </>
            ) : (
              <>
                <Maximize2 className="w-3 h-3" />
                <span className="hidden sm:inline">Expand</span>
              </>
            )}
          </button>
        </div>
      </div>

      {/* Editor Body */}
      <div className="relative">
        <textarea
          rows={isExpanded ? 12 : 5}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={
            placeholder ||
            'com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)\nat com.traceback.order.OrderService.processPayment(OrderService.java:45)'
          }
          className="w-full p-3 bg-[#0C1014] font-mono text-xs text-[#E4E8EE] placeholder:text-[#828D9E] focus:outline-none resize-y leading-relaxed"
          spellCheck={false}
        />
      </div>

      {/* Stack Trace Parsing Preview */}
      {value.trim() ? (
        parsedInfo && (parsedInfo.exceptionType || parsedInfo.topFrame) ? (
          <div className="px-3.5 py-2 bg-[#141A20] border-t border-[#1F262E] flex flex-wrap items-center justify-between gap-2 text-xs">
            <div className="flex items-center gap-2 truncate">
              <span className="inline-flex items-center gap-1 text-[10px] font-bold text-[#4FA88A] px-1.5 py-0.5 rounded bg-[#4FA88A]/10 border border-[#4FA88A]/30 shrink-0">
                <CheckCircle2 className="w-2.5 h-2.5" />
                Parsed Telemetry
              </span>
              {parsedInfo.exceptionType && (
                <span className="text-[#E4E8EE] font-semibold truncate">
                  {parsedInfo.exceptionType}
                </span>
              )}
              {parsedInfo.topFrame && (
                <span className="text-[#7FA3C4] text-[11px] truncate">
                  at {parsedInfo.topFrame.fileName}:{parsedInfo.topFrame.lineNumber}
                </span>
              )}
            </div>
            {parsedInfo.frameCount > 0 && (
              <span className="text-[10px] text-[#828D9E] shrink-0">
                {parsedInfo.frameCount} frame{parsedInfo.frameCount === 1 ? '' : 's'} detected
              </span>
            )}
          </div>
        ) : (
          <div className="px-3.5 py-1.5 bg-[#141A20] border-t border-[#1F262E] flex items-center gap-1.5 text-[11px] text-[#828D9E]">
            <AlertCircle className="w-3 h-3 text-[#C9A24A]" />
            <span>Raw text entered — no standard Java stack frame pattern detected yet.</span>
          </div>
        )
      ) : (
        <div className="px-3 py-1.5 bg-[#0E1216] border-t border-[#1F262E] flex items-center justify-between text-[11px] font-mono text-[#828D9E]">
          <span>Contributes class, method &amp; line coordinate evidence</span>
          <span className="text-[10px] text-[#828D9E]">UTF-8 &bull; Monospace</span>
        </div>
      )}
    </div>
  );
};
