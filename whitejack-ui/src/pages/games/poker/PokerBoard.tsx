import React, { useEffect, useState } from 'react';
import { BotThinking } from '@/components/BotThinking';
import { CardView } from '@/components/CardView';
import { useSettings } from '@/app/SettingsContext';
import { isBotNick } from '@/network/api';
import type { BoardProps } from '@/pages/games/live/boards';
import { TableSettings } from '@/types/game';
import { PokerHandResult, PokerSeat, PokerView } from '@/types/wire';
import { soundFx } from '@/utils/audio';
import { cardKey, toUiCard } from '@/utils/cards';


const STREET_LABEL = { PREFLOP: 'Pre-flop', FLOP: 'Flop', TURN: 'Turn', RIVER: 'River' } as const;

const chips = (n: number) => n.toLocaleString();

/**
 * Where seat `offset` (0 = you) sits round the oval, as percentages of the felt. You are at
 * the bottom and play passes clockwise, which on screen runs from you to your left.
 */
const around = (offset: number, count: number, radiusX: number, radiusY: number) => {
  const angle = Math.PI / 2 + (offset * 2 * Math.PI) / count;
  return { left: `${50 + radiusX * Math.cos(angle)}%`, top: `${50 + radiusY * Math.sin(angle)}%` };
};

/**
 * No-limit Hold'em, rendered entirely from the server's view. Which moves are open, and the
 * raise range, come from `view.legal` / `minRaiseTo` / `maxRaiseTo`; the raise presets only
 * pick a number inside that range, so this component never decides a rule.
 */
