'use client';

import React, { useMemo, useEffect } from 'react';
import { calculateProfit } from '../lib/calculations';
import { useSync } from '../hooks/useSync';

export default function Calculator() {
  const { data: input, setData: setInput, syncData, loading, lastSynced } = useSync({
    grossEarnings: 0,
    distanceKm: 0,
    fuelPrice: 5.80,
    fuelConsumptionLabel: 10,
    otherCosts: 0,
    platformFeePercent: 25,
  });

  // Listener para automação nativa (GigU Style)
  useEffect(() => {
    // Só roda se estiver no ambiente Capacitor
    if (typeof window !== 'undefined' && (window as any).Capacitor) {
      const giguPlugin = (window as any).Capacitor.Plugins.GigUPlugin;
      if (giguPlugin) {
        giguPlugin.addListener('onUberOffer', (data: { price: number; km: number }) => {
          console.log('Automação detectou oferta:', data);
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
    if (results.profitMargin >= 30) return { label: 'ELITE', color: 'bg-green-500' };
    if (results.profitMargin >= 15) return { label: 'ACEITÁVEL', color: 'bg-yellow-500' };
    return { label: 'BAIXO LUCRO', color: 'bg-red-500' };
  }, [results.profitMargin]);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value } = e.target;
    setInput(prev => ({ ...prev, [name]: parseFloat(value) || 0 }));
  };

  const handleSync = async () => {
    const result = await syncData();
    if (result.success) {
      alert('Dados sincronizados com sucesso!');
    } else {
      alert('Erro ao sincronizar: ' + result.error);
    }
  };

  const formatCurrency = (val: number) => 
    new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(val);

  return (
    <div className="animate-fade-in max-w-md mx-auto p-4">
      <header className="mb-8 text-center">
        <h1 className="text-4xl font-extrabold mb-2 text-gradient">Ubi</h1>
        <p className="text-text-muted">Driver Profit Optimizer</p>
      </header>

      <section className="card mb-6 primary-gradient bg-opacity-10 relative overflow-hidden">
        <div className={`absolute top-0 right-0 px-3 py-1 text-[10px] font-bold text-white uppercase tracking-tighter ${grade.color}`}>
          {grade.label}
        </div>
        <label className="text-black font-bold opacity-70">LUCRO LÍQUIDO</label>
        <div className="text-5xl font-black text-black">
          {formatCurrency(results.netProfit)}
        </div>
        <div className="mt-2 flex justify-between text-black opacity-80 font-semibold">
          <span>Margem: {results.profitMargin.toFixed(1)}%</span>
          <span>{formatCurrency(results.profitPerKm)}/km</span>
        </div>
      </section>

      <div className="grid grid-cols-2 gap-4 mb-6">
        <div className="input-group">
          <label>Ganho Bruto (R$)</label>
          <input 
            type="number" 
            name="grossEarnings" 
            value={input.grossEarnings || ''} 
            onChange={handleChange} 
            placeholder="0.00"
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

      <div className="card glass space-y-4">
        <h3 className="font-bold text-sm uppercase tracking-widest text-primary">Configurações de Custo</h3>
        
        <div className="grid grid-cols-2 gap-4">
          <div className="input-group">
            <label>Preço Combustível</label>
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

        <div className="input-group">
          <label>Taxa Plataforma (%)</label>
          <input 
            type="number" 
            name="platformFeePercent" 
            value={input.platformFeePercent} 
            onChange={handleChange}
          />
        </div>

        <div className="input-group">
          <label>Outros Custos (R$)</label>
          <input 
            type="number" 
            name="otherCosts" 
            value={input.otherCosts || ''} 
            onChange={handleChange}
          />
        </div>
      </div>

      <button 
        className={`btn mt-8 shadow-lg shadow-primary/20 ${loading ? 'opacity-50 cursor-not-allowed' : ''}`}
        onClick={handleSync}
        disabled={loading}
      >
        {loading ? 'Sincronizando...' : 'Sincronizar Dados'}
      </button>

      {/* Botão de teste para simular leitura de tela */}
      <button 
        className="mt-4 text-[10px] text-text-muted hover:text-primary transition-colors"
        onClick={() => {
          // Simula o que o Java enviaria via bridge
          const mockData = { price: 25.50, km: 5.2 };
          console.log('Simulando oferta detectada:', mockData);
          if (typeof window !== 'undefined' && (window as any).Capacitor) {
             const event = new CustomEvent('onUberOffer', { detail: mockData });
             // Emulando o comportamento do listener interno
             setInput(prev => ({
                ...prev,
                grossEarnings: mockData.price,
                distanceKm: mockData.km
             }));
          } else {
            // Se não estiver no Capacitor, apenas atualiza o estado para teste visual
            setInput(prev => ({ ...prev, grossEarnings: 25.50, distanceKm: 5.2 }));
          }
        }}
      >
        Simular Leitura de Tela (Dev Only)
      </button>

      {lastSynced && (
        <p className="mt-2 text-center text-xs text-text-muted">
          Última sincronização: {lastSynced.toLocaleTimeString()}
        </p>
      )}

      <div className="mt-12 text-center text-xs text-text-muted">
        <p>Desenvolvido para motoristas de elite.</p>
      </div>
    </div>
  );
}
