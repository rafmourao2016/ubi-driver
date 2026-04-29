'use client';

import React, { useState, useEffect } from 'react';

interface PiPModeProps {
  netProfit: number;
  margin: number;
  profitPerKm: number;
  dailyGoal: number;
  dailyAccumulated: number;
}

interface OverlayPluginInterface {
  showOverlay: (data: {
    netProfit: number; margin: number; profitPerKm: number;
    dailyGoal: number; dailyAccumulated: number;
  }) => Promise<void>;
  updateOverlay: (data: {
    netProfit: number; margin: number; profitPerKm: number;
    dailyGoal: number; dailyAccumulated: number;
  }) => Promise<void>;
  hideOverlay: () => Promise<void>;
}

function getOverlayPlugin(): OverlayPluginInterface | null {
  if (typeof window !== 'undefined' && (window as any).Capacitor) {
    return (window as any).Capacitor.Plugins.OverlayPlugin as OverlayPluginInterface;
  }
  return null;
}

export default function PiPMode({ netProfit, margin, profitPerKm, dailyGoal, dailyAccumulated }: PiPModeProps) {
  const [isOverlayActive, setIsOverlayActive] = useState(false);
  const [isNative, setIsNative] = useState(false);
  const [isBrowserPiPSupported, setIsBrowserPiPSupported] = useState(false);

  useEffect(() => {
    const native = typeof window !== 'undefined' && !!(window as any).Capacitor;
    setIsNative(native);
    setIsBrowserPiPSupported(!native && 'documentPictureInPicture' in window);
  }, []);

  // Atualiza overlay em tempo real quando dados mudam
  useEffect(() => {
    if (!isOverlayActive) return;
    const plugin = getOverlayPlugin();
    if (plugin) {
      plugin.updateOverlay({ netProfit, margin, profitPerKm, dailyGoal, dailyAccumulated })
        .catch(console.error);
    }
  }, [netProfit, margin, profitPerKm, dailyGoal, dailyAccumulated, isOverlayActive]);

  const toggleNativeOverlay = async () => {
    const plugin = getOverlayPlugin();
    if (!plugin) return;
    try {
      if (isOverlayActive) {
        await plugin.hideOverlay();
        setIsOverlayActive(false);
      } else {
        await plugin.showOverlay({ netProfit, margin, profitPerKm, dailyGoal, dailyAccumulated });
        setIsOverlayActive(true);
      }
    } catch (err: any) {
      if (err?.message?.includes('permissão') || err?.message?.includes('Permissão')) {
        alert('Ative a permissão "Aparecer sobre outros apps" para o Ubi nas configurações do Android.');
      }
      console.error('Overlay error:', err);
    }
  };

  const toggleBrowserPiP = async () => {
    try {
      // @ts-ignore
      const pipWindow = await window.documentPictureInPicture.requestWindow({ width: 260, height: 180 });
      const style = document.createElement('style');
      style.textContent = `
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { background: #0D0D1A; display: flex; align-items: center; justify-content: center; height: 100vh; font-family: sans-serif; }
        .root { width: 100%; padding: 16px; color: white; }
        .label { font-size: 9px; text-transform: uppercase; letter-spacing: 2px; color: #a78bfa; font-weight: 800; margin-bottom: 4px; }
        .profit { font-size: 32px; font-weight: 900; line-height: 1; margin-bottom: 4px; }
        .stats { font-size: 10px; color: #9ca3af; margin-bottom: 10px; }
        .goal-row { display: flex; justify-content: space-between; align-items: center; font-size: 10px; color: #9ca3af; margin-bottom: 5px; }
        .goal-val { color: #c084fc; font-weight: 800; }
        .bar-bg { height: 5px; background: #1f2937; border-radius: 99px; overflow: hidden; }
        .bar-fill { height: 100%; background: linear-gradient(90deg, #6d28d9, #c084fc); border-radius: 99px; transition: width 0.6s ease; }
      `;
      pipWindow.document.head.appendChild(style);
      const container = pipWindow.document.createElement('div');
      container.className = 'root';
      container.innerHTML = `
        <div class="label">UBI DRIVER</div>
        <div class="profit" id="pp">R$ 0,00</div>
        <div class="stats" id="ps">Margem 0%  •  R$0,00/km</div>
        <div class="goal-row"><span>🎯 Faltam</span><span class="goal-val" id="gr">R$ 0,00</span></div>
        <div class="bar-bg"><div class="bar-fill" id="bf" style="width:0%"></div></div>
      `;
      pipWindow.document.body.appendChild(container);

      const fmt = (v: number) => v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
      const update = () => {
        const remaining = Math.max(dailyGoal - dailyAccumulated, 0);
        const pct = dailyGoal > 0 ? Math.min((dailyAccumulated / dailyGoal) * 100, 100) : 0;
        const pp = pipWindow.document.getElementById('pp');
        const ps = pipWindow.document.getElementById('ps');
        const gr = pipWindow.document.getElementById('gr');
        const bf = pipWindow.document.getElementById('bf');
        if (pp) pp.textContent = fmt(netProfit);
        if (ps) ps.textContent = `Margem ${margin.toFixed(1)}%  •  ${fmt(profitPerKm)}/km`;
        if (gr) gr.textContent = fmt(remaining);
        if (bf) (bf as HTMLElement).style.width = pct + '%';
      };
      update();

      const interval = setInterval(update, 1000);
      pipWindow.addEventListener('pagehide', () => { clearInterval(interval); setIsOverlayActive(false); });
      setIsOverlayActive(true);
    } catch (err) {
      console.error('PiP error:', err);
    }
  };

  const handleToggle = () => {
    if (isNative) toggleNativeOverlay();
    else if (isBrowserPiPSupported) toggleBrowserPiP();
  };

  if (!isNative && !isBrowserPiPSupported) return null;

  return (
    <div style={{
      position: 'fixed',
      bottom: 24,
      left: '50%',
      transform: 'translateX(-50%)',
      zIndex: 100,
      width: 'calc(100% - 32px)',
      maxWidth: 448,
    }}>
      <button
        onClick={handleToggle}
        style={{
          width: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 12,
          padding: '16px 24px',
          borderRadius: 20,
          border: 'none',
          cursor: 'pointer',
          fontFamily: 'Outfit, sans-serif',
          fontWeight: 900,
          fontSize: '0.85rem',
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
          background: isOverlayActive
            ? 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)'
            : 'linear-gradient(135deg, #7c3aed 0%, #c084fc 50%, #a855f7 100%)',
          color: '#fff',
          boxShadow: isOverlayActive
            ? '0 10px 30px -6px rgba(220,38,38,0.55)'
            : '0 10px 30px -6px rgba(124,58,237,0.55)',
          transition: 'all 0.3s ease',
        }}
        onTouchStart={e => (e.currentTarget.style.transform = 'scale(0.97)')}
        onTouchEnd={e => (e.currentTarget.style.transform = 'scale(1)')}
      >
        <div style={{ width: 32, height: 32, borderRadius: 10, background: 'rgba(255,255,255,0.15)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
          {isOverlayActive ? (
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="3" strokeLinecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          ) : (
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><rect width="18" height="14" x="3" y="5" rx="2"/><rect x="12" y="10" width="8" height="6" rx="1" fill="white" stroke="none"/></svg>
          )}
        </div>
        <span>{isOverlayActive ? '✕ Fechar Overlay' : '⚡ Ativar Modo Flutuante'}</span>
        <div style={{ width: 8, height: 8, borderRadius: '50%', background: isOverlayActive ? '#86efac' : 'rgba(255,255,255,0.3)', boxShadow: isOverlayActive ? '0 0 8px #22c55e' : 'none', flexShrink: 0 }} />
      </button>
    </div>
  );
}
