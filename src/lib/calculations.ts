export interface ProfitInput {
  grossEarnings: number;
  distanceKm: number;
  fuelPrice: number;
  fuelConsumptionLabel: number; // km/l
  otherCosts: number;
  platformFeePercent: number;
  targetProfitPerKm: number; // Nova meta de lucro líquido por KM
}

export interface ProfitResult {
  netProfit: number;
  totalCosts: number;
  fuelCost: number;
  platformFee: number;
  profitPerKm: number;
  profitMargin: number;
  targetGrossPrice: number; // Valor bruto necessário para atingir a meta
}

export const calculateProfit = (input: ProfitInput): ProfitResult => {
  const {
    grossEarnings,
    distanceKm,
    fuelPrice,
    fuelConsumptionLabel,
    otherCosts,
    platformFeePercent,
    targetProfitPerKm,
  } = input;

  const platformFee = grossEarnings * (platformFeePercent / 100);
  const fuelCost = (distanceKm / fuelConsumptionLabel) * fuelPrice;
  const totalCosts = platformFee + fuelCost + otherCosts;
  const netProfit = grossEarnings - totalCosts;

  const profitPerKm = distanceKm > 0 ? netProfit / distanceKm : 0;
  const profitMargin = grossEarnings > 0 ? (netProfit / grossEarnings) * 100 : 0;

  // Cálculo do Bruto Necessário para a Meta:
  // Gross = (TargetNet + Fuel + Other) / (1 - Platform%)
  const targetNetProfit = distanceKm * targetProfitPerKm;
  const targetGrossPrice = (targetNetProfit + fuelCost + otherCosts) / (1 - (platformFeePercent / 100));

  return {
    netProfit,
    totalCosts,
    fuelCost,
    platformFee,
    profitPerKm,
    profitMargin,
    targetGrossPrice,
  };
};

