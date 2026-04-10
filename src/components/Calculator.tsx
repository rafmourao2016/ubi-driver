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
  });

  useEffect(() => {
    if (typeof window !== 'undefined' && (window as any).Capacitor) {
      const giguPlugin = (window as any).Capacitor.Plugins.GigUPlugin;
      if (giguPlugin) {
        giguPlugin.addListener('onUberOffer', (data: { price: number; km: number }) => {
          if (data.price > 0 || data.km > 0) {
            setInput(prev => ({
              ...prev,
              grossEarnings: data.price > 0 ? data.price : prev.grossEarnings,
              distanceKm: data.km > 0 ? data.km : prev.distanceKm,
            }));
          }
        });
      }
    }
  }, [setInput]);

  const results = useMemo(() => calculateProfit(input), [input]);

  const grade = useMemo(() => {
    if (results.profitMargin >= 30) return { label: 'ELITE', color: 'bg-green-500/20 text-green-400 border-green-500/30' };
    if (results.profitMargin >= 15) return { label: 'ACEITÁVEL', color: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30' };
    return { label: 'BAIXO LUCRO', color: 'bg-red-500/20 text-red-400 border-red-500/30' };
  }, [results.profitMargin]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setInput(prev => ({ ...prev, [name]: parseFloat(value) || 0 }));
  };

  const handleSync = async () => {
    const result = await syncData();
    if (result.success) {
      alert('Tudo salvo na nuvem! ✨');
    } else {
      alert('Ops! Erro ao sincronizar: ' + result.error);
    }
  };

  const formatCurrency = (val: number) => 
    new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(val);

  return (
    <div className="animate-fade-in max-w-md mx-auto p-6 pb-32">
      <header className="mb-10 flex justify-between items-center">
        <div>
          <h1 className="text-5xl font-extrabold tracking-tighter text-gradient">Ubi</h1>
          <p className="text-text-muted text-xs font-semibold uppercase tracking-widest mt-1">Smart Driver Pro</p>
        </div>
        <button 
          onClick={() => setInput(prev => ({ ...prev, grossEarnings: 25.50, distanceKm: 5.2 }))}
          className="p-3 bg-white/5 rounded-2xl hover:bg-white/10 transition-colors border border-white/10"
          title="Simular Leitura"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polygon points="5 3 19 12 5 21 5 3"/>
          </svg>
        </button>
      </header>

      <section className="card primary-gradient mb-8 p-8 relative overflow-hidden animate-float">
        <div className="absolute -right-4 -top-4 w-32 h-32 bg-white/10 rounded-full blur-3xl"></div>
        
        <div className="flex justify-between items-start mb-4">
          <label className="text-black/60 font-black text-[10px] uppercase tracking-widest">Lucro Líquido Estimado</label>
          <div className={`chip border ${grade.color}`}>
            {grade.label}
          </div>
        </div>

        <div className="text-6xl font-black text-black tracking-tighter mb-4">
          {formatCurrency(results.netProfit)}
        </div>

        <div className="grid grid-cols-2 gap-4 border-t border-black/10 pt-4">
          <div>
            <span className="block text-[10px] text-black/40 font-bold uppercase">Margem</span>
            <span className="text-black font-black text-xl">{results.profitMargin.toFixed(1)}%</span>
          </div>
          <div>
            <span className="block text-[10px] text-black/40 font-bold uppercase">Meta / Km</span>
            <span className="text-black font-black text-xl">{formatCurrency(results.profitPerKm)}</span>
          </div>
        </div>
      </section>

      <div className="grid grid-cols-2 gap-4 mb-8">
        <div className="input-group">
          <label>Ganho Bruto</label>
          <input 
            type="number" 
            name="grossEarnings" 
            value={input.grossEarnings || ''} 
            onChange={handleChange} 
            placeholder="0,00"
          />
        </div>
        <div className="input-group">
          <label>Distância (km)</label>
          <input 
            type="number" 
            name="distanceKm" 
            value={input.distanceKm || ''} 
            onChange={handleChange} 
            placeholder="0"
          />
        </div>
      </div>

      <section className="space-y-6">
        <div className="flex items-center gap-2 mb-2">
          <div className="h-[1px] flex-1 bg-border"></div>
          <span className="text-[10px] font-bold text-text-muted uppercase tracking-widest">Custos & Configurações</span>
          <div className="h-[1px] flex-1 bg-border"></div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="input-group">
            <label>Combustível</label>
            <input 
              type="number" 
              name="fuelPrice" 
              value={input.fuelPrice} 
              onChange={handleChange} 
              step="0.01"
            />
          </div>
          <div className="input-group">
            <label>Consumo (km/l)</label>
            <input 
              type="number" 
              name="fuelConsumptionLabel" 
              value={input.fuelConsumptionLabel} 
              onChange={handleChange}
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="input-group">
            <label>Taxa Base %</label>
            <input 
              type="number" 
              name="platformFeePercent" 
              value={input.platformFeePercent} 
              onChange={handleChange}
            />
          </div>
          <div className="input-group">
            <label>Gastos Extras</label>
            <input 
              type="number" 
              name="otherCosts" 
              value={input.otherCosts || ''} 
              onChange={handleChange}
              placeholder="0,00"
            />
          </div>
        </div>
      </section>

      <button 
        className={`btn mt-8 ${loading ? 'opacity-50 cursor-not-allowed' : ''}`}
        onClick={handleSync}
        disabled={loading}
      >
        {loading ? 'Sincronizando...' : 'Sincronizar com Nuvem'}
      </button>

      {lastSynced && (
        <p className="mt-4 text-center text-[10px] text-text-muted font-bold uppercase tracking-widest">
          Nuvem atualizada: {lastSynced.toLocaleTimeString()}
        </p>
      )}

      <PiPMode
        netProfit={results.netProfit}
        margin={results.profitMargin}
        profitPerKm={results.profitPerKm}
      />
    </div>
  );
}
