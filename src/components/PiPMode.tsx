'use client';

import React, { useState, useEffect } from 'react';

export default function PiPMode() {
  const [isSupported, setIsSupported] = useState(false);

  useEffect(() => {
    setIsSupported('documentPictureInPicture' in window);
  }, []);

  const togglePiP = async () => {
    if (!isSupported) return;

    try {
      // @ts-ignore - Document Picture-in-Picture API is still experimental in some types
      const pipWindow = await window.documentPictureInPicture.requestWindow({
        width: 300,
        height: 200,
      });

      // Copy styles to the new window
      const allStyleSheets = Array.from(document.styleSheets);
      allStyleSheets.forEach((styleSheet) => {
        try {
          const cssRules = Array.from(styleSheet.cssRules)
            .map((rule) => rule.cssText)
            .join('');
          const style = document.createElement('style');
          style.textContent = cssRules;
          pipWindow.document.head.appendChild(style);
        } catch (e) {
          const link = document.createElement('link');
          if (styleSheet.href) {
            link.rel = 'stylesheet';
            link.href = styleSheet.href;
            pipWindow.document.head.appendChild(link);
          }
        }
      });

      // Simple interface for PiP
      const container = pipWindow.document.createElement('div');
      container.id = 'pip-root';
      container.className = 'glass p-4 rounded-none h-screen flex flex-col justify-center items-center text-center';
      container.innerHTML = `
        <h2 class="text-xs uppercase tracking-widest text-primary mb-1">Ubi PiP</h2>
        <div id="pip-profit" class="text-3xl font-black">R$ 0,00</div>
        <div id="pip-stats" class="text-[10px] text-text-muted mt-1">Margem: 0%</div>
      `;
      pipWindow.document.body.appendChild(container);

      // Function to sync with main window profit (simplified)
      const updatePip = () => {
        const profitEl = document.querySelector('.text-5xl');
        if (profitEl && pipWindow.document.getElementById('pip-profit')) {
          pipWindow.document.getElementById('pip-profit')!.innerText = (profitEl as HTMLElement).innerText;
        }
        const statsEl = document.querySelector('.mt-2.flex');
        if (statsEl && pipWindow.document.getElementById('pip-stats')) {
          pipWindow.document.getElementById('pip-stats')!.innerText = (statsEl as HTMLElement).innerText;
        }
      };

      const interval = setInterval(updatePip, 1000);
      pipWindow.addEventListener('pagehide', () => clearInterval(interval));

    } catch (err) {
      console.error('Failed to open PiP window:', err);
    }
  };

  if (!isSupported) return null;

  return (
    <button 
      onClick={togglePiP}
      className="fixed bottom-6 right-6 p-4 bg-accent border border-border rounded-full shadow-xl hover:scale-110 transition-transform z-50"
      title="Ativar Modo Flutuante (PiP)"
    >
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <rect width="18" height="18" x="3" y="3" rx="2" ry="2"/>
        <path d="M15 11v-1a2 2 0 0 0-2-2H9"/>
        <path d="M7 15l2 2 2-2"/>
        <path d="M9 17V9"/>
      </svg>
    </button>
  );
}
