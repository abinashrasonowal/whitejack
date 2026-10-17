package com.whitejack.games.hearts;

import com.whitejack.contract.Card;
import com.whitejack.contract.PlayerId;
import com.whitejack.contract.PlayerView;
import java.util.List;

/**
 * What one viewer may see. Other players' cards never appear — only how many they hold. The
 * viewer's {@code legal} cards are computed here so the client can enable the right ones
 * without carrying a copy of the rules (§3 rule 5).
 *
 * @param myHand the viewer's own cards; empty for a spectator
 * @param legal cards the viewer may pass or play right now; empty when it is not their move
 * @param tricks every trick finished this hand, in order, so a player can count what is gone
 * @param myPass the three cards the viewer chose to pass this hand; empty for a spectator or a hold hand
 * @param received the three cards passed to the viewer; empty until the exchange happens
 * @param history one row per finished hand, points in seat order
 * @param winner lowest score once the game is over, else null
 */
public record HeartsView(
        HeartsPhase phase,
        int hand,
        PassDirection passDirection,
        boolean heartsBroken,
        int tricksPlayed,
        List<SeatView> seats,
        List<Card> myHand,
        List<Card> legal,
        List<PlayedCard> trick,
        PlayerId leader,
        PlayerId onClock,
        List<PlayedCard> lastTrick,
        PlayerId lastTrickWinner,
        List<Trick> tricks,
        List<Card> myPass,
        List<Card> received,
        List<List<Integer>> history,
        PlayerId winner) implements PlayerView {

    public record SeatView(int index, PlayerId id, String nick, int cardCount, int handPoints, int score,
            boolean passed) {}
}