export const PokerBoard: React.FC<BoardProps<PokerView>> = ({ view, playerId, intent, aside }) => {
  const { settings } = useSettings();
  const myTurn = view.phase === 'BETTING' && view.onClock === playerId && view.legal.length > 0;
  const canRaise = myTurn && view.legal.includes('raise');
  const [raiseTo, setRaiseTo] = useState(view.minRaiseTo);

  // Each new decision starts the raise box at the minimum.
  useEffect(() => setRaiseTo(view.minRaiseTo), [view.minRaiseTo, view.maxRaiseTo, view.hand, view.street, view.onClock]);

  const myIndex = Math.max(0, view.seats.findIndex((s) => s.id === playerId));
  const me = view.seats[myIndex];
  const nickOf = (id: string | null) => (id === playerId ? 'You' : view.seats.find((s) => s.id === id)?.nick ?? '…');
  const ordered = view.seats.map((_, i) => view.seats[(myIndex + i) % view.seats.length]);

  const send = (payload: { type: string; to?: number }) => {
    // Chips for money going in, a mucked card for a fold, a knock on the table for a check.
    if (payload.type === 'call' || payload.type === 'raise') soundFx.playChips();
    else if (payload.type === 'fold') soundFx.playCardPlay();
    else soundFx.playKnock();
    intent(payload);
  };
  const clampRaise = (n: number) => Math.max(view.minRaiseTo, Math.min(view.maxRaiseTo, Math.round(n)));
  // Pot-sized: call first, then raise by the whole pot including that call.
  const potRaise = view.currentBet + view.toCall + view.pot;
  const presets: { label: string; to: number }[] = [
    { label: 'Min', to: view.minRaiseTo },
    { label: '½ Pot', to: clampRaise(view.currentBet + (view.toCall + view.pot) / 2) },
    { label: 'Pot', to: clampRaise(potRaise) },
    { label: 'All-in', to: view.maxRaiseTo },
  ];
  const opening = view.currentBet === 0;
  const raiseLabel = raiseTo >= view.maxRaiseTo ? `All-in ${chips(view.maxRaiseTo)}` : `${opening ? 'Bet' : 'Raise to'} ${chips(raiseTo)}`;

  const status = () => {
    if (view.phase === 'GAME_OVER') return `${nickOf(view.winner)} won every chip`;
    if (myTurn) return view.toCall > 0 ? `Your move — ${chips(view.toCall)} to call` : 'Your move — check or bet';
    if (me && !me.inHand) return 'You are out of chips — watching';
    return `Waiting on ${nickOf(view.onClock)}`;
  };

  return (
    // Fills the viewport between navbar and footer on wide screens: table and hand on the left,
    // status and chips on the right.
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem] xl:grid-cols-[minmax(0,1fr)_22rem] items-start">
      <div className="flex flex-col gap-4 min-w-0 lg:min-h-[calc(100dvh-15rem)]">
        {/* Table: an oval of felt, seats round the rim, board and pot in the middle */}
        <section className="flex-1 rounded-xl bg-stone-900 p-3 sm:p-5 flex">
          <div className="relative flex-1 min-h-[26rem] sm:min-h-[30rem]">
            <div className="absolute inset-x-[7%] inset-y-[11%] rounded-[50%] bg-emerald-900 border-[10px] border-stone-800 shadow-[inset_0_0_60px_rgba(0,0,0,0.45)]" />

            {/* Board and pot */}
            <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 flex flex-col items-center gap-2.5">
              <span className="text-xs sm:text-sm font-bold text-amber-200 font-mono-code">Pot {chips(view.pot)}</span>
              <div className="flex gap-1 sm:gap-1.5">
                {[0, 1, 2, 3, 4].map((i) =>
                  view.board[i] ? (
                    <CardView
                      key={cardKey(view.board[i])}
                      card={toUiCard(view.board[i])}
                      size="sm"
                      className="sm:w-12 sm:h-[4.2rem] md:w-14 md:h-[4.9rem]"
                      cardBack={settings.cardBack}
                      fourColor={settings.fourColorDeck}
                    />
                  ) : (
                    <div
                      key={i}
                      className="w-10 h-14 sm:w-12 sm:h-[4.2rem] md:w-14 md:h-[4.9rem] rounded-[4px] border border-dashed border-emerald-700/70"
                    />
                  )
                )}
              </div>
              <span className="text-xs font-medium tracking-wide text-emerald-200/80">
                {STREET_LABEL[view.street]}
              </span>
            </div>

            {ordered.map((seat, offset) => (
              <React.Fragment key={seat.id}>
                <div
                  className="absolute -translate-x-1/2 -translate-y-1/2"
                  style={around(offset, ordered.length, 43, 41)}
                >
                  <PokerSeatBadge seat={seat} view={view} you={seat.id === playerId} settings={settings} />
                </div>
                {seat.bet > 0 && (
                  <div
                    className="absolute -translate-x-1/2 -translate-y-1/2 rounded-full bg-amber-400 text-stone-950 text-[11px] font-bold font-mono-code px-2 py-0.5 shadow"
                    style={around(offset, ordered.length, 27, 24)}
                  >
                    {chips(seat.bet)}
                  </div>
                )}
              </React.Fragment>
            ))}
          </div>
        </section>

        {/* Your cards and the betting controls */}
        <section
          className={`rounded-xl border px-3 sm:px-5 py-4 flex flex-col md:flex-row md:items-center gap-4 bg-stone-900 ${
            myTurn ? 'border-felt-400 ring-2 ring-felt-400/60' : 'border-stone-900'
          }`}
        >
          <div className="flex items-center gap-4 shrink-0">
            <div className="flex">
              {view.myCards.length > 0 ? (
                view.myCards.map((card) => (
                  <CardView
                    key={cardKey(card)}
                    card={toUiCard(card)}
                    size="lg"
                    className="-ml-6 first:ml-0"
                    cardBack={settings.cardBack}
                    fourColor={settings.fourColorDeck}
                  />
                ))
              ) : (
                <span className="text-sm text-stone-400 py-6">No cards this hand.</span>
              )}
            </div>
            <div className="flex flex-col text-white">
              <span className="text-sm font-semibold">Your hand</span>
              <span className="text-xs text-stone-400">{chips(me?.stack ?? 0)} chips behind</span>
            </div>
          </div>

          <div className="flex-1 flex flex-col gap-3 min-w-0">
            {canRaise && (
              <div className="flex flex-col gap-2">
                <div className="flex flex-wrap gap-1.5">
                  {presets.map((preset) => (
                    <button
                      key={preset.label}
                      type="button"
                      onClick={() => setRaiseTo(preset.to)}
                      className={`h-8 px-3 rounded-md text-xs font-semibold border cursor-pointer ${
                        raiseTo === preset.to
                          ? 'bg-felt-600 border-felt-500 text-white'
                          : 'bg-stone-800 border-stone-700 text-stone-200 hover:bg-stone-700'
                      }`}
                    >
                      {preset.label}
                    </button>
                  ))}
                </div>
                <div className="flex items-center gap-3">
                  <input
                    type="range"
                    min={view.minRaiseTo}
                    max={view.maxRaiseTo}
                    step={1}
                    value={raiseTo}
                    onChange={(e) => setRaiseTo(clampRaise(Number(e.target.value)))}
                    className="flex-1 accent-felt-500 cursor-pointer"
                    aria-label="Raise amount"
                  />
                  <input
                    type="number"
                    min={view.minRaiseTo}
                    max={view.maxRaiseTo}
                    value={raiseTo}
                    onChange={(e) => setRaiseTo(Number(e.target.value))}
                    onBlur={() => setRaiseTo(clampRaise(raiseTo))}
                    className="w-24 h-9 rounded-md bg-stone-800 border border-stone-700 text-white text-sm font-mono-code px-2"
                    aria-label="Raise to"
                  />
                </div>
              </div>
            )}

            <div className="flex flex-wrap gap-2">
              <ActionButton
                label="Fold"
                disabled={!myTurn}
                onClick={() => send({ type: 'fold' })}
                className="bg-stone-700 hover:bg-stone-600"
              />
              {view.legal.includes('call') ? (
                <ActionButton
                  label={`Call ${chips(view.toCall)}`}
                  disabled={!myTurn}
                  onClick={() => send({ type: 'call' })}
                  className="bg-stone-50 hover:bg-white text-stone-900!"
                />
              ) : (
                <ActionButton
                  label="Check"
                  disabled={!myTurn || !view.legal.includes('check')}
                  onClick={() => send({ type: 'check' })}
                  className="bg-stone-50 hover:bg-white text-stone-900!"
                />
              )}
              {canRaise && (
                <ActionButton
                  label={raiseLabel}
                  disabled={raiseTo < view.minRaiseTo || raiseTo > view.maxRaiseTo}
                  onClick={() => send({ type: 'raise', to: raiseTo })}
                  className="bg-felt-500 hover:bg-felt-400"
                />
              )}
            </div>
          </div>
        </section>
      </div>

      {/* Sidebar: where the hand stands, the chip counts, and how the last hand ended */}
      <aside className="flex flex-col gap-4 lg:sticky lg:top-28">
        <section className="border border-stone-200 bg-white rounded-xl p-4 flex flex-col gap-3">
          <div className="flex items-center justify-between gap-2 text-sm">
            <span className="font-semibold font-display text-stone-900">Hand {view.hand + 1}</span>
            <span className="text-stone-600">{STREET_LABEL[view.street]}</span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            <span className="text-xs text-stone-600 bg-stone-100 border border-stone-200 px-2 py-0.5 rounded-full">
              Blinds {chips(view.smallBlind)} / {chips(view.bigBlind)}
            </span>
            <span className="text-xs font-semibold text-amber-800 bg-amber-50 border border-amber-200 px-2 py-0.5 rounded-full">
              Pot {chips(view.pot)}
            </span>
          </div>
          <p
            className={`text-sm font-semibold rounded-lg px-3 py-2 ${
              myTurn ? 'bg-felt-50 text-felt-700' : 'bg-stone-50 text-stone-600'
            }`}
          >
            {status()}
          </p>
        </section>

        {view.phase === 'GAME_OVER' && (
          <section role="status" className="rounded-xl border border-felt-200 bg-felt-50 p-4 font-semibold font-display text-felt-900 flex items-center gap-2">
            <span aria-hidden className="material-symbols-outlined text-[20px] text-felt-600">trophy</span>
            {nickOf(view.winner)} {view.winner === playerId ? 'win' : 'wins'} the table
          </section>
        )}

        <section className="border border-stone-200 bg-white rounded-xl p-4 flex flex-col gap-2">
          <h3 className="font-semibold font-display text-stone-900">Chips</h3>
          <ul className="flex flex-col divide-y divide-stone-100">
            {[...view.seats]
              .sort((a, b) => b.stack + b.bet - (a.stack + a.bet))
              .map((s) => (
                <li key={s.id} className="py-2 flex items-center justify-between gap-3">
                  <div className="flex flex-col min-w-0">
                    <span className={`text-sm font-semibold truncate ${s.id === playerId ? 'text-felt-700' : 'text-stone-900'}`}>
                      {s.nick}
                      {s.id === playerId && <span className="text-xs font-normal text-stone-500"> (you)</span>}
                    </span>
                    <span className="text-xs text-stone-500">
                      {!s.inHand ? (s.stack === 0 ? 'Out' : 'Sitting out') : s.folded ? 'Folded' : s.allIn ? 'All-in' : 'In the hand'}
                    </span>
                  </div>
                  <span className="text-lg font-bold font-mono-code text-stone-950">{chips(s.stack + s.bet)}</span>
                </li>
              ))}
          </ul>
        </section>

        {view.lastHand && <LastHand result={view.lastHand} nickOf={nickOf} settings={settings} />}
        {aside}
      </aside>
    </div>
  );
};

