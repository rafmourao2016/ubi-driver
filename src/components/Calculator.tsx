'use client';

import React, { useMemo, useEffect } from 'react';
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

  const [dailyGoal, setDailyGoal] = React.useState(250);
  const [dailyAccumulated, setDailyAccumulated] = React.useState(0);

  useEffect(() => {
    const savedGoal = localStorage.getItem('ubi_daily_goal');
    const savedAcc = localStorage.getItem('ubi_daily_acc');
    if (savedGoal) setDailyGoal(parseFloat(savedGoal));
    if (savedAcc) setDailyAccumulated(parseFloat(savedAcc));
  }, []);

  useEffect(() => {
    localStorage.setItem('ubi_daily_goal', dailyGoal.toString());
    localStorage.setItem('ubi_daily_acc', dailyAccumulated.toString());
  }, [dailyGoal, dailyAccumulated]);

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
    if (results.profitMargin >= 30) return { label: 'ELITE ★', color: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/40 shadow-[0_0_15px_rgba(16,185,129,0.2)]' };
    if (results.profitMargin >= 15) return { label: 'OK ●', color: 'bg-amber-500/20 text-amber-400 border-amber-500/40 shadow-[0_0_15px_rgba(245,158,11,0.2)]' };
    return { label: 'BAIXO ▼', color: 'bg-red-500/20 text-red-400 border-red-500/40 shadow-[0_0_15px_rgba(239,68,68,0.2)]' };
  }, [results.profitMargin]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    const numericValue = value === '' ? 0 : parseFloat(value);
    setInput(prev => ({ ...prev, [name]: numericValue }));
  };

  const handleConfirmRide = () => {
    setDailyAccumulated(prev => prev + results.netProfit);
    // Vibrate success
    if (navigator.vibrate) navigator.vibrate([50, 50, 50]);
  };

  const handleResetDay = () => {
    if (confirm('Deseja começar um novo dia e zerar os ganhos? 🌅')) {
      setDailyAccumulated(0);
    }
  };

  const syncDataWithDaily = async () => {
    const result = await syncData();
    if (result.success) {
      if (navigator.vibrate) navigator.vibrate(100);
      alert('Tudo salvo na nuvem! ✨');
    }
  };

  const formatCurrency = (val: number) => 
    new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(val);

  const dailyProgressPercent = Math.min((dailyAccumulated / dailyGoal) * 100, 100);

  return (
    <div className="animate-fade-in max-w-md mx-auto p-5 pb-32">
      
      {/* HEADER */}
      <header className="mb-6 flex justify-between items-center bg-white/5 p-4 rounded-3xl border border-white/10 shadow-lg backdrop-blur-md">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-gradient-to-br from-primary to-purple-600 flex items-center justify-center shadow-lg shadow-primary/30">
            <span className="text-white font-black text-xl tracking-tighter">U</span>
          </div>
          <div>
            <h1 className="text-xl font-extrabold tracking-tight text-white leading-none">Ubi Driver</h1>
            <p className="text-primary text-[10px] font-bold uppercase tracking-widest mt-1">Smart Overlay</p>
          </div>
        </div>
        <div className="flex gap-2">
          <button onClick={handleResetDay} className="w-10 h-10 flex items-center justify-center bg-white/5 rounded-full hover:bg-white/10 transition-all border border-white/5 active:scale-95" title="Zerar Dia">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-text-muted"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>
          </button>
        </div>
      </header>

      {/* DAILY GOAL PANEL */}
      <section className="relative overflow-hidden mb-6 p-6 rounded-[32px] bg-gradient-to-br from-[#1A1A2E] to-[#121220] border border-white/5 shadow-2xl">
        <div className="absolute top-0 right-0 p-4">
          <div className="flex items-center bg-white/5 rounded-full px-3 py-1 border border-white/10">
            <span className="text-[10px] text-text-muted mr-1 font-bold uppercase">Meta:</span>
            <input 
              type="number" 
              className="bg-transparent text-right text-[11px] font-black text-white w-16 outline-none"
              value={dailyGoal}
              onChange={(e) => setDailyGoal(parseFloat(e.target.value) || 0)}
            />
          </div>
        </div>
        
        <div className="mb-4 mt-2">
          <p className="text-[11px] font-bold text-text-muted uppercase tracking-widest mb-1 flex items-center gap-1">
            <span>🎯</span> Progresso de Hoje
          </p>
          <div className="text-4xl font-black text-white tracking-tight">
            {formatCurrency(dailyAccumulated)}
          </div>
        </div>
        
        <div className="h-4 bg-black/40 rounded-full overflow-hidden mb-3 border border-white/5 p-[2px]">
          <div 
            className="h-full bg-gradient-to-r from-primary via-purple-400 to-accent rounded-full transition-all duration-1000 relative overflow-hidden shadow-[0_0_10px_rgba(192,132,252,0.5)]" 
            style={{ width: `${dailyProgressPercent}%` }}
          >
            <div className="absolute inset-0 bg-white/20 w-full animate-[shimmer_2s_infinite]"></div>
          </div>
        </div>
        
        <div className="flex justify-between text-[11px] font-bold text-text-muted">
          <span className="text-primary">{dailyProgressPercent.toFixed(0)}%</span>
          <span>Faltam {formatCurrency(Math.max(dailyGoal - dailyAccumulated, 0))}</span>
        </div>
      </section>

      {/* LIVE PROFIT CARD */}
      <section className="relative overflow-hidden mb-6 p-6 rounded-[32px] bg-gradient-to-br from-primary to-orange-400 shadow-[0_15px_35px_-10px_rgba(192,132,252,0.5)] animate-float border border-white/20">
        <div className="absolute -right-10 -top-10 w-40 h-40 bg-white/20 rounded-full blur-3xl"></div>
        <div className="absolute -left-10 -bottom-10 w-32 h-32 bg-black/10 rounded-full blur-2xl"></div>
        
        <div className="relative z-10 flex justify-between items-center mb-4">
          <div className="flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-white animate-pulse shadow-[0_0_8px_white]"></span>
            <label className="text-black/60 font-black text-[10px] uppercase tracking-widest">Lucro Líquido</label>
          </div>
          <div className={`px-3 py-1 rounded-full text-[9px] font-black tracking-widest backdrop-blur-md ${grade.color.replace('text-green-400', 'text-white').replace('text-amber-400', 'text-white').replace('text-red-400', 'text-white')} bg-black/20 border-white/20`}>
            {grade.label}
          </div>
        </div>

        <div className="relative z-10 text-6xl font-black text-white tracking-tighter mb-5 drop-shadow-md">
          {formatCurrency(results.netProfit)}
        </div>

        <button 
          onClick={handleConfirmRide}
          className="relative z-10 w-full py-3 bg-white/20 hover:bg-white/30 backdrop-blur-sm rounded-2xl mb-5 text-[11px] font-black uppercase tracking-widest text-white border border-white/30 transition-all active:scale-95 shadow-lg flex justify-center items-center gap-2"
        >
          <span>🚗</span> Somar na Meta
        </button>

        <div className="relative z-10 flex gap-4 border-t border-black/10 pt-4">
          <div className="flex-1 bg-black/10 rounded-2xl p-3 border border-white/10 backdrop-blur-sm">
            <span className="block text-[9px] text-black/50 font-black uppercase tracking-widest mb-1">R$ / Km</span>
            <span className="text-white font-black text-xl">{formatCurrency(results.profitPerKm)}</span>
          </div>
          <div className="flex-1 bg-black/10 rounded-2xl p-3 border border-white/10 backdrop-blur-sm">
            <span className="block text-[9px] text-black/50 font-black uppercase tracking-widest mb-1">Bruto Alvo</span>
            <span className="text-white font-black text-xl">{formatCurrency(results.targetGrossPrice)}</span>
          </div>
        </div>
      </section>

      {/* INPUTS SIMULADOR */}
      <div className="grid grid-cols-2 gap-3 mb-6">
        <div className="bg-[#1A1A2E] p-4 rounded-3xl border border-white/5 focus-within:border-primary/50 focus-within:bg-[#1f1f38] transition-all">
          <label className="text-[10px] text-text-muted font-bold uppercase tracking-widest mb-2 flex gap-1"><span>💰</span> Bruto</label>
          <input 
            type="number" 
            name="grossEarnings" 
            value={input.grossEarnings === 0 ? '' : input.grossEarnings} 
            onChange={handleChange} 
            placeholder="0,00"
            className="w-full bg-transparent text-white font-black text-2xl outline-none placeholder:text-white/20"
          />
        </div>
        <div className="bg-[#1A1A2E] p-4 rounded-3xl border border-white/5 focus-within:border-primary/50 focus-within:bg-[#1f1f38] transition-all">
          <label className="text-[10px] text-text-muted font-bold uppercase tracking-widest mb-2 flex gap-1"><span>📍</span> Distância</label>
          <input 
            type="number" 
            name="distanceKm" 
            value={input.distanceKm === 0 ? '' : input.distanceKm} 
            onChange={handleChange} 
            placeholder="0"
            className="w-full bg-transparent text-white font-black text-2xl outline-none placeholder:text-white/20"
          />
        </div>
      </div>

      {/* CONFIGURAÇÕES (Sanfoninha fofa) */}
      <section className="bg-white/5 rounded-[32px] p-1 border border-white/5">
        <div className="p-4 flex items-center justify-center gap-2">
          <div className="h-[1px] flex-1 bg-gradient-to-r from-transparent to-white/10"></div>
          <span className="text-[10px] font-bold text-text-muted uppercase tracking-widest flex items-center gap-1"><span>⚙️</span> Ajustes Finos</span>
          <div className="h-[1px] flex-1 bg-gradient-to-l from-transparent to-white/10"></div>
        </div>

        <div className="p-4 space-y-4">
          <div className="bg-black/20 p-4 rounded-2xl border border-white/5">
            <label className="flex justify-between text-[11px] font-bold text-text-muted uppercase tracking-widest mb-3">
              <span>Alvo de Lucro (km)</span>
              <span className="text-primary bg-primary/10 px-2 py-0.5 rounded-full">{formatCurrency(input.targetProfitPerKm)}</span>
            </label>
            <input 
              type="range" 
              name="targetProfitPerKm" 
              min="0.5" max="5" step="0.1"
              value={input.targetProfitPerKm} 
              onChange={(e) => setInput(prev => ({ ...prev, targetProfitPerKm: parseFloat(e.target.value) }))}
              className="w-full accent-primary h-2 bg-black/50 rounded-lg appearance-none cursor-pointer"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="bg-black/20 p-3 rounded-2xl border border-white/5">
              <label className="text-[9px] text-text-muted font-bold uppercase mb-1 block">⛽ Gasolina (R$)</label>
              <input type="number" name="fuelPrice" value={input.fuelPrice === 0 ? '' : input.fuelPrice} onChange={handleChange} step="0.01" className="w-full bg-transparent text-white font-bold text-lg outline-none"/>
            </div>
            <div className="bg-black/20 p-3 rounded-2xl border border-white/5">
              <label className="text-[9px] text-text-muted font-bold uppercase mb-1 block">🚗 Consumo (km/l)</label>
              <input type="number" name="fuelConsumptionLabel" value={input.fuelConsumptionLabel === 0 ? '' : input.fuelConsumptionLabel} onChange={handleChange} className="w-full bg-transparent text-white font-bold text-lg outline-none"/>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="bg-black/20 p-3 rounded-2xl border border-white/5">
              <label className="text-[9px] text-text-muted font-bold uppercase mb-1 block">📊 Taxa App (%)</label>
              <input type="number" name="platformFeePercent" value={input.platformFeePercent === 0 ? '' : input.platformFeePercent} onChange={handleChange} className="w-full bg-transparent text-white font-bold text-lg outline-none"/>
            </div>
            <div className="bg-black/20 p-3 rounded-2xl border border-white/5">
              <label className="text-[9px] text-text-muted font-bold uppercase mb-1 block">🍟 Extras (R$)</label>
              <input type="number" name="otherCosts" value={input.otherCosts === 0 ? '' : input.otherCosts} onChange={handleChange} className="w-full bg-transparent text-white font-bold text-lg outline-none placeholder:text-white/20" placeholder="0"/>
            </div>
          </div>
        </div>
      </section>

      <button 
        className={`w-full mt-6 py-4 rounded-[24px] font-black text-sm uppercase tracking-widest text-white bg-gradient-to-r from-primary to-purple-600 shadow-[0_10px_20px_-5px_rgba(192,132,252,0.4)] transition-all active:scale-95 ${loading ? 'opacity-50' : 'hover:-translate-y-1'}`}
        disabled={loading}
        onClick={syncDataWithDaily}
      >
        {loading ? 'Sincronizando...' : '☁️ Salvar na Nuvem'}
      </button>

      {lastSynced && (
        <p className="mt-4 text-center text-[9px] text-white/30 font-bold uppercase tracking-widest">
          Sincronizado: {lastSynced.toLocaleTimeString()}
        </p>
      )}

      {/* PIP MODE BUTTON FLOATS OVER EVERYTHING */}
      <PiPMode
        netProfit={results.netProfit}
        margin={results.profitMargin}
        profitPerKm={results.profitPerKm}
      />
    </div>
  );
}
