'use client';

import React, { useMemo, useEffect, useState } from 'react';
import { calculateProfit } from '../lib/calculations';
import { useSync } from '../hooks/useSync';
import PiPMode from './PiPMode';

export default function Calculator() {
  const { data: input, setData: setInput, syncData, loading, lastSynced } = useSync({
    grossEarnings: 0,
    distanceKm: 0,
    fuelPrice: 5.80,
    fuelConsumptionLabel: 10,
    otherCosts: 0,
    platformFeePercent: 25,
    targetProfitPerKm: 2.00,
  });

  const [dailyGoal, setDailyGoal] = useState(250);
  const [dailyGoalStr, setDailyGoalStr] = useState('250'); // string para permitir apagar o zero
  const [dailyAccumulated, setDailyAccumulated] = useState(0);

  useEffect(() => {
    const savedGoal = localStorage.getItem('ubi_daily_goal');
    const savedAcc = localStorage.getItem('ubi_daily_acc');
    if (savedGoal) {
      setDailyGoal(parseFloat(savedGoal));
      setDailyGoalStr(savedGoal);
    }
    if (savedAcc) setDailyAccumulated(parseFloat(savedAcc));
  }, []);

  useEffect(() => {
    localStorage.setItem('ubi_daily_goal', dailyGoal.toString());
    localStorage.setItem('ubi_daily_acc', dailyAccumulated.toString());
  }, [dailyGoal, dailyAccumulated]);

  const [serviceStatus, setServiceStatus] = useState({ running: false, permissions: false });

  useEffect(() => {
    const checkStatus = async () => {
      if (typeof window !== 'undefined' && (window as any).Capacitor) {
        const plugin = (window as any).Capacitor.Plugins.GigUPlugin;
        if (plugin) {
          const res = await plugin.checkPermissions();
          setServiceStatus({
            running: res.serviceRunning,
            permissions: res.accessibilityGranted && res.notificationGranted
          });
        }
      }
    };
    checkStatus();
    const interval = setInterval(checkStatus, 3000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (typeof window !== 'undefined' && (window as any).Capacitor) {
      const giguPlugin = (window as any).Capacitor.Plugins.GigUPlugin;
      if (giguPlugin) {
        giguPlugin.updateSettings({
          fuelPrice: input.fuelPrice,
          fuelConsumption: input.fuelConsumptionLabel,
          platformFee: input.platformFeePercent
        });
        const listener = giguPlugin.addListener('onUberOffer', (data: { price: number; km: number }) => {
          if (data.price > 0 || data.km > 0) {
            setInput(prev => ({
              ...prev,
              grossEarnings: data.price > 0 ? data.price : prev.grossEarnings,
              distanceKm: data.km > 0 ? data.km : prev.distanceKm,
            }));
          }
        });
        return () => listener.remove();
      }
    }
  }, [input.fuelPrice, input.fuelConsumptionLabel, input.platformFeePercent, setInput]);

  const results = useMemo(() => calculateProfit(input), [input]);

  const grade = useMemo(() => {
    if (results.profitMargin >= 30) return { label: 'ELITE ★', bg: '#065f46', color: '#6ee7b7', border: '#059669' };
    if (results.profitMargin >= 15) return { label: 'OK ●', bg: '#78350f', color: '#fcd34d', border: '#d97706' };
    return { label: 'BAIXO ▼', bg: '#7f1d1d', color: '#fca5a5', border: '#dc2626' };
  }, [results.profitMargin]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setInput(prev => ({ ...prev, [name]: value === '' ? 0 : parseFloat(value) }));
  };

  const handleConfirmRide = () => {
    setDailyAccumulated(prev => prev + results.netProfit);
    if (navigator.vibrate) navigator.vibrate([50, 30, 50]);
  };

  const handleResetDay = () => {
    if (confirm('Zerar os ganhos de hoje? 🌅')) {
      setDailyAccumulated(0);
      setInput(prev => ({ ...prev, grossEarnings: 0, distanceKm: 0 }));
      localStorage.removeItem('ubi_daily_acc');
      // Também reseta o overlay se estiver ativo
      if (typeof window !== 'undefined' && (window as any).Capacitor) {
        const plugin = (window as any).Capacitor.Plugins.OverlayPlugin;
        if (plugin?.clearOverlay) plugin.clearOverlay();
      }
    }
  };

  const syncDataWithDaily = async () => {
    const result = await syncData();
    if (result.success && navigator.vibrate) navigator.vibrate(100);
  };


  const f = (val: number) =>
    new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(val);

  const pct = Math.min((dailyAccumulated / dailyGoal) * 100, 100);

  return (
    <div className="animate-fade-in" style={{ maxWidth: 480, margin: '0 auto', padding: '16px 16px 120px' }}>

      {/* ── HEADER ── */}
      <header style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.07)',
        borderRadius: 20, padding: '12px 16px', marginBottom: 14,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{
            width: 40, height: 40, borderRadius: '50%',
            background: 'linear-gradient(135deg, #c084fc, #7c3aed)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontWeight: 900, fontSize: 18, color: '#fff',
            boxShadow: '0 4px 14px rgba(192,132,252,0.35)',
          }}>U</div>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <div style={{ fontWeight: 900, fontSize: '1rem', lineHeight: 1, color: '#fff' }}>Ubi Driver</div>
              <div 
                onClick={() => {
                  if (!serviceStatus.running && (window as any).Capacitor) {
                    (window as any).Capacitor.Plugins.GigUPlugin.openNotificationSettings();
                  }
                }}
                style={{
                  fontSize: '0.6rem', fontWeight: 900, padding: '2px 6px', borderRadius: 6,
                  background: serviceStatus.running ? 'rgba(34,197,94,0.15)' : 'rgba(239,68,68,0.15)',
                  color: serviceStatus.running ? '#4ade80' : '#f87171',
                  border: `1px solid ${serviceStatus.running ? 'rgba(34,197,94,0.3)' : 'rgba(239,68,68,0.3)'}`,
                  cursor: 'pointer'
                }}>
                {serviceStatus.running ? '🟢 ATIVO' : '🔴 INATIVO'}
              </div>
            </div>
            <div style={{ fontWeight: 700, fontSize: '0.65rem', color: '#c084fc', letterSpacing: '0.1em', textTransform: 'uppercase', marginTop: 2 }}>Smart Overlay</div>
          </div>
        </div>
        <button onClick={handleResetDay} style={{
          width: 36, height: 36, borderRadius: '50%',
          background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.08)',
          color: '#64748b', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>
        </button>
      </header>

      {/* ── DAILY GOAL ── */}
      <section style={{
        background: 'linear-gradient(135deg, #1a1033 0%, #0f0e1a 100%)',
        border: '1px solid rgba(192,132,252,0.15)', borderRadius: 24, padding: 20, marginBottom: 14,
      }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 14 }}>
          <div>
            <div style={{ fontSize: '0.65rem', fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 4 }}>🎯 Progresso de Hoje</div>
            <div style={{ fontSize: '2.2rem', fontWeight: 900, color: '#fff', lineHeight: 1 }}>{f(dailyAccumulated)}</div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: '0.65rem', fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 4 }}>Meta</div>
            <div style={{ display: 'flex', alignItems: 'center', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)', borderRadius: 10, padding: '4px 10px' }}>
              <span style={{ color: '#64748b', fontSize: '0.8rem', marginRight: 2 }}>R$</span>
              <input type="number" value={dailyGoalStr}
              onChange={e => setDailyGoalStr(e.target.value)}
              onBlur={() => {
                const parsed = parseFloat(dailyGoalStr);
                if (!isNaN(parsed) && parsed > 0) {
                  setDailyGoal(parsed);
                } else {
                  setDailyGoalStr(dailyGoal.toString());
                }
              }}
                style={{ width: 56, fontSize: '0.9rem', fontWeight: 900, color: '#c084fc', textAlign: 'right' }} />
            </div>
          </div>
        </div>

        {/* Progress bar */}
        <div style={{ height: 10, background: 'rgba(255,255,255,0.06)', borderRadius: 99, overflow: 'hidden', position: 'relative', marginBottom: 8 }}>
          <div style={{
            height: '100%', width: `${pct}%`,
            background: 'linear-gradient(90deg, #c084fc, #a855f7, #6366f1)',
            borderRadius: 99, transition: 'width 0.8s cubic-bezier(0.4,0,0.2,1)',
            position: 'relative', overflow: 'hidden',
          }}>
            <div style={{
              position: 'absolute', top: 0, left: '-30%', width: '30%', height: '100%',
              background: 'linear-gradient(90deg, transparent, rgba(255,255,255,0.4), transparent)',
              animation: 'shimmer 2s infinite',
            }} />
          </div>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.7rem', fontWeight: 700, color: '#64748b' }}>
          <span style={{ color: '#c084fc' }}>{pct.toFixed(0)}% concluído</span>
          <span>Faltam {f(Math.max(dailyGoal - dailyAccumulated, 0))}</span>
        </div>
      </section>

      {/* ── PROFIT CARD ── */}
      <section className="animate-float" style={{
        background: 'linear-gradient(135deg, #c084fc 0%, #a855f7 40%, #fb923c 100%)',
        borderRadius: 24, padding: 20, marginBottom: 14, position: 'relative', overflow: 'hidden',
        boxShadow: '0 16px 40px -8px rgba(192,132,252,0.45)',
      }}>
        <div style={{ position: 'absolute', top: -30, right: -30, width: 120, height: 120, background: 'rgba(255,255,255,0.12)', borderRadius: '50%', filter: 'blur(30px)' }} />
        <div style={{ position: 'relative', zIndex: 1 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <div style={{ width: 7, height: 7, borderRadius: '50%', background: '#fff', animation: 'pulse-dot 2s infinite' }} />
              <span style={{ fontSize: '0.65rem', fontWeight: 700, color: 'rgba(255,255,255,0.65)', textTransform: 'uppercase', letterSpacing: '0.1em' }}>Lucro Líquido</span>
            </div>
            <div style={{
              padding: '4px 10px', borderRadius: 99,
              background: grade.bg, color: grade.color,
              border: `1px solid ${grade.border}`,
              fontSize: '0.65rem', fontWeight: 800, letterSpacing: '0.06em',
            }}>
              {grade.label}
            </div>
          </div>

          <div style={{ fontSize: 'clamp(2.2rem, 8vw, 3rem)', fontWeight: 900, color: '#fff', lineHeight: 1, marginBottom: 14, textShadow: '0 2px 10px rgba(0,0,0,0.2)' }}>
            {f(results.netProfit)}
          </div>

          <button onClick={handleConfirmRide} className="btn-add" style={{ marginBottom: 14, color: '#fff' }}>
            🚗 Somar na Meta do Dia
          </button>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, borderTop: '1px solid rgba(255,255,255,0.15)', paddingTop: 14 }}>
            <div style={{ background: 'rgba(0,0,0,0.15)', borderRadius: 14, padding: '10px 14px' }}>
              <div style={{ fontSize: '0.6rem', fontWeight: 700, color: 'rgba(255,255,255,0.5)', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 4 }}>R$ / Km</div>
              <div style={{ fontSize: '1.1rem', fontWeight: 900, color: '#fff' }}>{f(results.profitPerKm)}</div>
            </div>
            <div style={{ background: 'rgba(0,0,0,0.15)', borderRadius: 14, padding: '10px 14px' }}>
              <div style={{ fontSize: '0.6rem', fontWeight: 700, color: 'rgba(255,255,255,0.5)', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 4 }}>Bruto Alvo</div>
              <div style={{ fontSize: '1.1rem', fontWeight: 900, color: '#fff' }}>{f(results.targetGrossPrice)}</div>
            </div>
          </div>
        </div>
      </section>

      {/* ── INPUT CARDS ── */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 14 }}>
        {[
          { label: '💰 Bruto (R$)', name: 'grossEarnings', placeholder: '0,00' },
          { label: '📍 Distância (km)', name: 'distanceKm', placeholder: '0' },
        ].map(f2 => (
          <div key={f2.name} className="card" style={{ padding: '14px 16px' }}>
            <label style={{ marginBottom: 6 }}>{f2.label}</label>
            <input type="number" name={f2.name} placeholder={f2.placeholder}
              value={(input as any)[f2.name] === 0 ? '' : (input as any)[f2.name]}
              onChange={handleChange}
              style={{ fontSize: '1.6rem' }}
            />
          </div>
        ))}
      </div>

      {/* ── SETTINGS ── */}
      <section className="card" style={{ padding: '18px 16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16 }}>
          <div style={{ flex: 1, height: 1, background: 'rgba(255,255,255,0.06)' }} />
          <span style={{ fontSize: '0.65rem', fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em' }}>⚙️ Ajustes Finos</span>
          <div style={{ flex: 1, height: 1, background: 'rgba(255,255,255,0.06)' }} />
        </div>

        {/* Alvo slider */}
        <div style={{ background: 'rgba(0,0,0,0.2)', borderRadius: 16, padding: '14px 16px', marginBottom: 12 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
            <label style={{ margin: 0 }}>Alvo de Lucro (km)</label>
            <span style={{ fontSize: '0.8rem', fontWeight: 900, color: '#c084fc', background: 'rgba(192,132,252,0.1)', padding: '2px 8px', borderRadius: 99 }}>
              {f(input.targetProfitPerKm)}
            </span>
          </div>
          <input type="range" min="0.5" max="5" step="0.1"
            value={input.targetProfitPerKm}
            onChange={e => setInput(prev => ({ ...prev, targetProfitPerKm: parseFloat(e.target.value) }))}
          />
        </div>

        {/* Grid 2x2 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          {[
            { label: '⛽ Gasolina (R$)', name: 'fuelPrice', step: '0.01' },
            { label: '🚗 Consumo (km/l)', name: 'fuelConsumptionLabel', step: '0.5' },
            { label: '📊 Taxa App (%)', name: 'platformFeePercent', step: '1' },
            { label: '🍟 Extras (R$)', name: 'otherCosts', step: '1', placeholder: '0' },
          ].map(f3 => (
            <div key={f3.name} style={{ background: 'rgba(0,0,0,0.2)', borderRadius: 14, padding: '12px 14px' }}>
              <label style={{ marginBottom: 4 }}>{f3.label}</label>
              <input type="number" name={f3.name} step={f3.step} placeholder={f3.placeholder}
                value={(input as any)[f3.name] === 0 ? '' : (input as any)[f3.name]}
                onChange={handleChange}
                style={{ fontSize: '1.2rem' }}
              />
            </div>
          ))}
        </div>
      </section>


      {/* ── SAVE BUTTON ── */}
      <button onClick={syncDataWithDaily} disabled={loading} className="btn-primary"
        style={{ marginTop: 16, opacity: loading ? 0.6 : 1 }}>
        {loading ? 'Sincronizando...' : '☁️ Salvar na Nuvem'}
      </button>

      {lastSynced && (
        <p style={{ textAlign: 'center', fontSize: '0.65rem', fontWeight: 700, color: 'rgba(255,255,255,0.2)', textTransform: 'uppercase', letterSpacing: '0.1em', marginTop: 10 }}>
          Sync: {lastSynced.toLocaleTimeString()}
        </p>
      )}


      <PiPMode
        netProfit={results.netProfit}
        margin={results.profitMargin}
        profitPerKm={results.profitPerKm}
        dailyGoal={dailyGoal}
        dailyAccumulated={dailyAccumulated}
      />
    </div>
  );
}
