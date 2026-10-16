import React from 'react';
import { Dialog, dialogPrimaryButton } from '@/components/Dialog';
import { useSettings } from '@/app/SettingsContext';
import { TableSettings } from '@/types/game';
import { soundFx } from '@/utils/audio';

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({ isOpen, onClose }) => {
  const { settings, update } = useSettings();
  if (!isOpen) return null;

  const handleToggleSound = () => {
    soundFx.playClick();
    const updated = !settings.soundEnabled;
    update({ soundEnabled: updated });
  };

  const handleVolumeChange = (vol: number) => {
    update({ soundVolume: vol });
  };

  const handleSelectTheme = (theme: TableSettings['tableTheme']) => {
    soundFx.playClick();
    update({ tableTheme: theme });
  };

  const handleSelectCardBack = (back: TableSettings['cardBack']) => {
    soundFx.playCardDeal();
    update({ cardBack: back });
  };

  const handleToggleFourColor = () => {
    soundFx.playClick();
    update({ fourColorDeck: !settings.fourColorDeck });
  };

  const handleToggleSort = (sortBy: 'suit' | 'rank') => {
    soundFx.playClick();
    update({ sortBy });
  };

  return (
    <Dialog
      title="Preferences"
      description="Applied at every table you sit at."
      onClose={onClose}
      footer={
        <button type="button" onClick={onClose} className={dialogPrimaryButton}>
          Done
        </button>
      }
    >
      <div className="flex flex-col gap-6 text-sm">
        <fieldset className="flex flex-col gap-2.5">
          <legend className={groupLabel}>Table surface</legend>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
            {TABLE_THEMES.map((t) => {
              const active = settings.tableTheme === t.id;
              return (
                <button
                  key={t.id}
                  type="button"
                  aria-pressed={active}
                  onClick={() => handleSelectTheme(t.id)}
                  className={`${option} ${active ? optionActive : optionIdle} p-1.5 flex flex-col gap-1.5`}
                >
                  <span aria-hidden className={`w-full h-9 rounded ${t.swatch}`} />
                  <span className="text-[13px] font-medium text-stone-800 px-0.5">{t.name}</span>
                </button>
              );
            })}
          </div>
        </fieldset>

        <fieldset className="flex flex-col gap-2.5">
          <legend className={groupLabel}>Card back</legend>
          <div className="grid grid-cols-3 gap-2">
            {CARD_BACKS.map((b) => {
              const active = settings.cardBack === b.id;
              return (
                <button
                  key={b.id}
                  type="button"
                  aria-pressed={active}
                  onClick={() => handleSelectCardBack(b.id)}
                  className={`${option} ${active ? optionActive : optionIdle} px-3 py-2.5 text-left`}
                >
                  <span className="text-[13px] font-medium text-stone-900 block">{b.name}</span>
                  <span className="text-xs text-stone-500 block">{b.desc}</span>
                </button>
              );
            })}
          </div>
        </fieldset>

        <div className="flex flex-col divide-y divide-stone-100 border-y border-stone-100">
          <Row title="Four-color deck" detail="Black spades, red hearts, blue diamonds, green clubs.">
            <Switch label="Four-color deck" on={settings.fourColorDeck} onToggle={handleToggleFourColor} />
          </Row>

          <Row title="Sort hand by" detail="How cards line up in your hand.">
            <div role="radiogroup" aria-label="Sort hand by" className="flex items-center bg-stone-100 rounded-md p-0.5">
              {(['suit', 'rank'] as const).map((key) => (
                <button
                  key={key}
                  type="button"
                  role="radio"
                  aria-checked={settings.sortBy === key}
                  onClick={() => handleToggleSort(key)}
                  className={`h-7 px-3 rounded text-[13px] font-medium capitalize transition-colors cursor-pointer ${
                    settings.sortBy === key ? 'bg-white text-stone-900 shadow-hairline' : 'text-stone-500 hover:text-stone-900'
                  }`}
                >
                  {key}
                </button>
              ))}
            </div>
          </Row>

          <Row title="Sound effects" detail="Card deals, clicks and turn cues.">
            <Switch label="Sound effects" on={settings.soundEnabled} onToggle={handleToggleSound} />
          </Row>

          {settings.soundEnabled && (
            <label className="flex items-center gap-3 py-3">
              <span className="sr-only">Volume</span>
              <span aria-hidden className="material-symbols-outlined text-[18px] text-stone-400">volume_down</span>
              <input
                type="range"
                min="0.1"
                max="1.0"
                step="0.05"
                value={settings.soundVolume}
                onChange={(e) => handleVolumeChange(parseFloat(e.target.value))}
                className="flex-1 accent-stone-900 h-1 cursor-pointer"
              />
              <span aria-hidden className="material-symbols-outlined text-[18px] text-stone-400">volume_up</span>
            </label>
          )}
        </div>
      </div>
    </Dialog>
  );
};

const groupLabel = 'text-[13px] font-medium text-stone-700 mb-2.5';
const option = 'rounded-lg border transition-[border-color,box-shadow] cursor-pointer';
const optionIdle = 'border-stone-200 hover:border-stone-300';
const optionActive = 'border-stone-900 ring-1 ring-stone-900';

const TABLE_THEMES: { id: TableSettings['tableTheme']; name: string; swatch: string }[] = [
  { id: 'slate', name: 'Charcoal', swatch: 'bg-stone-900' },
  { id: 'emerald', name: 'Felt', swatch: 'table-felt-pattern' },
  { id: 'navy', name: 'Navy', swatch: 'bg-[#1b2433]' },
  { id: 'studio', name: 'Light', swatch: 'bg-stone-100 border border-stone-200' },
];

const CARD_BACKS: { id: TableSettings['cardBack']; name: string; desc: string }[] = [
  { id: 'geometric-blue', name: 'Ink', desc: 'Fine lattice' },
  { id: 'classic-cross', name: 'Felt', desc: 'Crosshatch' },
  { id: 'crimson-diamond', name: 'Oxblood', desc: 'Diamond dot' },
];

const Row: React.FC<{ title: string; detail: string; children: React.ReactNode }> = ({ title, detail, children }) => (
  <div className="flex items-center justify-between gap-4 py-3">
    <div className="flex flex-col gap-0.5 min-w-0">
      <span className="text-sm font-medium text-stone-900">{title}</span>
      <span className="text-[13px] text-stone-500">{detail}</span>
    </div>
    {children}
  </div>
);

const Switch: React.FC<{ label: string; on: boolean; onToggle: () => void }> = ({ label, on, onToggle }) => (
  <button
    type="button"
    role="switch"
    aria-checked={on}
    aria-label={label}
    onClick={onToggle}
    className={`w-9 h-5 shrink-0 flex items-center rounded-full p-0.5 transition-colors cursor-pointer ${
      on ? 'bg-stone-900' : 'bg-stone-300'
    }`}
  >
    <span
      className={`bg-white w-4 h-4 rounded-full shadow-hairline transition-transform ${on ? 'translate-x-4' : 'translate-x-0'}`}
    />
  </button>
);
