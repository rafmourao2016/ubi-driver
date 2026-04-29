'use client';

import React, { useState, useEffect, useRef } from 'react';

interface LogEntry {
  time: string;
  msg: string;
}

export default function DiagLog() {
  const [logs, setLogs] = useState<LogEntry[]>([{ time: now(), msg: '🟢 Log viewer ativo — aguardando eventos...' }]);
  const [visible, setVisible] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  function now() {
    return new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }

  useEffect(() => {
    if (typeof window === 'undefined' || !(window as any).Capacitor) return;
    const plugin = (window as any).Capacitor.Plugins.GigUPlugin;
    if (!plugin) return;

    const listener = plugin.addListener('onDiagLog', (data: { msg: string }) => {
      setLogs(prev => [...prev.slice(-49), { time: now(), msg: data.msg }]);
    });

    // Também captura onUberOffer para mostrar o que o serviço detectou
    const rideListener = plugin.addListener('onUberOffer', (data: { price: number; km: number }) => {
      setLogs(prev => [
        ...prev.slice(-49),
        { time: now(), msg: `📡 onUberOffer recebido: R$${data.price?.toFixed(2)} | ${data.km?.toFixed(1)} km` }
      ]);
    });

    return () => {
      listener?.remove?.();
      rideListener?.remove?.();
    };
  }, []);

  useEffect(() => {
    if (visible) bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs, visible]);

  const colorFor = (msg: string) => {
    if (msg.includes('[OFERTA]') || msg.includes('onUberOffer')) return '#6ee7b7';
    if (msg.includes('[ERRO]') || msg.includes('null')) return '#fca5a5';
    if (msg.includes('[LEITOR]') || msg.includes('EMITINDO')) return '#c084fc';
    return '#9ca3af';
  };

  return (
    <div style={{ marginTop: 16 }}>
      {/* Toggle button */}
      <button
        onClick={() => setVisible(v => !v)}
        style={{
          width: '100%', padding: '10px', borderRadius: 14, border: '1px solid rgba(255,255,255,0.08)',
          background: 'rgba(255,255,255,0.03)', color: '#6b7280',
          fontFamily: 'Outfit, sans-serif', fontWeight: 700, fontSize: '0.75rem',
          cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
          textTransform: 'uppercase', letterSpacing: '0.06em',
        }}
      >
        <span style={{ fontSize: 10 }}>{visible ? '▲' : '▼'}</span>
        🔍 {visible ? 'Fechar' : 'Ver'} Log de Diagnóstico ({logs.length})
      </button>

      {/* Log panel */}
      {visible && (
        <div style={{
          marginTop: 8, borderRadius: 14, background: '#0a0a12',
          border: '1px solid rgba(255,255,255,0.06)',
          padding: '12px', maxHeight: 260, overflowY: 'auto',
          fontFamily: 'monospace',
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
            <span style={{ fontSize: '0.65rem', color: '#374151', textTransform: 'uppercase', letterSpacing: '0.08em' }}>
              Console de Diagnóstico
            </span>
            <button
              onClick={() => setLogs([{ time: now(), msg: '🗑️ Log limpo' }])}
              style={{ background: 'none', border: 'none', color: '#4b5563', cursor: 'pointer', fontSize: '0.7rem' }}
            >
              Limpar
            </button>
          </div>
          {logs.map((entry, i) => (
            <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 4, alignItems: 'flex-start' }}>
              <span style={{ fontSize: '0.6rem', color: '#374151', flexShrink: 0, paddingTop: 2 }}>
                {entry.time}
              </span>
              <span style={{ fontSize: '0.72rem', color: colorFor(entry.msg), lineHeight: 1.4, wordBreak: 'break-all' }}>
                {entry.msg}
              </span>
            </div>
          ))}
          <div ref={bottomRef} />
        </div>
      )}
    </div>
  );
}
