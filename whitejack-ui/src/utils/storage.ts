import type { TableSettings } from '@/types/game';

// Web Storage can throw (private windows, blocked site data), so every access goes through
// here and degrades to "nothing stored" instead of crashing the page.

function read(storage: () => Storage, key: string): string | null {
  try {
    return storage().getItem(key);
  } catch {
    return null;
  }
}

function write(storage: () => Storage, key: string, value: string): void {
  try {
    storage().setItem(key, value);
  } catch {
    // storage blocked: callers treat stored values as a convenience, never a requirement
  }
}

export const readLocal = (key: string) => read(() => localStorage, key);
export const writeLocal = (key: string, value: string) => write(() => localStorage, key, value);
export const readSession = (key: string) => read(() => sessionStorage, key);
export const writeSession = (key: string, value: string) => write(() => sessionStorage, key, value);

const NICK_KEY = 'whitejack.nick';

/** §4: the nickname is a label kept in localStorage, not part of identity. */
/** The name used last time on this browser, or '' if none. */
export const loadNick = () => readLocal(NICK_KEY) ?? '';
export const saveNick = (nick: string) => writeLocal(NICK_KEY, nick);

const SETTINGS_KEY = 'whitejack.settings';

/** Saved table settings, or {} when none are stored or they cannot be read. */
export function loadSettings(): Partial<TableSettings> {
  try {
    const parsed = JSON.parse(readLocal(SETTINGS_KEY) ?? '{}');
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

export const saveSettings = (settings: TableSettings) => writeLocal(SETTINGS_KEY, JSON.stringify(settings));
