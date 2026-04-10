import { useState, useEffect } from 'react';
import { supabase } from '../lib/supabase';
import { ProfitInput } from '../lib/calculations';

export function useSync(initialData: ProfitInput) {
  const [data, setData] = useState<ProfitInput>(initialData);
  const [loading, setLoading] = useState(false);
  const [lastSynced, setLastSynced] = useState<Date | null>(null);

  // Load from LocalStorage on mount
  useEffect(() => {
    const localData = localStorage.getItem('ubi_driver_data');
    if (localData) {
      try {
        setData(JSON.parse(localData));
      } catch (e) {
        console.error('Failed to parse local data', e);
      }
    }
  }, []);

  // Save to LocalStorage whenever data changes
  useEffect(() => {
    localStorage.setItem('ubi_driver_data', JSON.stringify(data));
  }, [data]);

  const syncData = async () => {
    setLoading(true);
    try {
      // In a real app, we'd use auth. But for now, we'll just try to upsert by a device ID
      let deviceId = localStorage.getItem('ubi_device_id');
      if (!deviceId) {
        deviceId = Math.random().toString(36).substring(2);
        localStorage.setItem('ubi_device_id', deviceId);
      }

      const { error } = await supabase
        .from('driver_sessions')
        .upsert({ 
          id: deviceId, 
          data: data, 
          updated_at: new Date().toISOString() 
        });

      if (error) throw error;
      
      setLastSynced(new Date());
      return { success: true };
    } catch (error: any) {
      console.error('Sync failed:', error.message);
      return { success: false, error: error.message };
    } finally {
      setLoading(false);
    }
  };

  return { data, setData, syncData, loading, lastSynced };
}
