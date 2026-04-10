import { createClient } from '@supabase/supabase-js';

// Fallback to dummy values during build to prevent crash when env vars are missing
const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL || 'https://placeholder-project.supabase.co';
const supabaseAnonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || 'placeholder-key';

if (!process.env.NEXT_PUBLIC_SUPABASE_URL || !process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY) {
  if (typeof window !== 'undefined') {
    console.warn('Supabase credentials are missing. Check your environment variables.');
  }
}

export const supabase = createClient(supabaseUrl, supabaseAnonKey);
