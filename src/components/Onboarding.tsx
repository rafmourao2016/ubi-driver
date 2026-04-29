'use client';

import React, { useState, useEffect, useCallback } from 'react';

interface PermState {
  overlayGranted: boolean;
  accessibilityGranted: boolean;
}

interface OnboardingProps {
  onComplete: () => void;
}

function getPlugin() {
  if (typeof window !== 'undefined' && (window as any).Capacitor) {
    return (window as any).Capacitor.Plugins.GigUPlugin;
  }
  return null;
}

export default function Onboarding({ onComplete }: OnboardingProps) {
  const [perms, setPerms] = useState<PermState>({ overlayGranted: false, accessibilityGranted: false });
  const [checking, setChecking] = useState(false);

  const checkPerms = useCallback(async () => {
    const plugin = getPlugin();
    if (!plugin) {
      // No ambiente web (sem Capacitor), pula o onboarding
      onComplete();
      return;
    }
    try {
      setChecking(true);
      const result = await plugin.checkPermissions();
      setPerms(result);
      if (result.overlayGranted && result.accessibilityGranted) {
        onComplete();
      }
    } catch (_) {
      onComplete(); // fallback se o plugin não existir
    } finally {
      setChecking(false);
    }
  }, [onComplete]);

  // Checa permissões ao montar e quando o app volta pro foco (usuário voltou das configurações)
  useEffect(() => {
    checkPerms();
    const onFocus = () => checkPerms();
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) checkPerms();
    });
    return () => window.removeEventListener('focus', onFocus);
  }, [checkPerms]);

  const handleOverlay = async () => {
    const plugin = getPlugin();
    if (plugin) await plugin.openOverlayPermission();
  };

  const handleAccessibility = async () => {
    const plugin = getPlugin();
    if (plugin) await plugin.openAccessibilitySettings();
  };

  const bothDone = perms.overlayGranted && perms.accessibilityGranted;

  const steps = [
    {
      id: 'overlay',
      emoji: '🪟',
      title: 'Janela Flutuante',
      description: 'Permite que o Ubi mostre o lucro por cima do app da Uber ou 99.',
      granted: perms.overlayGranted,
      onPress: handleOverlay,
      hint: 'Ative "Permitir sobreposição de apps"',
    },
    {
      id: 'accessibility',
      emoji: '👁️',
      title: 'Leitura Automática',
      description: 'Detecta as ofertas da Uber/99 na tela e calcula o lucro instantaneamente.',
      granted: perms.accessibilityGranted,
      onPress: handleAccessibility,
      hint: 'Procure por "Ubi Driver" na lista e ative',
    },
  ];

  return (
    <div style={{
      minHeight: '100vh',
      background: 'linear-gradient(160deg, #0f0c1a 0%, #08070b 60%, #0f0c1a 100%)',
      display: 'flex', flexDirection: 'column',
      alignItems: 'center', justifyContent: 'center',
      padding: '24px 20px',
      fontFamily: 'Outfit, sans-serif',
    }}>
      {/* Logo */}
      <div style={{ marginBottom: 32, textAlign: 'center' }}>
        <div style={{
          width: 72, height: 72, borderRadius: '50%',
          background: 'linear-gradient(135deg, #7c3aed, #c084fc)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          margin: '0 auto 16px',
          boxShadow: '0 0 40px rgba(192,132,252,0.35)',
          fontSize: 36, fontWeight: 900, color: '#fff',
        }}>U</div>
        <h1 style={{ fontSize: '1.6rem', fontWeight: 900, color: '#fff', lineHeight: 1, marginBottom: 6 }}>
          Ubi Driver
        </h1>
        <p style={{ fontSize: '0.75rem', color: '#9ca3af', fontWeight: 600, letterSpacing: '0.1em', textTransform: 'uppercase' }}>
          Configuração inicial
        </p>
      </div>

      {/* Intro text */}
      <p style={{ fontSize: '0.9rem', color: '#6b7280', textAlign: 'center', maxWidth: 300, lineHeight: 1.6, marginBottom: 32 }}>
        Para funcionar, o Ubi precisa de <strong style={{ color: '#c084fc' }}>2 permissões</strong>.
        São seguras — não acessamos seus dados pessoais.
      </p>

      {/* Steps */}
      <div style={{ width: '100%', maxWidth: 400, display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 32 }}>
        {steps.map((step, idx) => (
          <div key={step.id} style={{
            background: step.granted ? 'rgba(5,150,105,0.1)' : 'rgba(255,255,255,0.04)',
            border: `1px solid ${step.granted ? '#059669' : 'rgba(255,255,255,0.08)'}`,
            borderRadius: 20,
            padding: '18px 20px',
            transition: 'all 0.3s ease',
          }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
              {/* Step number / check */}
              <div style={{
                width: 40, height: 40, borderRadius: '50%', flexShrink: 0,
                background: step.granted ? '#059669' : 'rgba(109,40,217,0.3)',
                border: `2px solid ${step.granted ? '#059669' : '#6d28d9'}`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: step.granted ? 18 : 16,
              }}>
                {step.granted ? '✓' : step.emoji}
              </div>

              <div style={{ flex: 1 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <span style={{ fontSize: '0.85rem', fontWeight: 800, color: step.granted ? '#6ee7b7' : '#fff' }}>
                    {step.title}
                  </span>
                  {step.granted && (
                    <span style={{ fontSize: '0.65rem', fontWeight: 700, color: '#059669', background: 'rgba(5,150,105,0.15)', padding: '2px 8px', borderRadius: 99 }}>
                      ATIVO
                    </span>
                  )}
                </div>
                <p style={{ fontSize: '0.78rem', color: '#9ca3af', lineHeight: 1.5, marginBottom: step.granted ? 0 : 12 }}>
                  {step.description}
                </p>

                {!step.granted && (
                  <>
                    <div style={{ fontSize: '0.7rem', color: '#6d28d9', background: 'rgba(109,40,217,0.1)', borderRadius: 8, padding: '6px 10px', marginBottom: 12 }}>
                      💡 {step.hint}
                    </div>
                    <button
                      onClick={step.onPress}
                      style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                        width: '100%', padding: '12px 16px',
                        background: 'linear-gradient(135deg, #7c3aed, #c084fc)',
                        color: '#fff', border: 'none', borderRadius: 14,
                        fontFamily: 'Outfit, sans-serif',
                        fontWeight: 800, fontSize: '0.8rem',
                        letterSpacing: '0.05em', textTransform: 'uppercase',
                        cursor: 'pointer',
                        boxShadow: '0 6px 16px -4px rgba(124,58,237,0.45)',
                      }}
                    >
                      <span>Passo {idx + 1}: Liberar Agora</span>
                      <span style={{ fontSize: 16 }}>→</span>
                    </button>
                  </>
                )}
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Re-check button */}
      <button
        onClick={checkPerms}
        disabled={checking}
        style={{
          background: 'transparent', border: '1px solid rgba(255,255,255,0.1)',
          color: '#6b7280', padding: '10px 24px', borderRadius: 99,
          fontFamily: 'Outfit, sans-serif', fontWeight: 700, fontSize: '0.75rem',
          cursor: 'pointer', marginBottom: bothDone ? 16 : 0,
          letterSpacing: '0.05em',
        }}
      >
        {checking ? 'Verificando...' : '🔄 Já ativei — Verificar novamente'}
      </button>

      {/* Enter button when all done */}
      {bothDone && (
        <button
          onClick={onComplete}
          style={{
            marginTop: 12,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
            padding: '16px 40px', borderRadius: 20, border: 'none',
            background: 'linear-gradient(135deg, #059669, #34d399)',
            color: '#fff', fontFamily: 'Outfit, sans-serif',
            fontWeight: 900, fontSize: '1rem', letterSpacing: '0.04em',
            cursor: 'pointer',
            boxShadow: '0 10px 30px -6px rgba(5,150,105,0.5)',
            animation: 'fadeIn 0.4s ease',
          }}
        >
          🚀 Entrar no Ubi Driver
        </button>
      )}

      {/* Footer note */}
      <p style={{ fontSize: '0.65rem', color: '#374151', textAlign: 'center', marginTop: 24, maxWidth: 280, lineHeight: 1.6 }}>
        Suas informações ficam apenas no seu celular. Nenhum dado é enviado para terceiros.
      </p>
    </div>
  );
}