const ActionButton: React.FC<{ label: string; disabled: boolean; onClick: () => void; className: string }> = ({
  label,
  disabled,
  onClick,
  className,
}) => (
  <button
    type="button"
    onClick={onClick}
    disabled={disabled}
    className={`h-11 min-w-28 px-5 rounded-lg text-white text-sm font-medium disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer ${className}`}
  >
    {label}
  </button>
);

const PokerSeatBadge: React.FC<{ seat: PokerSeat; view: PokerView; you: boolean; settings: TableSettings }> = ({
  seat,
  view,
  you,
  settings,
}) => {
  const onClock = seat.id === view.onClock && view.phase === 'BETTING';
  const holding = seat.inHand && !seat.folded && !you;
  return (
    <div className={`flex flex-col items-center gap-1 ${seat.folded || !seat.inHand ? 'opacity-50' : ''}`}>
      {/* Face-down cards for anyone still in the hand; your own are shown below the table. */}
      {holding && (
        <div className="flex -mb-3">
          <CardView faceDown size="sm" cardBack={settings.cardBack} className="-rotate-6" />
          <CardView faceDown size="sm" cardBack={settings.cardBack} className="-ml-6 rotate-6" />
        </div>
      )}
      <div
        className={`relative rounded-xl px-2.5 sm:px-3 py-1.5 text-white border text-center min-w-24 ${
          onClock ? 'border-felt-400 bg-felt-600/40 ring-2 ring-felt-400' : 'border-stone-700 bg-stone-800'
        }`}
      >
        {seat.id === view.dealer && (
          <span
            title="Dealer button"
            className="absolute -top-2 -right-2 w-5 h-5 rounded-full bg-white text-stone-950 text-[10px] font-bold flex items-center justify-center shadow"
          >
            D
          </span>
        )}
        <div className="text-xs sm:text-sm font-semibold truncate max-w-28">
          {seat.nick}
          {you && <span className="text-[11px] font-normal text-stone-300"> (you)</span>}
        </div>
        <div className="text-[11px] sm:text-xs font-mono-code text-amber-200">{chips(seat.stack)}</div>
        {onClock && !you && isBotNick(seat.nick) && (
          <div className="flex justify-center">
            <BotThinking />
          </div>
        )}
        {(seat.lastAction || seat.allIn) && (
          <div className="text-[10px] text-stone-300">{seat.allIn ? 'All-in' : seat.lastAction}</div>
        )}
      </div>
    </div>
  );
};

