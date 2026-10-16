import React, { useEffect, useState } from 'react';
import { BotThinking } from '@/components/BotThinking';
import { CardView } from '@/components/CardView';
import { useSettings } from '@/app/SettingsContext';
import { isBotNick } from '@/network/api';
import type { BoardProps } from '@/pages/games/live/boards';
import { TableSettings } from '@/types/game';
import { GinHandResult, GinSeat, GinView, WireCard } from '@/types/wire';
import { soundFx } from '@/utils/audio';
import { toUiCard } from '@/utils/cards';


const cardKey = (card: WireCard) => `${card.rank}-${card.suit}`;
const sameCard = (a: WireCard | null, b: WireCard | null) => !!a && !!b && a.rank === b.rank && a.suit === b.suit;
const contains = (cards: WireCard[], card: WireCard | null) => cards.some((c) => sameCard(c, card));

/**
 * Gin Rummy, rendered from the server's view. Every button is enabled from the view's legal-move
 * lists (`drawSources`, `discards`, `knockDiscards`, `ginDiscards`); this component decides no rule.
 */
export const GinRummyBoard: React.FC<BoardProps<GinView>> = ({ view, playerId, intent, aside }) => {
  const { settings } = useSettings();
  const [selected, setSelected] = useState<WireCard | null>(null);

  // A new turn phase or a new hand clears the pick.
  useEffect(() => setSelected(null), [view.phase, view.hand, view.onClock]);

  const me = view.seats.find((s) => s.id === playerId);
  const opponent = view.seats.find((s) => s.id !== playerId);
  const nickOf = (id: string | null) => (id === playerId ? 'You' : view.seats.find((s) => s.id === id)?.nick ?? '…');
  const myTurn = view.onClock === playerId;
  const drawing = myTurn && view.phase === 'DRAW';
  const discarding = myTurn && view.phase === 'DISCARD';
  const canDrawStock = view.drawSources.includes('STOCK');
  const canDrawDiscard = view.drawSources.includes('DISCARD');

  const draw = (source: 'stock' | 'discard') => {
    soundFx.playCardDeal();
    intent({ type: 'draw', source });
  };

  const discard = (knock: boolean) => {
    if (!selected) return;
    soundFx.playCardPlay();
    intent({ type: 'discard', card: selected, knock });
  };

  const handleCardClick = (card: WireCard) => {
    if (!discarding || !contains(view.discards, card)) return;
    soundFx.playClick();
    setSelected((prev) => (sameCard(prev, card) ? null : card));
  };

  const status = () => {
    if (view.phase === 'GAME_OVER') return 'Game over';
    if (drawing) return 'Your turn — draw from the stock or the discard pile';
    if (discarding) return selected ? 'Discard it, or knock if you can' : 'Pick a card to discard';
    return `Waiting on ${nickOf(view.onClock)}`;
  };

  // Overlapped within a meld like a held fan; the first card of each group sits flush.
  const myCard = (card: WireCard) => (
    <CardView
      key={cardKey(card)}
      card={toUiCard(card)}
      selected={sameCard(selected, card)}
      disabled={discarding && !contains(view.discards, card)}
      onClick={discarding && contains(view.discards, card) ? () => handleCardClick(card) : undefined}
      tooltip={sameCard(view.takenFromDiscard, card) ? 'Just taken from the pile — cannot be discarded this turn' : undefined}
      cardBack={settings.cardBack}
      fourColor={settings.fourColorDeck}
      className="-ml-11 sm:-ml-8 lg:-ml-9 xl:-ml-4 first:ml-0"
    />
  );

  const pileClass = (active: boolean) =>
    `rounded-md ${active ? 'ring-2 ring-felt-400 cursor-pointer hover:-translate-y-1 transition-transform' : 'cursor-default'}`;

  return (
    // Fills the viewport between navbar and footer on wide screens: table and hand on the left,
    // status and scores on the right.
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem] xl:grid-cols-[minmax(0,1fr)_22rem] items-start">
      <div className="flex flex-col gap-4 min-w-0 lg:min-h-[calc(100dvh-15rem)]">
        {/* Table: the opponent across, the stock and discard between you */}
        <section className="flex-1 rounded-xl bg-stone-900 p-3 sm:p-5 flex flex-col items-center justify-between gap-5">
          <SeatBadge seat={opponent} view={view} />

          <div className="flex items-end gap-8 sm:gap-12 rounded-xl bg-stone-800/60 border border-stone-700/70 px-6 sm:px-10 py-5">
            <div className="flex flex-col items-center gap-2">
              <button
                type="button"
                disabled={!canDrawStock}
                onClick={() => draw('stock')}
                className={pileClass(canDrawStock)}
                aria-label="Draw from the stock"
              >
                <CardView faceDown cardBack={settings.cardBack} />
              </button>
              <span className="text-xs text-stone-300">Stock · {view.stockCount}</span>
            </div>
            <div className="flex flex-col items-center gap-2">
              <button
                type="button"
                disabled={!canDrawDiscard}
                onClick={() => draw('discard')}
                className={pileClass(canDrawDiscard)}
                aria-label="Take the top discard"
              >
                {view.discardTop ? (
                  <CardView card={toUiCard(view.discardTop)} cardBack={settings.cardBack} fourColor={settings.fourColorDeck} />
                ) : (
                  <div className="w-16 h-[5.6rem] sm:w-18 sm:h-[6.3rem] md:w-20 md:h-28 rounded-md border-2 border-dashed border-stone-600" />
                )}
              </button>
              <span className="text-xs text-stone-300">Discard · {view.discardCount}</span>
            </div>
          </div>

          <SeatBadge seat={me} view={view} you />
        </section>

        {/* Your cards, grouped the way they would be laid down */}
        <section
          className={`rounded-xl border px-3 sm:px-5 pt-3 pb-4 flex flex-col gap-3 bg-stone-900 ${
            myTurn ? 'border-felt-400 ring-2 ring-felt-400/60' : 'border-stone-900'
          }`}
        >
          <div className="flex flex-wrap items-center justify-between gap-2 min-h-9">
            <span className="text-sm font-semibold text-white">
              Your hand
              {view.myMelds && (
                <span className="ml-2 text-xs font-normal text-stone-400">deadwood {view.myMelds.deadwoodPoints}</span>
              )}
            </span>
            {discarding && (
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => discard(false)}
                  disabled={!selected}
                  className="h-9 px-4 rounded-lg bg-stone-700 text-white text-sm font-medium hover:bg-stone-600 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                >
                  Discard
                </button>
                {contains(view.ginDiscards, selected) ? (
                  <button
                    type="button"
                    onClick={() => discard(true)}
                    className="h-9 px-4 rounded-lg bg-emerald-600 text-white text-sm font-medium hover:bg-emerald-500 cursor-pointer"
                  >
                    Gin!
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => discard(true)}
                    disabled={!contains(view.knockDiscards, selected)}
                    title={view.knockDiscards.length === 0 ? 'Knocking needs 10 or less deadwood after your discard' : undefined}
                    className="h-9 px-4 rounded-lg bg-felt-600 text-white text-sm font-medium hover:bg-felt-500 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                  >
                    Knock
                  </button>
                )}
              </div>
            )}
          </div>

          {/* Each meld is its own fan; the top padding leaves room for a picked card to lift. */}
          {view.myMelds ? (
            <div className="flex flex-wrap justify-center gap-x-3 gap-y-2 pt-3">
              {view.myMelds.melds.map((meld) => (
                <div key={meld.map(cardKey).join()} className="flex rounded-xl border border-emerald-400/40 bg-emerald-500/10 p-1.5">
                  {meld.map(myCard)}
                </div>
              ))}
              {view.myMelds.deadwood.length > 0 && (
                <div className="flex rounded-xl border border-dashed border-stone-600 p-1.5">{view.myMelds.deadwood.map(myCard)}</div>
              )}
            </div>
          ) : (
            <span className="text-sm text-stone-400 py-8 text-center">Watching.</span>
          )}
          <p className="text-xs text-stone-400">Green groups are melds. Cards in the dashed group count as deadwood.</p>
        </section>
      </div>

      {/* Sidebar: where the hand stands, the scores, then how the last hand ended */}
      <aside className="flex flex-col gap-4 lg:sticky lg:top-28">
        <section className="border border-stone-200 bg-white rounded-xl p-4 flex flex-col gap-3">
          <div className="flex items-center justify-between gap-2 text-sm">
            <span className="font-semibold font-display text-stone-900">Hand {view.hand + 1}</span>
            <span className="text-stone-600">{nickOf(view.dealer)} dealt</span>
          </div>
          <div className="flex flex-wrap gap-1.5">
            <span className="text-xs text-stone-600 bg-stone-100 border border-stone-200 px-2 py-0.5 rounded-full">
              First to 100
            </span>
            {view.myMelds && (
              <span className="text-xs font-semibold text-emerald-700 bg-emerald-50 border border-emerald-200 px-2 py-0.5 rounded-full">
                Deadwood {view.myMelds.deadwoodPoints}
              </span>
            )}
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
            {nickOf(view.winner)} {view.winner === playerId ? 'win' : 'wins'} with{' '}
            {view.seats.find((s) => s.id === view.winner)?.score} points
          </section>
        )}

        <section className="border border-stone-200 bg-white rounded-xl p-4 flex flex-col gap-2">
          <h3 className="font-semibold font-display text-stone-900">Scores</h3>
          {/* One row per player, so the sidebar never overflows however many hands are played. */}
          <ul className="flex flex-col divide-y divide-stone-100">
            {view.seats.map((s, j) => (
              <li key={s.id} className="py-2 flex items-center justify-between gap-3">
                <div className="flex flex-col min-w-0">
                  <span className={`text-sm font-semibold truncate ${s.id === playerId ? 'text-felt-700' : 'text-stone-900'}`}>
                    {s.nick}
                    {s.id === playerId && <span className="text-xs font-normal text-stone-500"> (you)</span>}
                  </span>
                  {view.history.length > 0 && (
                    <span className="text-xs text-stone-500 font-mono-code truncate">
                      {view.history.map((row) => row[j]).join(' · ')}
                    </span>
                  )}
                </div>
                <span className="text-lg font-bold font-mono-code text-stone-950">{s.score}</span>
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

const OUTCOME_LABEL = { KNOCK: 'knocked', GIN: 'went gin', UNDERCUT: 'knocked and was undercut', DEAD: '' } as const;

/** Both hands as they were laid down at the end of the previous hand. */
const LastHand: React.FC<{ result: GinHandResult; nickOf: (id: string | null) => string; settings: TableSettings }> = ({
  result,
  nickOf,
  settings,
}) => {
  // Small and overlapped, so a full hand fits the sidebar in a row or two.
  const small = (card: WireCard) => (
    <CardView
      key={cardKey(card)}
      card={toUiCard(card)}
      size="sm"
      cardBack={settings.cardBack}
      fourColor={settings.fourColorDeck}
      className="-ml-4 first:ml-0"
    />
  );
  return (
    <section className="border border-stone-200 bg-white rounded-xl p-4 flex flex-col gap-3">
      <h3 className="text-sm font-semibold font-display text-stone-900">
        Last hand:{' '}
        {result.outcome === 'DEAD'
          ? 'the stock ran out — no score'
          : `${nickOf(result.knocker)} ${OUTCOME_LABEL[result.outcome]}. ${nickOf(result.winner)} scored ${result.points}.`}
      </h3>
      {result.hands.map((hand) => (
        <div key={hand.player} className="flex flex-col gap-1.5">
          <span className="text-xs font-semibold text-stone-700">
            {nickOf(hand.player)} · deadwood {hand.deadwoodPoints}
          </span>
          <div className="flex flex-wrap items-center gap-1.5">
            {hand.melds.map((meld) => (
              <div key={meld.map(cardKey).join()} className="flex rounded-lg border border-emerald-200 bg-emerald-50/60 p-1">
                {meld.map(small)}
              </div>
            ))}
            {hand.laidOff.length > 0 && (
              <div className="flex items-center rounded-lg border border-felt-200 bg-felt-50/60 p-1">
                <span className="text-[10px] font-semibold text-felt-700 px-1">laid off</span>
                <div className="flex">{hand.laidOff.map(small)}</div>
              </div>
            )}
            {hand.deadwood.length > 0 && (
              <div className="flex rounded-lg border border-dashed border-stone-300 p-1">{hand.deadwood.map(small)}</div>
            )}
          </div>
        </div>
      ))}
    </section>
  );
};

const SeatBadge: React.FC<{ seat: GinSeat | undefined; view: GinView; you?: boolean }> = ({ seat, view, you = false }) => {
  if (!seat) return null;
  const onClock = seat.id === view.onClock && view.phase !== 'GAME_OVER';
  return (
    <div
      className={`rounded-xl px-2.5 sm:px-3 py-2 text-white border min-w-0 ${
        onClock ? 'border-felt-400 bg-felt-600/30 ring-2 ring-felt-400' : 'border-stone-700 bg-stone-800'
      }`}
    >
      <div className="flex items-center gap-2 text-sm font-semibold truncate">
        {seat.nick}
        {you && <span className="text-xs font-normal text-stone-300">(you)</span>}
        {seat.id === view.dealer && <span className="text-xs font-normal text-amber-300">dealer</span>}
        {onClock && !you && isBotNick(seat.nick) && <BotThinking />}
      </div>
      <div className="text-[11px] sm:text-xs text-stone-300">
        {seat.cardCount} cards · {seat.score} pts
      </div>
    </div>
  );
};
