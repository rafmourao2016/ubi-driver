export interface ProfitInput {
  grossEarnings: number;
  distanceKm: number;
  fuelPrice: number;
  fuelConsumptionLabel: number; // km/l
  otherCosts: number;
  platformFeePercent: number;
}

export interface ProfitResult {
  netProfit: number;
  totalCosts: number;
  fuelCost: number;
  platformFee: number;
  profitPerKm: number;
  profitMargin: number;
}

export const calculateProfit = (input: ProfitInput): ProfitResult => {
  const {
    grossEarnings,
    distanceKm,
    fuelPrice,
    fuelConsumptionLabel,
    otherCosts,
    platformFeePercent,
  } = input;

  const platformFee = grossEarnings * (platformFeePercent / 100);
  const fuelCost = (distanceKm / fuelConsumptionLabel) * fuelPrice;
  const totalCosts = platformFee + fuelCost + otherCosts;
  const netProfit = grossEarnings - totalCosts;

  const profitPerKm = distanceKm > 0 ? netProfit / distanceKm : 0;
  const profitMargin = grossEarnings > 0 ? (netProfit / grossEarnings) * 100 : 0;

  return {
    netProfit,
    totalCosts,
    fuelCost,
    platformFee,
    profitPerKm,
    profitMargin,
  };
};
