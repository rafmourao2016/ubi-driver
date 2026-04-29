'use client';

import { useState, useEffect } from 'react';
import Calculator from '@/components/Calculator';
import Onboarding from '@/components/Onboarding';

export default function Home() {
  const [ready, setReady] = useState<boolean | null>(null);

  useEffect(() => {
    // Verifica se o usuário já concluiu o onboarding antes
    const done = localStorage.getItem('ubi_onboarding_done');
    if (done === '1') {
      setReady(true);
    } else {
      // Verifica se está rodando em ambiente web (sem Capacitor) → pula onboarding
      const isNative = typeof window !== 'undefined' && !!(window as any).Capacitor;
      if (!isNative) {
        setReady(true);
      } else {
        setReady(false); // mostra onboarding
      }
    }
  }, []);

  const handleOnboardingComplete = () => {
    localStorage.setItem('ubi_onboarding_done', '1');
    setReady(true);
  };

  if (ready === null) return null; // splash momentâneo

  return (
    <main style={{ minHeight: '100vh' }}>
      {ready ? (
        <Calculator />
      ) : (
        <Onboarding onComplete={handleOnboardingComplete} />
      )}
    </main>
  );
}
