import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { TableSettings } from '@/types/game';
import { soundFx } from '@/utils/audio';
import { loadSettings, saveSettings } from '@/utils/storage';

export const DEFAULT_SETTINGS: TableSettings = {
  soundEnabled: true,
  soundVolume: 0.7,
  tableTheme: 'slate',
  cardBack: 'geometric-blue',
  fourColorDeck: false,
  sortBy: 'suit',
};

interface SettingsValue {
  settings: TableSettings;
  /** Applies a change; the sound engine follows at once, so a click right after enabling it is heard. */
  update: (patch: Partial<TableSettings>) => void;
}

const SettingsContext = createContext<SettingsValue | null>(null);

/**
 * The one owner of table settings: every board, the offline table and the settings modal read
 * them here, the sound engine is synced from here only, and they persist across reloads.
 */
export const SettingsProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [settings, setSettings] = useState<TableSettings>(() => ({ ...DEFAULT_SETTINGS, ...loadSettings() }));

  useEffect(() => {
    soundFx.enabled = settings.soundEnabled;
    soundFx.volume = settings.soundVolume;
    saveSettings(settings);
  }, [settings]);

  const update = useCallback((patch: Partial<TableSettings>) => {
    if (patch.soundEnabled !== undefined) soundFx.enabled = patch.soundEnabled;
    if (patch.soundVolume !== undefined) soundFx.volume = patch.soundVolume;
    setSettings((prev) => ({ ...prev, ...patch }));
  }, []);

  const value = useMemo(() => ({ settings, update }), [settings, update]);
  return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>;
};

export function useSettings(): SettingsValue {
  const value = useContext(SettingsContext);
  if (!value) throw new Error('useSettings needs a SettingsProvider above it');
  return value;
}
