import React, { useState } from 'react';
import { Copy, Check } from 'lucide-react';

interface CodeBlockProps {
  code: string;
  title?: string;
  language?: string;
  maxHeight?: string;
  className?: string;
}

export const CodeBlock: React.FC<CodeBlockProps> = ({
  code,
  title,
  language,
  maxHeight = 'max-h-80',
  className = '',
}) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // fallback
    }
  };

  return (
    <div className={`rounded-lg border border-dark-700 bg-dark-950 overflow-hidden font-mono text-xs ${className}`}>
      {(title || language) && (
        <div className="flex items-center justify-between px-3 py-1.5 border-b border-dark-700 bg-dark-900/80 text-dark-400">
          <div className="flex items-center gap-2">
            {title && <span className="font-medium text-dark-300">{title}</span>}
            {language && <span className="text-[10px] uppercase text-dark-500">{language}</span>}
          </div>
          <button
            onClick={handleCopy}
            className="flex items-center gap-1 text-[11px] hover:text-dark-200 transition-colors py-0.5 px-1.5 rounded bg-dark-800 border border-dark-700"
            title="Copy code"
          >
            {copied ? (
              <>
                <Check className="w-3.5 h-3.5 text-emerald-400" />
                <span className="text-emerald-400">Copied</span>
              </>
            ) : (
              <>
                <Copy className="w-3.5 h-3.5" />
                <span>Copy</span>
              </>
            )}
          </button>
        </div>
      )}
      <div className={`p-3 overflow-x-auto ${maxHeight} text-dark-200 leading-relaxed whitespace-pre font-mono`}>
        {code}
      </div>
    </div>
  );
};
