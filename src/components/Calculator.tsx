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

  // Novos estados para a Meta Diária com persistência
  const [dailyGoal, setDailyGoal] = React.useState(250);
  const [dailyAccumulated, setDailyAccumulated] = React.useState(0);

  // Carregar do LocalStorage ao montar
  useEffect(() => {
    const savedGoal = localStorage.getItem('ubi_daily_goal');
    const savedAcc = localStorage.getItem('ubi_daily_acc');
    if (savedGoal) setDailyGoal(parseFloat(savedGoal));
    if (savedAcc) setDailyAccumulated(parseFloat(savedAcc));
  }, []);

  // Salvar no LocalStorage sempre que mudar
  useEffect(() => {
    localStorage.setItem('ubi_daily_goal', dailyGoal.toString());
    localStorage.setItem('ubi_daily_acc', dailyAccumulated.toString());
  }, [dailyGoal, dailyAccumulated]);

  // Sincroniza configurações com o código Nativo (Java) sempre que mudar
  useEffect(() => {
    if (typeof window !== 'undefined' && (window as any).Capacitor) {
      const giguPlugin = (window as any).Capacitor.Plugins.GigUPlugin;
      if (giguPlugin) {
        // Envia custos para a Overlay Nativa
        giguPlugin.updateSettings({
          fuelPrice: input.fuelPrice,
          fuelConsumption: input.fuelConsumptionLabel,
          platformFee: input.platformFeePercent
        });

        // Ouvinte de ofertas (existente)
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
    if (results.profitMargin >= 30) return { label: 'ELITE', color: 'bg-green-500/20 text-green-400 border-green-500/30' };
    if (results.profitMargin >= 15) return { label: 'ACEITÁVEL', color: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30' };
    return { label: 'BAIXO LUCRO', color: 'bg-red-500/20 text-red-400 border-red-500/30' };
  }, [results.profitMargin]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    const numericValue = value === '' ? 0 : parseFloat(value);
    setInput(prev => ({ ...prev, [name]: numericValue }));
  };

  const handleConfirmRide = () => {
    setDailyAccumulated(prev => prev + results.netProfit);
    alert('Corrida contabilizada no seu dia! 🚀');
  };

  const handleResetDay = () => {
    if (confirm('Deseja zerar os ganhos de hoje?')) {
      setDailyAccumulated(0);
    }
  };

  const syncDataWithDaily = async () => {
    // Sincroniza os dados base + os dados diários
    const result = await syncData();
    if (result.success) {
      alert('Tudo salvo na nuvem! ✨');
    } else {
      alert('Ops! Erro ao sincronizar: ' + result.error);
    }
  };

  const formatCurrency = (val: number) => 
    new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(val);

  const dailyProgressPercent = Math.min((dailyAccumulated / dailyGoal) * 100, 100);

  return (
    <div className="animate-fade-in max-w-md mx-auto p-6 pb-32">
      <header className="mb-8 flex justify-between items-center">
        <div>
          <h1 className="text-5xl font-extrabold tracking-tighter text-gradient">Ubi</h1>
          <p className="text-text-muted text-xs font-semibold uppercase tracking-widest mt-1">Smart Driver Pro</p>
        </div>
        <div className="flex gap-2">
          <button 
            onClick={handleResetDay}
            className="p-3 bg-white/5 rounded-2xl hover:bg-white/10 transition-colors border border-white/10"
            title="Zerar Dia"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/>
              <path d="M3 3v5h5"/>
            </svg>
          </button>
          <button 
            onClick={() => setInput(prev => ({ ...prev, grossEarnings: 25.50, distanceKm: 5.2 }))}
            className="p-3 bg-white/5 rounded-2xl hover:bg-white/10 transition-colors border border-white/10"
            title="Simular Leitura"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polygon points="5 3 19 12 5 21 5 3"/>
            </svg>
          </button>
        </div>
      </header>

      {/* PAINEL DIÁRIO */}
      <section className="card mb-8 p-6 glass border-primary/20 relative overflow-hidden">
        <div className="absolute top-0 right-0 p-2">
          <input 
            type="number" 
            className="bg-transparent text-right text-[10px] font-bold text-primary w-20 outline-none hover:bg-white/5 rounded"
            value={dailyGoal}
            onChange={(e) => setDailyGoal(parseFloat(e.target.value) || 0)}
            title="Editar Meta"
          />
        </div>
        <div className="flex justify-between items-end mb-4">
          <div>
            <label className="mb-1">Progresso do Dia</label>
            <div className="text-3xl font-black">{formatCurrency(dailyAccumulated)}</div>
          </div>
          <div className="text-right">
            <span className="text-[10px] font-bold text-text-muted uppercase">Meta: {formatCurrency(dailyGoal)}</span>
          </div>
        </div>

        
        <div className="h-3 bg-white/5 rounded-full overflow-hidden mb-2">
          <div 
            className="h-full bg-gradient-to-r from-primary to-accent transition-all duration-1000" 
            style={{ width: `${dailyProgressPercent}%` }}
          ></div>
        </div>
        
        <div className="flex justify-between text-[10px] font-bold text-text-muted uppercase">
          <span>{dailyProgressPercent.toFixed(0)}% Concluído</span>
          <span>Falta: {formatCurrency(Math.max(dailyGoal - dailyAccumulated, 0))}</span>
        </div>
      </section>

      <section className="card primary-gradient mb-8 p-8 relative overflow-hidden animate-float">
        <div className="absolute -right-4 -top-4 w-32 h-32 bg-white/10 rounded-full blur-3xl"></div>
        
        <div className="flex justify-between items-start mb-4">
          <label className="text-black/60 font-black text-[10px] uppercase tracking-widest">Lucro desta Corrida</label>
          <div className={`chip border ${grade.color}`}>
            {grade.label}
          </div>
        </div>

        <div className="text-6xl font-black text-black tracking-tighter mb-4">
          {formatCurrency(results.netProfit)}
        </div>

        <button 
          onClick={handleConfirmRide}
          className="w-full py-2 bg-black/10 hover:bg-black/20 rounded-xl mb-4 text-[10px] font-black uppercase tracking-widest text-black border border-black/10 transition-colors"
        >
          🚀 Validar e Somar ao Dia
        </button>

        <div className="grid grid-cols-2 gap-4 border-t border-black/10 pt-4">
          <div>
            <span className="block text-[10px] text-black/40 font-bold uppercase">Real / Km</span>
            <span className="text-black font-black text-xl">{formatCurrency(results.profitPerKm)}</span>
          </div>
          <div>
            <span className="block text-[10px] text-black/40 font-bold uppercase">Bruto p/ Meta</span>
            <span className="text-black font-black text-xl">{formatCurrency(results.targetGrossPrice)}</span>
          </div>
        </div>
      </section>

        <div className="grid grid-cols-2 gap-4 mb-8">
          <div className="input-group">
            <label>Ganho Bruto</label>
            <input 
              type="number" 
              name="grossEarnings" 
              value={input.grossEarnings === 0 ? '' : input.grossEarnings} 
              onChange={handleChange} 
              placeholder="0,00"
            />
          </div>
          <div className="input-group">
            <label>Distância (km)</label>
            <input 
              type="number" 
              name="distanceKm" 
              value={input.distanceKm === 0 ? '' : input.distanceKm} 
              onChange={handleChange} 
              placeholder="0"
            />
          </div>
        </div>

        <section className="space-y-6">
          <div className="flex items-center gap-2 mb-2">
            <div className="h-[1px] flex-1 bg-border"></div>
            <span className="text-[10px] font-bold text-text-muted uppercase tracking-widest">Metas & Parâmetros</span>
            <div className="h-[1px] flex-1 bg-border"></div>
          </div>

          <div className="input-group">
            <label className="flex justify-between">
              <span>Meta Lucro Líquido / Km</span>
              <span className="text-primary">{formatCurrency(input.targetProfitPerKm)}/km</span>
            </label>
            <input 
              type="range" 
              name="targetProfitPerKm" 
              min="0.5" 
              max="5" 
              step="0.1"
              value={input.targetProfitPerKm} 
              onChange={(e) => setInput(prev => ({ ...prev, targetProfitPerKm: parseFloat(e.target.value) }))}
              className="w-full accent-primary h-2 bg-white/5 rounded-lg appearance-none cursor-pointer"
            />
          </div>

          <div className="grid grid-cols-2 gap-4 pt-4 border-t border-white/5">
            <div className="input-group">
              <label>Combustível</label>
              <input 
                type="number" 
                name="fuelPrice" 
                value={input.fuelPrice === 0 ? '' : input.fuelPrice} 
                onChange={handleChange} 
                step="0.01"
              />
            </div>
            <div className="input-group">
              <label>Consumo (km/l)</label>
              <input 
                type="number" 
                name="fuelConsumptionLabel" 
                value={input.fuelConsumptionLabel === 0 ? '' : input.fuelConsumptionLabel} 
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
                value={input.platformFeePercent === 0 ? '' : input.platformFeePercent} 
                onChange={handleChange}
              />
            </div>
            <div className="input-group">
              <label>Gastos Extras</label>
              <input 
                type="number" 
                name="otherCosts" 
                value={input.otherCosts === 0 ? '' : input.otherCosts} 
                onChange={handleChange}
                placeholder="0,00"
              />
            </div>
          </div>
        </section>


      <button 
        className={`btn mt-8 ${loading ? 'opacity-50 cursor-not-allowed' : ''}`}
        disabled={loading}
        onClick={syncDataWithDaily}
      >
        {loading ? 'Sincronizando...' : 'Nuvem'}
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