/** The previous hand: who took each pot, and every hand that was shown down. */
const LastHand: React.FC<{
  result: PokerHandResult;
  nickOf: (id: string | null) => string;
  settings: TableSettings;
}> = ({ result, nickOf, settings }) => (
  <section className="border border-stone-200 bg-white rounded-xl p-4 flex flex-col gap-3">
    <h3 className="font-semibold font-display text-stone-900">Hand {result.hand + 1} result</h3>
    <ul className="flex flex-col gap-1 text-sm text-stone-700">
      {result.pots.map((pot, i) => (
        <li key={i}>
          <strong className="text-stone-950">{pot.winners.map(nickOf).join(' & ')}</strong>{' '}
          {pot.winners.length > 1 ? 'split' : 'won'} {chips(pot.amount)}
          {result.pots.length > 1 && <span className="text-stone-500"> ({i === 0 ? 'main pot' : `side pot ${i}`})</span>}
          {pot.handName && <span className="text-stone-500"> with {pot.handName.toLowerCase()}</span>}
        </li>
      ))}
    </ul>
    {result.board.length > 0 && (
      <div className="flex gap-1">
        {result.board.map((card) => (
          <CardView key={cardKey(card)} card={toUiCard(card)} size="sm" cardBack={settings.cardBack} fourColor={settings.fourColorDeck} />
        ))}
      </div>
    )}
    {result.reveals.length > 0 && (
      <ul className="flex flex-col gap-2">
        {result.reveals.map((reveal) => (
          <li key={reveal.player} className="flex items-center gap-2">
            <div className="flex">
              {reveal.cards.map((card) => (
                <CardView key={cardKey(card)} card={toUiCard(card)} size="sm" className="-ml-3 first:ml-0" fourColor={settings.fourColorDeck} />
              ))}
            </div>
            <div className="flex flex-col min-w-0">
              <span className="text-sm font-semibold text-stone-900 truncate">{nickOf(reveal.player)}</span>
              <span className="text-xs text-stone-500">{reveal.handName}</span>
            </div>
          </li>
        ))}
      </ul>
    )}
  </section>
);
