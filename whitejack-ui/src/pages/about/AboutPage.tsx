import React from 'react';
import { CardFan, suitFromGlyph } from '@/components/CardFan';
import { GAME_DEFINITIONS } from '@/config/games';
import { feedbackHref, sourceUrl } from '@/config/site';
import { GameKey } from '@/types/game';

/** The URL hash that opens this page. Room codes never contain a slash, so the two cannot collide. */
export const ABOUT_HASH = '#/about';

const GAMES: GameKey[] = ['poker', 'hearts', 'gin_rummy', 'spades'];

const STEPS = [
  {
    title: 'Open a table',
    body: 'Choose a game and enter the name the table will see. The server opens a private room with a six-character code.',
  },
  {
    title: 'Send the link',
    body: 'Share the invite link in whatever chat your group already uses. Friends pick a name and take a seat — no account, no download.',
  },
  {
    title: 'Deal',
    body: 'When enough players are seated the host starts the game. Short a player? Add a bot to the empty seat and play anyway.',
  },
];

const PRINCIPLES = [
  ['No accounts', 'A name and a room code are all it takes. Your identity lives in a signed browser cookie, not a profile.'],
  ['No money', 'Poker chips are for keeping score. There is nothing to buy, deposit or cash out.'],
  ['No ads', 'Nothing between you and the cards — no banners, no pop-ups, no loot boxes.'],
  ['Fair by construction', 'The server holds the deck and enforces every rule. Other players’ cards never reach your browser until they are shown.'],
] as const;

const STACK = ['Java 17', 'Spring Boot', 'WebSocket', 'React 19', 'TypeScript', 'Tailwind CSS', 'Docker', 'Jev via OpenRouter'];

const INTERNALS = [
  {
    title: 'The server is the only referee',
    body: 'Each room is a single-threaded actor that owns the deck, checks every move against the rules and sends each player a view of the game. Browsers and bots only send intentions.',
    path: 'engine-core/src/main/java/com/whitejack/engine/RoomActor.java',
  },
  {
    title: 'Games are plug-ins',
    body: 'A game implements one contract — validate a move, reduce it to events, apply them, project a view per player — and is discovered at runtime. The engine compiles without any game on its classpath; each game ships as its own module.',
    path: 'engine-contract/src/main/java/com/whitejack/contract/GameDefinition.java',
  },
  {
    title: 'Hidden cards stay hidden',
    body: 'Every view is filtered per player before it leaves the server. The tests play hands end to end over the real socket and check that no player ever receives another player’s cards.',
    path: 'whitejack-server/src/test/java/com/whitejack/server/GatewayIntegrationTest.java',
  },
];

const inlineLink = 'text-stone-900 underline decoration-stone-300 underline-offset-2 hover:decoration-stone-900';

const sectionGrid = 'grid grid-cols-1 lg:grid-cols-12 gap-x-16 gap-y-8 py-14 sm:py-20 border-t border-stone-200';
const sectionHeading = 'font-display text-2xl font-semibold text-stone-900 leading-tight';

