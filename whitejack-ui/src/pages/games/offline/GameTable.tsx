import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Card,
  GameKey,
  GameLogEntry,
  PlayedCard,
  Player,
  RoomRules,
} from '@/types/game';
import {
  calculateHeartsTrickPoints,
  chooseBotPlayHearts,
  chooseBotPlaySpades,
  createEuchreDeck,
  createFullDeck,
  determineTrickWinner,
  isJackOfDiamonds,
  isLegalPlayHearts,
  isLegalPlaySpades,
  isQueenOfSpades,
  isTwoOfClubs,
  shuffleDeck,
  sortCards,
  SUIT_SYMBOLS,
} from '@/utils/deck';
import { useSettings } from '@/app/SettingsContext';
import { soundFx } from '@/utils/audio';
import { CardView } from '@/components/CardView';
import { GAME_DEFINITIONS } from '@/config/games';

interface GameTableProps {
  gameKey: GameKey;
  roomCode: string;
  playerName: string;
  rules: RoomRules;
  onLeaveTable: () => void;
  onOpenSettings: () => void;
}

export const GameTable: React.FC<GameTableProps> = ({
  gameKey,
  roomCode,
  playerName,
  rules,
  onLeaveTable,
  onOpenSettings,
}) => {
  const { settings } = useSettings();
  const gameDef = GAME_DEFINITIONS[gameKey];

  // Game state
  const [roundNumber, setRoundNumber] = useState<number>(1);
  const [phase, setPhase] = useState<'passing' | 'bidding' | 'playing' | 'round_end' | 'game_over'>(
    gameKey === 'hearts' && rules.rule2 ? 'passing' : gameKey === 'spades' || gameKey === 'oh_hell' ? 'bidding' : 'playing'
  );

  // Passing state (Hearts)
  const [selectedPassCards, setSelectedPassCards] = useState<string[]>([]);
  const [passDirection, setPassDirection] = useState<'left' | 'right' | 'across' | 'hold'>('left');

  // Players: 0 = User (South), 1 = West (Marcus), 2 = North (Sarah), 3 = East (Elena)
  const [players, setPlayers] = useState<Player[]>([
    {
      id: 'p0',
      name: playerName || 'Julian (You)',
      avatar: 'J',
      isHost: true,
      isUser: true,
      cards: [],
      tricksWon: 0,
      roundScore: 0,
      totalScore: 0,
    },
    {
      id: 'p1',
      name: 'Marcus',
      avatar: 'M',
      isHost: false,
      isUser: false,
      cards: [],
      tricksWon: 0,
      roundScore: 0,
      totalScore: 0,
    },
    {
      id: 'p2',
      name: 'Sarah',
      avatar: 'S',
      isHost: false,
      isUser: false,
      cards: [],
      tricksWon: 0,
      roundScore: 0,
      totalScore: 0,
    },
    {
      id: 'p3',
      name: 'Elena',
      avatar: 'E',
      isHost: false,
      isUser: false,
      cards: [],
      tricksWon: 0,
      roundScore: 0,
      totalScore: 0,
    },
  ]);

  const [activePlayerIndex, setActivePlayerIndex] = useState<number>(0);
  const [currentTrick, setCurrentTrick] = useState<PlayedCard[]>([]);
  const [trickWinnerNotification, setTrickWinnerNotification] = useState<{
    winnerName: string;
    points: number;
    winningCard: Card;
  } | null>(null);

  // Trick state
  const [isFirstTrick, setIsFirstTrick] = useState<boolean>(true);
  const [brokenSuit, setBrokenSuit] = useState<boolean>(false); // Hearts or Spades broken

  // Bidding
  const [userBidSelection, setUserBidSelection] = useState<number>(2);

  // Game Log
  const [gameLogs, setGameLogs] = useState<GameLogEntry[]>([]);
  const [isLogOpen, setIsLogOpen] = useState<boolean>(false);
  const [copiedLink, setCopiedLink] = useState<boolean>(false);
  const [reactionFloat, setReactionFloat] = useState<{ id: number; text: string; from: string } | null>(
    null
  );

  // Sandbox mode state
  const [sandboxDeck, setSandboxDeck] = useState<Card[]>([]);
  const [sandboxChips, setSandboxChips] = useState<Record<string, number>>({
    p0: 100,
    p1: 100,
    p2: 100,
    p3: 100,
  });

  const addLog = useCallback((text: string, type: GameLogEntry['type'] = 'play') => {
    const time = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    setGameLogs((prev) => [{ id: Math.random().toString(), text, type, timestamp: time }, ...prev.slice(0, 49)]);
  }, []);

  // Read through a ref so changing the sort order re-sorts the hand (below) instead of re-dealing it.
  const sortByRef = useRef(settings.sortBy);
  sortByRef.current = settings.sortBy;

  // Initialize a fresh deal
  const startNewDeal = useCallback(() => {
    const sortBy = sortByRef.current;
    let rawDeck = gameKey === 'euchre' ? createEuchreDeck() : createFullDeck();
    let deck = shuffleDeck(rawDeck);

    soundFx.playCardDeal();

    let cardsPerPlayer = 13;
    if (gameKey === 'euchre') cardsPerPlayer = 5;
    if (gameKey === 'custom') cardsPerPlayer = 7;

    const p0Cards = sortCards(deck.slice(0, cardsPerPlayer), sortBy);
    const p1Cards = sortCards(deck.slice(cardsPerPlayer, cardsPerPlayer * 2), sortBy);
    const p2Cards = sortCards(deck.slice(cardsPerPlayer * 2, cardsPerPlayer * 3), sortBy);
    const p3Cards = sortCards(deck.slice(cardsPerPlayer * 3, cardsPerPlayer * 4), sortBy);

    const remaining = deck.slice(cardsPerPlayer * 4);
    setSandboxDeck(remaining);

    setPlayers((prev) => [
      { ...prev[0], cards: p0Cards, tricksWon: 0, roundScore: 0, currentBid: undefined },
      { ...prev[1], cards: p1Cards, tricksWon: 0, roundScore: 0, currentBid: undefined },
      { ...prev[2], cards: p2Cards, tricksWon: 0, roundScore: 0, currentBid: undefined },
      { ...prev[3], cards: p3Cards, tricksWon: 0, roundScore: 0, currentBid: undefined },
    ]);

    setCurrentTrick([]);
    setTrickWinnerNotification(null);
    setIsFirstTrick(true);
    setBrokenSuit(false);
    setSelectedPassCards([]);

    // Determine initial phase
    if (gameKey === 'hearts') {
      const directions: ('left' | 'right' | 'across' | 'hold')[] = ['left', 'right', 'across', 'hold'];
      const dir = directions[(roundNumber - 1) % 4];
      setPassDirection(dir);
      if (rules.rule2 && dir !== 'hold') {
        setPhase('passing');
        addLog(`Round ${roundNumber} started. Passing phase: pass 3 cards ${dir}.`, 'system');
      } else {
        setPhase('playing');
        addLog(`Round ${roundNumber} started (Hold hand - no pass).`, 'system');
      }
    } else if (gameKey === 'spades' || gameKey === 'oh_hell') {
      setPhase('bidding');
      addLog(`Round ${roundNumber} started. Place bids for the contract.`, 'system');
    } else {
      setPhase('playing');
      addLog(`Round ${roundNumber} started. Deal complete.`, 'system');
    }

    // Determine who leads first trick
    if (gameKey === 'hearts') {
      // Player with 2 of Clubs leads
      let starter = 0;
      [p0Cards, p1Cards, p2Cards, p3Cards].forEach((h, idx) => {
        if (h.some(isTwoOfClubs)) starter = idx;
      });
      setActivePlayerIndex(starter);
    } else {
      setActivePlayerIndex(0);
    }
  }, [gameKey, roundNumber, rules.rule2, addLog]);

  // Deal on mount and whenever the round number moves on: startNewDeal changes with it.
  useEffect(() => {
    startNewDeal();
  }, [startNewDeal]);

  useEffect(() => {
    setPlayers((prev) => prev.map((p) => ({ ...p, cards: sortCards(p.cards, settings.sortBy) })));
  }, [settings.sortBy]);

  // Passing phase handler (Hearts)
  const handlePassCardsConfirm = () => {
    if (selectedPassCards.length !== 3) return;
    soundFx.playCardDeal();

    // Determine pass offsets
    // left: 0->1, 1->2, 2->3, 3->0
    // right: 0->3, 3->2, 2->1, 1->0
    // across: 0->2, 1->3, 2->0, 3->1
    let offset = 1;
    if (passDirection === 'right') offset = 3;
    if (passDirection === 'across') offset = 2;

    const userPassed = players[0].cards.filter((c) => selectedPassCards.includes(c.id));
    const userRetained = players[0].cards.filter((c) => !selectedPassCards.includes(c.id));

    // Choose 3 cards for each bot to pass
    const botPasses: Card[][] = [];
    for (let i = 1; i <= 3; i++) {
      // Bots pass their highest cards (Ace, King, Queen of Spades, high hearts)
      const sorted = [...players[i].cards].sort((a, b) => b.value - a.value);
      botPasses[i] = sorted.slice(0, 3);
    }

    const allPassed: Card[][] = [userPassed, botPasses[1], botPasses[2], botPasses[3]];

    const newPlayers = players.map((p, idx) => {
      // Who gave to idx?
      const giverIdx = (idx - offset + 4) % 4;
      const received = allPassed[giverIdx];
      const kept = p.cards.filter((c) => !allPassed[idx].some((passC) => passC.id === c.id));
      const newHand = sortCards([...kept, ...received], settings.sortBy);
      return { ...p, cards: newHand };
    });

    setPlayers(newPlayers);
    setSelectedPassCards([]);
    setPhase('playing');

    // Find who has 2 of clubs after pass
    let starter = 0;
    newPlayers.forEach((p, idx) => {
      if (p.cards.some(isTwoOfClubs)) starter = idx;
    });
    setActivePlayerIndex(starter);

    addLog(`Passing completed. ${newPlayers[starter].name} has the 2♣ and leads the first trick.`, 'system');
  };

  // Submit Bid (Spades / Oh Hell)
  const handleSubmitBid = () => {
    soundFx.playClick();

    // Assign bids to bots
    const updated = players.map((p, idx) => {
      if (idx === 0) {
        return { ...p, currentBid: userBidSelection };
      }
      // Bot bidding heuristic: count Aces, Kings, high trumps
      let est = 0;
      p.cards.forEach((c) => {
        if (c.value >= 13) est++;
        if (gameKey === 'spades' && c.suit === 'spades' && c.value >= 10) est++;
      });
      const finalBid = Math.max(1, Math.min(6, est));
      return { ...p, currentBid: finalBid };
    });

    setPlayers(updated);
    setPhase('playing');
    setActivePlayerIndex(0);
    addLog(`Bids locked in: You (${userBidSelection}), Marcus (${updated[1].currentBid}), Sarah (${updated[2].currentBid}), Elena (${updated[3].currentBid})`, 'system');
  };

  // Check if a card is legally playable for user
  const isCardPlayableForUser = (card: Card): { legal: boolean; reason?: string } => {
    if (phase !== 'playing') return { legal: false, reason: 'Not in play phase' };
    if (activePlayerIndex !== 0) return { legal: false, reason: "Wait for your turn" };

    const userHand = players[0].cards;
    if (gameKey === 'hearts') {
      return isLegalPlayHearts(card, userHand, currentTrick, isFirstTrick, brokenSuit);
    }
    if (gameKey === 'spades') {
      return isLegalPlaySpades(card, userHand, currentTrick, brokenSuit);
    }
    // Default trick rule (must follow lead suit if possible)
    if (currentTrick.length > 0) {
      const lead = currentTrick[0].card.suit;
      const hasLead = userHand.some((c) => c.suit === lead);
      if (hasLead && card.suit !== lead) {
        return { legal: false, reason: `Must follow suit (${lead.toUpperCase()})` };
      }
    }
    return { legal: true };
  };

  // Play a card
  const executePlayCard = (playerIdx: number, card: Card) => {
    soundFx.playCardPlay();

    const currPlayer = players[playerIdx];
    const newHand = currPlayer.cards.filter((c) => c.id !== card.id);

    // Check if broken suit
    if (!brokenSuit) {
      if (gameKey === 'hearts' && card.suit === 'hearts') {
        setBrokenSuit(true);
        addLog(`Hearts are broken.`, 'system');
      }
      if (gameKey === 'spades' && card.suit === 'spades') {
        setBrokenSuit(true);
        addLog(`Spades are broken.`, 'system');
      }
    }

    const playedEntry: PlayedCard = {
      card,
      playerId: currPlayer.id,
      playerName: currPlayer.name,
    };

    const newTrick = [...currentTrick, playedEntry];
    setCurrentTrick(newTrick);

    setPlayers((prev) =>
      prev.map((p, i) => (i === playerIdx ? { ...p, cards: newHand } : p))
    );

    addLog(`${currPlayer.name} played ${card.rank}${SUIT_SYMBOLS[card.suit]}`);

    // If trick is full (4 cards played)
    if (newTrick.length === 4) {
      // Determine winner
      const trump = gameKey === 'spades' ? 'spades' : null;
      const { winnerPlayerId, winningCard } = determineTrickWinner(newTrick, trump);
      const winnerIndex = players.findIndex((p) => p.id === winnerPlayerId);
      const winnerName = players[winnerIndex].name;

      const heartsPts = calculateHeartsTrickPoints(newTrick, rules.rule1);

      setTimeout(() => {
        soundFx.playTrickWon();
        setTrickWinnerNotification({
          winnerName,
          points: heartsPts,
          winningCard,
        });

        // Award trick to winner
        setPlayers((prev) =>
          prev.map((p, i) => {
            if (i === winnerIndex) {
              return {
                ...p,
                tricksWon: p.tricksWon + 1,
                roundScore: p.roundScore + (gameKey === 'hearts' ? heartsPts : 0),
              };
            }
            return p;
          })
        );

        addLog(`★ ${winnerName} won the trick with ${winningCard.rank}${SUIT_SYMBOLS[winningCard.suit]}!`, 'trick');

        // Clear trick after display
        setTimeout(() => {
          setCurrentTrick([]);
          setTrickWinnerNotification(null);
          setIsFirstTrick(false);

          // Check if hand/round is over (no cards left in hand)
          if (newHand.length === 0) {
            handleRoundEnd();
          } else {
            setActivePlayerIndex(winnerIndex);
          }
        }, 1200);
      }, 700);
    } else {
      // Next player's turn
      setActivePlayerIndex((playerIdx + 1) % 4);
    }
  };

  // Handle human click on a card
  const handleCardClick = (card: Card) => {
    if (phase === 'passing') {
      soundFx.playClick();
      setSelectedPassCards((prev) => {
        if (prev.includes(card.id)) {
          return prev.filter((id) => id !== card.id);
        }
        if (prev.length < 3) {
          return [...prev, card.id];
        }
        return prev;
      });
      return;
    }

    if (phase === 'playing' && activePlayerIndex === 0) {
      const check = isCardPlayableForUser(card);
      if (!check.legal) {
        soundFx.playClick();
        return;
      }
      executePlayCard(0, card);
    }
  };

  // Bot Turn Automation Loop
  useEffect(() => {
    if (phase !== 'playing' || activePlayerIndex === 0 || currentTrick.length >= 4) {
      return;
    }

    const botTimer = setTimeout(() => {
      const bot = players[activePlayerIndex];
      if (!bot || bot.cards.length === 0) return;

      let chosenCard: Card;
      if (gameKey === 'hearts') {
        chosenCard = chooseBotPlayHearts(bot.cards, currentTrick, isFirstTrick, brokenSuit);
      } else if (gameKey === 'spades') {
        chosenCard = chooseBotPlaySpades(bot.cards, currentTrick, brokenSuit);
      } else {
        // Fallback follow lead
        const lead = currentTrick.length > 0 ? currentTrick[0].card.suit : null;
        const matching = lead ? bot.cards.filter((c) => c.suit === lead) : [];
        chosenCard = matching.length > 0 ? matching[0] : bot.cards[0];
      }

      executePlayCard(activePlayerIndex, chosenCard);
    }, 650);

    return () => clearTimeout(botTimer);
  }, [activePlayerIndex, phase, currentTrick, isFirstTrick, brokenSuit, gameKey, players]);

  // Round End Resolution
  const handleRoundEnd = () => {
    soundFx.playWinFanfare();
    setPhase('round_end');

    setPlayers((prev) => {
      // Check for Shoot the Moon in Hearts
      if (gameKey === 'hearts') {
        const moonShooter = prev.find((p) => p.roundScore >= 26);
        if (moonShooter) {
          addLog(`${moonShooter.name} shot the moon — +26 to every opponent.`, 'score');
          return prev.map((p) => {
            const added = p.id === moonShooter.id ? 0 : 26;
            return {
              ...p,
              totalScore: p.totalScore + added,
            };
          });
        }
      }

      // Standard score tally
      return prev.map((p) => {
        let added = p.roundScore;
        if (gameKey === 'spades') {
          const bid = typeof p.currentBid === 'number' ? p.currentBid : 0;
          if (p.tricksWon >= bid) {
            added = bid * 10 + (p.tricksWon - bid);
          } else {
            added = -(bid * 10);
          }
        }
        return {
          ...p,
          totalScore: p.totalScore + added,
        };
      });
    });
  };

  // Next Round / Rematch
  const handleNextRound = () => {
    setRoundNumber((r) => r + 1); // the deal effect deals the new round once
  };

  // Send quick reaction
  const handleSendReaction = (emoji: string) => {
    soundFx.playClick();
    const id = Date.now();
    setReactionFloat({ id, text: emoji, from: players[0].name });
    setTimeout(() => setReactionFloat(null), 2500);

    // Occasional bot counter-reaction
    setTimeout(() => {
      const botEmojis = ['👏', '🔥', '👀', '🃏', '😅'];
      const botName = players[Math.floor(Math.random() * 3) + 1].name;
      const pick = botEmojis[Math.floor(Math.random() * botEmojis.length)];
      setReactionFloat({ id: Date.now(), text: pick, from: botName });
      setTimeout(() => setReactionFloat(null), 2500);
    }, 1500);
  };

  // Sandbox freeform deal
  const handleSandboxDeal = (count: number) => {
    soundFx.playCardDeal();
    if (sandboxDeck.length < count * 4) {
      const fresh = shuffleDeck(createFullDeck());
      setSandboxDeck(fresh);
    }
    const current = [...sandboxDeck];
    const newPlayers = players.map((p) => {
      const dealt = current.splice(0, count);
      return { ...p, cards: sortCards([...p.cards, ...dealt], settings.sortBy) };
    });
    setPlayers(newPlayers);
    setSandboxDeck(current);
    addLog(`Dealt ${count} cards to all players from the sandbox deck.`, 'system');
  };

  const handleSandboxReset = () => {
    soundFx.playClick();
    const fresh = shuffleDeck(createFullDeck());
    setSandboxDeck(fresh);
    setPlayers((prev) => prev.map((p) => ({ ...p, cards: [], tricksWon: 0 })));
    setCurrentTrick([]);
    addLog(`Sandbox table reset. 52 fresh cards ready in the shoe.`, 'system');
  };

  const handleSandboxChipChange = (playerId: string, delta: number) => {
    soundFx.playClick();
    setSandboxChips((prev) => ({
      ...prev,
      [playerId]: Math.max(0, (prev[playerId] || 0) + delta),
    }));
  };

  // Copy table invite link
  const handleCopyInviteLink = () => {
    soundFx.playClick();
    if (navigator?.clipboard) {
      navigator.clipboard.writeText(`${window.location.origin}/#${roomCode}`);
    }
    setCopiedLink(true);
    setTimeout(() => setCopiedLink(false), 2000);
  };

  // Table felt style
  const tableBackground = useMemo(() => {
    switch (settings.tableTheme) {
      case 'emerald':
        return 'bg-emerald-950 border-emerald-900 shadow-inner';
      case 'navy':
        return 'bg-stone-950 border-stone-800 shadow-inner';
      case 'studio':
        return 'bg-stone-100 border-stone-200 shadow-inner';
      case 'slate':
      default:
        return 'bg-stone-900 border-stone-800 shadow-inner';
    }
  }, [settings.tableTheme]);

  // Center prompt text
  const currentInstruction = useMemo(() => {
    if (phase === 'passing') {
      return `Choose 3 cards to pass ${passDirection.toUpperCase()} (${selectedPassCards.length}/3 selected)`;
    }
    if (phase === 'bidding') {
      return `Bidding Phase: Select your trick contract`;
    }
    if (activePlayerIndex === 0) {
      if (currentTrick.length === 0) {
        return isFirstTrick ? 'Lead the 2 of Clubs ♣ to start the hand' : 'Your turn to lead a trick';
      }
      return `Your turn · Follow ${currentTrick[0].card.suit.toUpperCase()}`;
    }
    return `${players[activePlayerIndex].name}'s turn...`;
  }, [phase, passDirection, selectedPassCards.length, activePlayerIndex, currentTrick, isFirstTrick, players]);

  return (
    <div className="w-full max-w-[1560px] mx-auto px-2 sm:px-6 flex flex-col gap-4">
      {/* Top Table Control Bar */}
      <div className="bg-white border border-stone-200 rounded-xl px-4 py-3 flex flex-wrap items-center justify-between gap-3 shadow-sm">
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <span className="text-xs font-semibold text-stone-500 uppercase tracking-wider">Room:</span>
            <span className="font-mono-code font-bold text-sm bg-felt-50 text-felt-700 border border-felt-200 px-2 py-0.5 rounded flex items-center gap-1.5">
              <span>{roomCode}</span>
              <button
                onClick={handleCopyInviteLink}
                className="hover:text-felt-900 text-xs cursor-pointer"
                title="Copy Room Link"
              >
                {copiedLink ? '✓' : '⧉'}
              </button>
            </span>
          </div>

          <div className="h-4 w-px bg-stone-200 hidden sm:block"></div>

          <div className="flex items-center gap-1.5">
            <span className="text-xs font-bold text-stone-950 font-display">{gameDef.name}</span>
            <span className="text-[10px] text-felt-700 bg-felt-100 px-2 py-0.5 rounded-full font-semibold">
              Round {roundNumber}
            </span>
          </div>
        </div>

        {/* Center / Right controls */}
        <div className="flex items-center gap-2">
          {/* Reaction Tray */}
          <div className="hidden sm:flex items-center gap-1 bg-stone-50 border border-stone-200 rounded-lg p-1">
            {['👏', '🔥', '🃏', '🎯', '😅'].map((emoji) => (
              <button
                key={emoji}
                onClick={() => handleSendReaction(emoji)}
                className="w-7 h-7 flex items-center justify-center rounded hover:bg-white hover:shadow-xs transition-[transform,background-color,border-color,box-shadow,opacity] text-sm cursor-pointer"
                title={`Send ${emoji}`}
              >
                {emoji}
              </button>
            ))}
          </div>

          {/* Game Log Drawer button */}
          <button
            onClick={() => setIsLogOpen(!isLogOpen)}
            className={`text-xs font-semibold px-2.5 py-1.5 rounded-lg border transition-colors flex items-center gap-1.5 cursor-pointer ${
              isLogOpen
                ? 'bg-felt-50 text-felt-700 border-felt-200'
                : 'bg-stone-50 text-stone-700 border-stone-200 hover:bg-stone-100'
            }`}
          >
            <span className="material-symbols-outlined text-base">format_list_bulleted</span>
            <span className="hidden md:inline">Log ({gameLogs.length})</span>
          </button>

          <button
            onClick={onOpenSettings}
            className="p-1.5 rounded-lg bg-stone-50 border border-stone-200 text-stone-700 hover:bg-stone-100 cursor-pointer"
            title="Table Felt & Card Preferences"
          >
            <span className="material-symbols-outlined text-base">palette</span>
          </button>

          <button
            onClick={onLeaveTable}
            className="text-xs font-semibold text-stone-600 hover:text-red-600 px-2.5 py-1.5 rounded-lg border border-stone-200 hover:border-red-200 hover:bg-red-50 transition-colors flex items-center gap-1 cursor-pointer"
          >
            <span className="material-symbols-outlined text-base">logout</span>
            <span className="hidden sm:inline">Leave Table</span>
          </button>
        </div>
      </div>

      {/* Floating Reaction Notification */}
      {reactionFloat && (
        <div className="fixed top-24 left-1/2 -translate-x-1/2 z-50 animate-rise bg-white border border-stone-200 shadow-raised rounded-full px-4 py-1.5 flex items-center gap-2">
          <span className="text-xl">{reactionFloat.text}</span>
          <span className="text-xs font-bold text-stone-700">{reactionFloat.from}</span>
        </div>
      )}

      {/* MAIN TABLE FELT CANVAS */}
      <div
        className={`w-full rounded-xl border-4 p-4 sm:p-6 transition-colors duration-200 relative overflow-hidden flex flex-col justify-between min-h-[620px] lg:min-h-[700px] ${tableBackground}`}
      >
        {/* Subtle subtle felt watermark */}
        <div className="absolute inset-0 pointer-events-none opacity-5 flex items-center justify-center">
          <div className="text-[120px] font-bold tracking-widest text-white uppercase select-none">
            ♠ ♥ ♦ ♣
          </div>
        </div>

        {/* TOP SEAT: North (Sarah) */}
        <div className="flex flex-col items-center gap-1 relative z-10">
          <div
            className={`flex items-center gap-3 px-3 py-1.5 rounded-full border transition-[transform,background-color,border-color,box-shadow,opacity] ${
              activePlayerIndex === 2
                ? 'bg-felt-600 text-white border-felt-400 ring-4 ring-felt-500/30'
                : 'bg-stone-900/80 backdrop-blur-md text-white border-stone-700'
            }`}
          >
            <div className="w-6 h-6 rounded-full bg-stone-700 flex items-center justify-center text-xs font-bold">
              {players[2].avatar}
            </div>
            <span className="text-xs font-bold">{players[2].name}</span>
            <span className="text-[11px] opacity-80 border-l border-white/20 pl-2">
              Cards: {players[2].cards.length}
            </span>
            {players[2].currentBid !== undefined && (
              <span className="text-[11px] bg-white/20 px-1.5 py-0.5 rounded font-mono-code">
                Bid: {players[2].currentBid} | Won: {players[2].tricksWon}
              </span>
            )}
            {gameKey === 'hearts' && (
              <span className="text-[11px] text-red-300 font-mono-code">
                Pts: {players[2].roundScore}
              </span>
            )}
          </div>

          {/* North Cards (Face Down) */}
          <div className="flex -space-x-6 sm:-space-x-8 pt-1">
            {players[2].cards.map((_, i) => (
              <CardView
                key={i}
                faceDown
                size="sm"
                cardBack={settings.cardBack}
                className="transform hover:-translate-y-1 transition-transform"
              />
            ))}
          </div>
        </div>

        {/* MIDDLE ROW: West Player, Center Trick Arena, East Player */}
        <div className="grid grid-cols-12 items-center gap-2 sm:gap-4 my-2 relative z-10">
          {/* West Player (Marcus) */}
          <div className="col-span-3 sm:col-span-3 flex flex-col items-start gap-1">
            <div
              className={`flex items-center gap-2 px-2.5 py-1.5 rounded-xl border transition-[transform,background-color,border-color,box-shadow,opacity] max-w-full ${
                activePlayerIndex === 1
                  ? 'bg-felt-600 text-white border-felt-400 ring-4 ring-felt-500/30'
                  : 'bg-stone-900/80 backdrop-blur-md text-white border-stone-700'
              }`}
            >
              <div className="w-6 h-6 rounded-full bg-stone-700 flex items-center justify-center text-xs font-bold shrink-0">
                {players[1].avatar}
              </div>
              <div className="flex flex-col truncate">
                <span className="text-xs font-bold truncate">{players[1].name}</span>
                <span className="text-[10px] opacity-80">
                  {players[1].cards.length} cards · {gameKey === 'hearts' ? `${players[1].roundScore} pts` : `Won: ${players[1].tricksWon}`}
                </span>
              </div>
            </div>

            {/* West Cards (Stacked column) */}
            <div className="flex flex-col -space-y-10 pt-1">
              {players[1].cards.slice(0, 7).map((_, i) => (
                <CardView
                  key={i}
                  faceDown
                  size="sm"
                  cardBack={settings.cardBack}
                  className="rotate-90 origin-top-left"
                />
              ))}
            </div>
          </div>

          {/* CENTER TRICK ARENA */}
          <div className="col-span-6 sm:col-span-6 flex flex-col items-center justify-center min-h-[220px] sm:min-h-[250px] relative">
            {/* Center Status / Instructions Pill */}
            <div className="mb-3 px-3 py-1 bg-black/40 backdrop-blur-md border border-white/10 rounded-full text-center">
              <span className="text-xs font-medium text-white tracking-wide">
                {currentInstruction}
              </span>
            </div>

            {/* Trick Card Drop Zone (Compass Placement) */}
            <div className="w-48 h-48 sm:w-56 sm:h-56 rounded-full border border-white/10 bg-white/5 flex items-center justify-center relative">
              {/* Compass points labels */}
              <span className="absolute top-1 text-[9px] font-bold text-white/30 uppercase">North</span>
              <span className="absolute bottom-1 text-[9px] font-bold text-white/30 uppercase">You</span>
              <span className="absolute left-1 text-[9px] font-bold text-white/30 uppercase">West</span>
              <span className="absolute right-1 text-[9px] font-bold text-white/30 uppercase">East</span>

              {/* Cards played in this trick */}
              {currentTrick.map((played) => {
                // Determine placement based on player id
                const isNorth = played.playerId === 'p2';
                const isSouth = played.playerId === 'p0';
                const isWest = played.playerId === 'p1';
                const isEast = played.playerId === 'p3';

                let posClass = 'translate-x-0 translate-y-0';
                if (isNorth) posClass = '-translate-y-8';
                if (isSouth) posClass = 'translate-y-8';
                if (isWest) posClass = '-translate-x-8';
                if (isEast) posClass = 'translate-x-8';

                return (
                  <div
                    key={played.card.id}
                    className={`absolute transition-[transform,background-color,border-color,box-shadow,opacity] duration-200 transform ${posClass} z-20`}
                  >
                    <CardView
                      card={played.card}
                      size="md"
                      fourColor={settings.fourColorDeck}
                      className="shadow-raised"
                    />
                    <span className="absolute -bottom-4 left-1/2 -translate-x-1/2 text-[9px] text-white/90 bg-black/60 px-1 rounded whitespace-nowrap">
                      {played.playerName}
                    </span>
                  </div>
                );
              })}

              {/* Trick Winner Notification Badge */}
              {trickWinnerNotification && (
                <div className="absolute inset-0 flex items-center justify-center z-30 animate-scale-up">
                  <div className="bg-stone-900/90 border border-felt-500/50 shadow-raised rounded-xl px-4 py-2 text-center text-white backdrop-blur-md">
                    <span className="text-xs font-bold text-felt-400 block">Trick won</span>
                    <span className="text-sm font-display font-bold">{trickWinnerNotification.winnerName}</span>
                    {gameKey === 'hearts' && trickWinnerNotification.points > 0 && (
                      <span className="text-xs text-red-400 block mt-0.5">
                        +{trickWinnerNotification.points} Penalty Pts
                      </span>
                    )}
                  </div>
                </div>
              )}
            </div>

            {/* Passing Phase CTA Button (Hearts) */}
            {phase === 'passing' && (
              <div className="mt-3 flex items-center gap-2 z-30">
                <button
                  onClick={handlePassCardsConfirm}
                  disabled={selectedPassCards.length !== 3}
                  className={`px-4 py-2 rounded-xl text-sm font-medium transition-[transform,background-color,border-color,box-shadow,opacity] flex items-center gap-1.5 shadow-raised ${
                    selectedPassCards.length === 3
                      ? 'bg-felt-600 text-white hover:bg-felt-700 cursor-pointer scale-105'
                      : 'bg-stone-700 text-stone-400 cursor-not-allowed'
                  }`}
                >
                  <span className="material-symbols-outlined text-base">swap_horiz</span>
                  <span>Pass 3 Cards ({selectedPassCards.length}/3)</span>
                </button>
              </div>
            )}

            {/* Bidding Phase Selector (Spades / Oh Hell) */}
            {phase === 'bidding' && (
              <div className="mt-3 bg-stone-900/90 border border-white/20 rounded-xl p-3 flex flex-col items-center gap-2 z-30 text-white backdrop-blur-md">
                <span className="text-xs font-bold">Select Your Contract Bid:</span>
                <div className="flex items-center gap-1 flex-wrap justify-center">
                  {[0, 1, 2, 3, 4, 5, 6, 7].map((b) => (
                    <button
                      key={b}
                      onClick={() => setUserBidSelection(b)}
                      className={`w-8 h-8 rounded-lg font-mono-code font-bold text-xs transition-[transform,background-color,border-color,box-shadow,opacity] cursor-pointer ${
                        userBidSelection === b
                          ? 'bg-felt-600 text-white scale-110 shadow-raised'
                          : 'bg-white/10 hover:bg-white/20 text-stone-200'
                      }`}
                    >
                      {b === 0 ? 'Nil' : b}
                    </button>
                  ))}
                </div>
                <button
                  onClick={handleSubmitBid}
                  className="mt-1 px-4 py-1.5 rounded-lg bg-felt-600 hover:bg-felt-700 text-sm font-medium text-white cursor-pointer shadow-sm"
                >
                  Confirm Bid ({userBidSelection === 0 ? 'Nil' : `${userBidSelection} Tricks`})
                </button>
              </div>
            )}
          </div>

          {/* East Player (Elena) */}
          <div className="col-span-3 sm:col-span-3 flex flex-col items-end gap-1">
            <div
              className={`flex items-center gap-2 px-2.5 py-1.5 rounded-xl border transition-[transform,background-color,border-color,box-shadow,opacity] max-w-full ${
                activePlayerIndex === 3
                  ? 'bg-felt-600 text-white border-felt-400 ring-4 ring-felt-500/30'
                  : 'bg-stone-900/80 backdrop-blur-md text-white border-stone-700'
              }`}
            >
              <div className="flex flex-col text-right truncate">
                <span className="text-xs font-bold truncate">{players[3].name}</span>
                <span className="text-[10px] opacity-80">
                  {players[3].cards.length} cards · {gameKey === 'hearts' ? `${players[3].roundScore} pts` : `Won: ${players[3].tricksWon}`}
                </span>
              </div>
              <div className="w-6 h-6 rounded-full bg-stone-700 flex items-center justify-center text-xs font-bold shrink-0">
                {players[3].avatar}
              </div>
            </div>

            {/* East Cards (Stacked column) */}
            <div className="flex flex-col -space-y-10 pt-1">
              {players[3].cards.slice(0, 7).map((_, i) => (
                <CardView
                  key={i}
                  faceDown
                  size="sm"
                  cardBack={settings.cardBack}
                  className="-rotate-90 origin-top-right"
                />
              ))}
            </div>
          </div>
        </div>

        {/* BOTTOM SEAT: User (Julian / South) & Hand */}
        <div className="flex flex-col items-center gap-2 relative z-10 pt-2">
          {/* User Status Bar */}
          <div
            className={`flex items-center gap-4 px-4 py-1.5 rounded-full border transition-[transform,background-color,border-color,box-shadow,opacity] ${
              activePlayerIndex === 0
                ? 'bg-felt-600 text-white border-felt-400 ring-4 ring-felt-500/30 shadow-raised'
                : 'bg-stone-900/85 backdrop-blur-md text-white border-stone-700'
            }`}
          >
            <div className="w-6 h-6 rounded-full bg-felt-700 border border-white/30 flex items-center justify-center text-xs font-bold">
              {players[0].avatar}
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold">{players[0].name}</span>
              <span className="text-[10px] bg-white/20 px-2 py-0.5 rounded font-semibold uppercase">
                Host
              </span>
            </div>
            <div className="h-3 w-px bg-white/20"></div>
            <span className="text-xs text-white/90">
              Tricks Won: <strong className="font-mono-code">{players[0].tricksWon}</strong>
            </span>
            {gameKey === 'hearts' && (
              <span className="text-xs text-red-300">
                Points: <strong className="font-mono-code">{players[0].roundScore}</strong>
              </span>
            )}
            {players[0].currentBid !== undefined && (
              <span className="text-xs text-felt-200">
                Bid: <strong className="font-mono-code">{players[0].currentBid}</strong>
              </span>
            )}
          </div>

          {/* USER CARD HAND */}
          <div className="w-full flex items-center justify-center overflow-x-auto pb-2 pt-3 px-2">
            <div className="flex -space-x-6 sm:-space-x-7 md:-space-x-8 hover:space-x-1 sm:hover:space-x-1 transition-[transform,background-color,border-color,box-shadow,opacity] duration-200">
              {players[0].cards.map((card) => {
                const isSelected = selectedPassCards.includes(card.id);
                const check = phase === 'playing' ? isCardPlayableForUser(card) : { legal: true };

                return (
                  <CardView
                    key={card.id}
                    card={card}
                    size="md"
                    selected={isSelected}
                    disabled={phase === 'playing' && (!check.legal || activePlayerIndex !== 0)}
                    tooltip={check.reason}
                    fourColor={settings.fourColorDeck}
                    onClick={() => handleCardClick(card)}
                  />
                );
              })}
            </div>
          </div>
        </div>

        {/* CUSTOM DECK / SANDBOX CONTROLS OVERLAY */}
        {gameKey === 'custom' && (
          <div className="absolute top-4 left-4 z-30 bg-stone-900/90 border border-stone-700 rounded-xl p-3 text-white backdrop-blur-md flex flex-col gap-2 max-w-xs shadow-raised">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-felt-400">
                Sandbox Controls
              </span>
              <span className="text-[10px] text-stone-400 font-mono-code">
                Deck: {sandboxDeck.length} cards
              </span>
            </div>
            <div className="flex items-center gap-1.5 flex-wrap">
              <button
                onClick={() => handleSandboxDeal(5)}
                className="px-2.5 py-1 rounded bg-felt-600 hover:bg-felt-700 text-[11px] font-bold cursor-pointer"
              >
                Deal 5
              </button>
              <button
                onClick={() => handleSandboxDeal(7)}
                className="px-2.5 py-1 rounded bg-felt-600 hover:bg-felt-700 text-[11px] font-bold cursor-pointer"
              >
                Deal 7
              </button>
              <button
                onClick={handleSandboxReset}
                className="px-2.5 py-1 rounded bg-stone-700 hover:bg-stone-600 text-[11px] font-bold cursor-pointer"
              >
                Reset Table
              </button>
            </div>
            {rules.rule2 && (
              <div className="pt-2 border-t border-stone-800 flex items-center justify-between text-xs">
                <span>Your Chips: {sandboxChips.p0 || 0}</span>
                <div className="flex gap-1">
                  <button
                    onClick={() => handleSandboxChipChange('p0', 25)}
                    className="px-1.5 py-0.5 rounded bg-emerald-700 text-[10px] font-bold cursor-pointer"
                  >
                    +25
                  </button>
                  <button
                    onClick={() => handleSandboxChipChange('p0', -25)}
                    className="px-1.5 py-0.5 rounded bg-red-700 text-[10px] font-bold cursor-pointer"
                  >
                    -25
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* GAME LOG DRAWER */}
      {isLogOpen && (
        <div className="bg-white border border-stone-200 rounded-xl p-4 shadow-sm flex flex-col gap-2">
          <div className="flex items-center justify-between border-b border-stone-200 pb-2">
            <span className="text-sm font-medium text-stone-800 flex items-center gap-1.5">
              <span className="material-symbols-outlined text-sm text-felt-600">history</span>
              Round Play Log
            </span>
            <button
              onClick={() => setIsLogOpen(false)}
              className="text-xs text-stone-400 hover:text-stone-700 cursor-pointer"
            >
              ✕ Close
            </button>
          </div>
          <div className="max-h-48 overflow-y-auto flex flex-col gap-1.5 font-mono-code text-xs">
            {gameLogs.length === 0 ? (
              <span className="text-stone-400 italic">No plays yet this round.</span>
            ) : (
              gameLogs.map((log) => (
                <div key={log.id} className="flex items-center justify-between text-stone-700">
                  <span
                    className={
                      log.type === 'trick'
                        ? 'text-felt-600 font-bold'
                        : log.type === 'score'
                        ? 'text-red-600 font-bold'
                        : log.type === 'system'
                        ? 'text-emerald-700'
                        : 'text-stone-700'
                    }
                  >
                    {log.text}
                  </span>
                  <span className="text-[10px] text-stone-400 shrink-0 pl-2">{log.timestamp}</span>
                </div>
              ))
            )}
          </div>
        </div>
      )}

      {/* ROUND SUMMARY MODAL */}
      {phase === 'round_end' && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-white border border-stone-200 rounded-xl max-w-lg w-full p-6 shadow-raised flex flex-col gap-4 animate-scale-up">
            <div className="flex items-center justify-between border-b border-stone-200 pb-3">
              <div className="flex items-center gap-2">
                <span className="text-2xl">🏆</span>
                <h3 className="text-lg font-bold text-stone-950 font-display">
                  Round {roundNumber} Summary
                </h3>
              </div>
              <span className="text-xs font-bold bg-felt-100 text-felt-700 px-2.5 py-0.5 rounded-full">
                {gameDef.name}
              </span>
            </div>

            {/* Scoreboard Table */}
            <div className="border border-stone-200 rounded-xl overflow-hidden">
              <table className="w-full text-left text-xs">
                <thead className="bg-stone-50 text-stone-500 uppercase font-semibold border-b border-stone-200">
                  <tr>
                    <th className="py-2.5 px-3">Player</th>
                    <th className="py-2.5 px-3 text-center">Tricks</th>
                    <th className="py-2.5 px-3 text-right">Round Pts</th>
                    <th className="py-2.5 px-3 text-right font-bold">Total Score</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-stone-100 font-mono-code">
                  {players.map((p) => (
                    <tr key={p.id} className={p.isUser ? 'bg-felt-50/50 font-bold' : ''}>
                      <td className="py-2.5 px-3 font-sans font-medium text-stone-900 flex items-center gap-2">
                        <span>{p.avatar}</span>
                        <span>{p.name}</span>
                        {p.isUser && (
                          <span className="text-[10px] bg-felt-600 text-white px-1.5 rounded">
                            YOU
                          </span>
                        )}
                      </td>
                      <td className="py-2.5 px-3 text-center">{p.tricksWon}</td>
                      <td
                        className={`py-2.5 px-3 text-right ${
                          p.roundScore > 0 && gameKey === 'hearts' ? 'text-red-600' : 'text-stone-700'
                        }`}
                      >
                        {p.roundScore}
                      </td>
                      <td className="py-2.5 px-3 text-right font-bold text-stone-950">
                        {p.totalScore}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Next Hand Action */}
            <div className="flex items-center gap-3 pt-2">
              <button
                onClick={onLeaveTable}
                className="flex-1 py-2.5 rounded-xl border border-stone-200 text-stone-700 hover:bg-stone-50 text-sm font-medium cursor-pointer"
              >
                Back to Lobby
              </button>
              <button
                onClick={handleNextRound}
                className="flex-1 py-2.5 rounded-xl bg-felt-600 hover:bg-felt-700 text-white text-sm font-medium cursor-pointer shadow-raised flex items-center justify-center gap-1.5"
              >
                <span className="material-symbols-outlined text-base">play_arrow</span>
                <span>Next Deal / Round {roundNumber + 1}</span>
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
