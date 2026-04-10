'use client';

import React, { useState, useEffect } from 'react';

interface PiPModeProps {
  netProfit: number;
  margin: number;
  profitPerKm: number;
}

// Tipo para o plugin nativo
interface OverlayPluginInterface {
  showOverlay: (data: { netProfit: number; margin: number; profitPerKm: number }) => Promise<void>;
  updateOverlay: (data: { netProfit: number; margin: number; profitPerKm: number }) => Promise<void>;
  hideOverlay: () => Promise<void>;
  isOverlayVisible: () => Promise<{ visible: boolean }>;
}

function getOverlayPlugin(): OverlayPluginInterface | null {
  if (typeof window !== 'undefined' && (window as any).Capacitor) {
    return (window as any).Capacitor.Plugins.OverlayPlugin as OverlayPluginInterface;
  }
  return null;
}

export default function PiPMode({ netProfit, margin, profitPerKm }: PiPModeProps) {
  const [isOverlayActive, setIsOverlayActive] = useState(false);
  const [isNative, setIsNative] = useState(false);
  const [isBrowserPiPSupported, setIsBrowserPiPSupported] = useState(false);

  useEffect(() => {
    const native = typeof window !== 'undefined' && !!(window as any).Capacitor;
    setIsNative(native);
    setIsBrowserPiPSupported(!native && 'documentPictureInPicture' in window);
  }, []);

  // Sincroniza valores com a overlay nativa quando mudam
  useEffect(() => {
    if (!isOverlayActive) return;
    const plugin = getOverlayPlugin();
    if (plugin) {
      plugin.updateOverlay({ netProfit, margin, profitPerKm }).catch(console.error);
    }
  }, [netProfit, margin, profitPerKm, isOverlayActive]);

  const toggleNativeOverlay = async () => {
    const plugin = getOverlayPlugin();
    if (!plugin) return;

    try {
      if (isOverlayActive) {
        await plugin.hideOverlay();
        setIsOverlayActive(false);
      } else {
        await plugin.showOverlay({ netProfit, margin, profitPerKm });
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
      const pipWindow = await window.documentPictureInPicture.requestWindow({ width: 280, height: 160 });

      const style = document.createElement('style');
      style.textContent = `
        body { margin: 0; background: #0D0D0D; display: flex; align-items: center; justify-content: center; height: 100vh; font-family: system-ui, sans-serif; }
        .pip-root { text-align: center; color: white; }
        .pip-label { font-size: 9px; text-transform: uppercase; letter-spacing: 2px; color: #A78BFA; }
        .pip-profit { font-size: 32px; font-weight: 900; margin: 4px 0; }
        .pip-stats { font-size: 11px; color: #888; }
      `;
      pipWindow.document.head.appendChild(style);

      const container = pipWindow.document.createElement('div');
      container.className = 'pip-root';
      container.innerHTML = `
        <div class="pip-label">UBI DRIVER</div>
        <div class="pip-profit" id="pip-profit">R$ 0,00</div>
        <div class="pip-stats" id="pip-stats">Margem: 0%  •  R$0,00/km</div>
      `;
      pipWindow.document.body.appendChild(container);

      const fmt = (v: number) =>
        v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

      const update = () => {
        const profitEl = pipWindow.document.getElementById('pip-profit');
        const statsEl = pipWindow.document.getElementById('pip-stats');
        if (profitEl) profitEl.textContent = fmt(netProfit);
        if (statsEl) statsEl.textContent = `Margem: ${margin.toFixed(1)}%  •  ${fmt(profitPerKm)}/km`;
      };
      update();

      const interval = setInterval(update, 1000);
      pipWindow.addEventListener('pagehide', () => {
        clearInterval(interval);
        setIsOverlayActive(false);
      });

      setIsOverlayActive(true);
    } catch (err) {
      console.error('PiP error:', err);
    }
  };

  const handleToggle = () => {
    if (isNative) {
      toggleNativeOverlay();
    } else if (isBrowserPiPSupported) {
      toggleBrowserPiP();
    }
  };

  if (!isNative && !isBrowserPiPSupported) return null;

  return (
    <button
      id="pip-toggle-btn"
      onClick={handleToggle}
      title={isOverlayActive ? 'Fechar Overlay' : 'Ativar Overlay Flutuante'}
      className={`fixed bottom-6 right-6 p-4 border border-border rounded-full shadow-xl hover:scale-110 transition-all z-50 ${
        isOverlayActive
          ? 'bg-primary text-white shadow-primary/40'
          : 'bg-accent'
      }`}
    >
      {isOverlayActive ? (
        // Ícone "X" quando ativo
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
          <line x1="18" y1="6" x2="6" y2="18"/>
          <line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      ) : (
        // Ícone PiP quando inativo
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <rect width="18" height="18" x="3" y="3" rx="2" ry="2"/>
          <rect x="12" y="12" width="8" height="6" rx="1" fill="currentColor" stroke="none"/>
        </svg>
      )}
    </button>
  );
}