export const AboutPage: React.FC = () => {
  return (
    <article className="w-full max-w-[1200px] mx-auto px-4 sm:px-6 animate-rise">
      {/* Intro */}
      <header className="pt-6 pb-14 sm:pb-20 grid grid-cols-1 lg:grid-cols-12 gap-x-16 gap-y-6">
        <p className="lg:col-span-12 text-[13px] font-medium text-felt-700">About Whitejack</p>
        <h1 className="lg:col-span-8 font-display text-[2.5rem] sm:text-5xl lg:text-[3.5rem] font-semibold text-stone-900 leading-[1.05]">
          Built for the kitchen table, not the casino.
        </h1>
        <div className="lg:col-span-7 flex flex-col gap-4 text-base text-stone-600 leading-relaxed max-w-[62ch]">
          <p>
            Whitejack is a small, private place to play classic card games with people you already know. One
            person opens a table, everyone else joins from a link, and the game runs in the browser on any
            device.
          </p>
          <p>
            It started from a simple frustration: most online card sites want an account, a download, an ad
            impression or a deposit before you see a single card. This one only wants a name.
          </p>
        </div>
      </header>

      {/* How it works */}
      <section aria-labelledby="how-heading" className={sectionGrid}>
        <div className="lg:col-span-4">
          <h2 id="how-heading" className={`${sectionHeading} lg:sticky lg:top-24`}>
            How a game night works
          </h2>
        </div>
        <ol className="lg:col-span-8 flex flex-col divide-y divide-stone-200 border-t border-stone-200">
          {STEPS.map((step, i) => (
            <li key={step.title} className="grid grid-cols-[3rem_1fr] gap-4 py-6">
              <span className="font-mono-code text-sm text-stone-400 pt-0.5">{String(i + 1).padStart(2, '0')}</span>
              <div className="flex flex-col gap-1.5">
                <h3 className="font-display text-lg font-semibold text-stone-900">{step.title}</h3>
                <p className="text-[15px] text-stone-600 leading-relaxed max-w-[58ch]">{step.body}</p>
              </div>
            </li>
          ))}
        </ol>
      </section>

      {/* Games */}
      <section aria-labelledby="games-heading" className={sectionGrid}>
        <div className="lg:col-span-4 flex flex-col gap-3">
          <h2 id="games-heading" className={sectionHeading}>
            The games
          </h2>
          <p className="text-[15px] text-stone-600 leading-relaxed">
            Three games run online today, each with its full rules enforced by the server. More are on the way.
          </p>
        </div>
        <ul className="lg:col-span-8 grid grid-cols-1 sm:grid-cols-2 gap-3">
          {GAMES.map((key) => {
            const def = GAME_DEFINITIONS[key];
            return (
              <li key={key} className="bg-white border border-stone-200 rounded-xl p-5 flex flex-col gap-4">
                <div className="flex items-center justify-between gap-3">
                  <div className="rounded-lg bg-stone-100 w-16 h-16 flex items-center justify-center">
                    <div className="scale-75">
                      <CardFan suit={suitFromGlyph(def.suitGlyph)} size="sm" />
                    </div>
                  </div>
                  {def.comingSoon && <span className="text-xs text-stone-500">Coming soon</span>}
                </div>
                <div className="flex flex-col gap-1">
                  <h3 className="font-display text-lg font-semibold text-stone-900">{def.name}</h3>
                  <p className="text-xs text-stone-500">{def.playerCountLabel}</p>
                </div>
                <p className="text-[13px] text-stone-600 leading-relaxed">{def.description}</p>
              </li>
            );
          })}
        </ul>
      </section>

      {/* Bots */}
      <section aria-labelledby="bots-heading" className={sectionGrid}>
        <div className="lg:col-span-4 flex flex-col gap-3">
          <h2 id="bots-heading" className={sectionHeading}>
            How the AI bots think
          </h2>
          <p className="text-[15px] text-stone-600 leading-relaxed">
            Short a player? The host can seat an AI bot in any empty chair. It plays by the same rules, over
            the same connection, as everyone else.
          </p>
        </div>
        <div className="lg:col-span-8 flex flex-col gap-10">
          <BotPipeline />

          <div className="grid grid-cols-1 md:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)] gap-10">
            <div className="flex flex-col gap-4 text-[15px] text-stone-600 leading-relaxed max-w-[58ch]">
              <p>
                On its turn a bot lists every move the server says is legal and describes each one in plain
                words — “discard 7♦ (leaves deadwood 12)”, “raise to 120 (adds 80, pot becomes 300)”. It asks{' '}
                <a href="https://openrouter.ai/~typesafe/jev-latest" target="_blank" rel="noreferrer" className={inlineLink}>
                  Jev
                </a>
                , TypeSafe’s fast, low-cost decision model, to weigh them, and plays the one Jev rates highest.
              </p>
              <p>
                Because Jev can only choose from that list, a bot can never attempt an illegal move. If the model
                is slow or unreachable, the bot falls back to a built-in strategy, so the table never waits.
              </p>
              <p>
                After each hand, the side panel shows how every bot move was decided: the options it weighed,
                Jev’s probability for each and how long it took. It stays hidden while the hand is in play, since a
                bot’s options are its own cards.
              </p>
            </div>
            <dl className="flex flex-col divide-y divide-stone-200 border-y border-stone-200 text-[13px] self-start">
              <div className="py-3 flex flex-col gap-0.5">
                <dt className="text-stone-500">Moves it considers</dt>
                <dd className="text-stone-900 font-medium">Only the legal moves the server lists</dd>
              </div>
              <div className="py-3 flex flex-col gap-0.5">
                <dt className="text-stone-500">If the model is slow</dt>
                <dd className="text-stone-900 font-medium">Built-in strategy after 3 seconds</dd>
              </div>
              <div className="py-3 flex flex-col gap-0.5">
                <dt className="text-stone-500">Cost per move</dt>
                <dd className="text-stone-900 font-medium">About $0.00003 — roughly 3¢ per 1,000 moves</dd>
              </div>
              <div className="py-3 flex flex-col gap-0.5">
                <dt className="text-stone-500">Why Jev</dt>
                <dd className="text-stone-900 font-medium">Fast, cheap, and answers with a probability per move</dd>
              </div>
              <div className="py-3 flex flex-col gap-0.5">
                <dt className="text-stone-500">What it can see</dt>
                <dd className="text-stone-900 font-medium">Its own hand and the table, like you</dd>
              </div>
              <div className="py-3 flex flex-wrap gap-x-4 gap-y-1">
                <a href={sourceUrl('whitejack-bots/src/main/java/com/whitejack/bots/Bot.java')} target="_blank" rel="noreferrer" className={inlineLink}>
                  Bot.java
                </a>
                <a href={sourceUrl('whitejack-bots/src/main/java/com/whitejack/bots/JevAdvisor.java')} target="_blank" rel="noreferrer" className={inlineLink}>
                  JevAdvisor.java
                </a>
              </div>
            </dl>
          </div>
        </div>
      </section>

      {/* Principles */}
      <section aria-labelledby="principles-heading" className={sectionGrid}>
        <div className="lg:col-span-4">
          <h2 id="principles-heading" className={sectionHeading}>
            What stays out of the game
          </h2>
        </div>
        <dl className="lg:col-span-8 grid grid-cols-1 sm:grid-cols-2 gap-x-10 gap-y-8">
          {PRINCIPLES.map(([term, detail]) => (
            <div key={term} className="flex flex-col gap-1.5 border-t border-stone-900 pt-4">
              <dt className="font-display text-base font-semibold text-stone-900">{term}</dt>
              <dd className="text-[15px] text-stone-600 leading-relaxed">{detail}</dd>
            </div>
          ))}
        </dl>
      </section>

      {/* Under the hood */}
      <section aria-labelledby="hood-heading" className={sectionGrid}>
        <div className="lg:col-span-4 flex flex-col gap-3">
          <h2 id="hood-heading" className={sectionHeading}>
            Under the hood
          </h2>
          <p className="text-[15px] text-stone-600 leading-relaxed">
            An open-source side project. The parts worth reading are linked to the code.
          </p>
          <ul className="flex flex-wrap gap-1.5 pt-1" aria-label="Tech stack">
            {STACK.map((t) => (
              <li key={t} className="text-xs text-stone-700 bg-stone-100 rounded px-2 py-1">
                {t}
              </li>
            ))}
          </ul>
        </div>
        <ol className="lg:col-span-8 flex flex-col divide-y divide-stone-200 border-t border-stone-200">
          {INTERNALS.map((item) => (
            <li key={item.title} className="py-6 grid grid-cols-1 sm:grid-cols-[minmax(0,1fr)_auto] gap-x-8 gap-y-2">
              <div className="flex flex-col gap-1.5">
                <h3 className="font-display text-lg font-semibold text-stone-900">{item.title}</h3>
                <p className="text-[15px] text-stone-600 leading-relaxed max-w-[58ch]">{item.body}</p>
              </div>
              <a
                href={sourceUrl(item.path)}
                target="_blank"
                rel="noreferrer"
                className="self-start text-[13px] font-medium text-stone-700 hover:text-stone-900 flex items-center gap-1 whitespace-nowrap"
              >
                Read the code
                <span aria-hidden className="material-symbols-outlined text-[16px]">arrow_outward</span>
              </a>
            </li>
          ))}
        </ol>
      </section>

      {/* Call to action */}
      <section className="mt-4 rounded-xl bg-stone-900 text-stone-50 px-6 sm:px-10 py-10 flex flex-col sm:flex-row sm:items-center justify-between gap-6">
        <div className="flex flex-col gap-1.5">
          <h2 className="font-display text-2xl font-semibold">Ready to deal?</h2>
          <p className="text-[15px] text-stone-400">
            Open a table in a few seconds and send the link. Have an idea or found a bug?{' '}
            <a href={feedbackHref()} className="text-stone-100 underline underline-offset-2 hover:text-white">
              Email me
            </a>
            .
          </p>
        </div>
        <a
          href="/"
          className="h-11 px-5 rounded-md bg-stone-50 text-stone-900 text-sm font-medium flex items-center justify-center gap-1.5 hover:bg-white active:scale-[0.99] transition-[background-color,transform] shrink-0"
        >
          Choose a game
          <span aria-hidden className="material-symbols-outlined text-[18px]">arrow_forward</span>
        </a>
      </section>
    </article>
  );
};

const PIPELINE = [
  { title: 'The table', detail: 'The bot’s own hand and the public table, as the server sends it' },
  { title: 'Legal moves', detail: 'Each one described in plain words' },
  { title: 'Ask Jev', detail: 'A fast, low-cost choice question over exactly those moves' },
  { title: 'Probabilities', detail: 'One per move — the bot plays the best' },
];

/** The decision loop as four steps; the fallback branch sits under the model step. */
const BotPipeline: React.FC = () => (
  <figure className="flex flex-col gap-3">
    <ol className="grid grid-cols-1 sm:grid-cols-4 gap-2">
      {PIPELINE.map((step, i) => (
        <li key={step.title} className="relative bg-white border border-stone-200 rounded-lg p-3.5 flex flex-col gap-1">
          <span className="font-mono-code text-[11px] text-stone-400">{String(i + 1).padStart(2, '0')}</span>
          <span className={`text-sm font-semibold ${i === 2 ? 'text-felt-700' : 'text-stone-900'}`}>{step.title}</span>
          <span className="text-xs text-stone-500 leading-snug">{step.detail}</span>
          {i < PIPELINE.length - 1 && (
            <span
              aria-hidden
              className="material-symbols-outlined absolute text-[16px] text-stone-400 max-sm:left-1/2 max-sm:-bottom-[15px] max-sm:-translate-x-1/2 max-sm:rotate-90 sm:-right-[13px] sm:top-1/2 sm:-translate-y-1/2 bg-[#fafaf9] rounded-full z-10"
            >
              arrow_forward
            </span>
          )}
        </li>
      ))}
    </ol>
    <figcaption className="text-xs text-stone-500 flex items-start gap-1.5">
      <span aria-hidden className="material-symbols-outlined text-[15px] text-stone-400">subdirectory_arrow_right</span>
      No answer within 3 seconds, or no API key configured? The bot plays its built-in strategy for that game instead.
    </figcaption>
  </figure>
);
