package com.whitejack.bots.view;

import com.whitejack.contract.Card;
import java.util.List;

/** games/hearts HeartsView; fields a bot does not use are dropped on read. */
public record HeartsView(
        String phase,
        int hand,
        String passDirection,
        boolean heartsBroken,
        int tricksPlayed,
        List<SeatView> seats,
        List<Card> myHand,
        List<Card> legal,
        List<PlayedCard> trick,
        String leader,
        String onClock,
        List<PlayedCard> lastTrick,
        String lastTrickWinner,
        List<Trick> tricks,
        List<Card> myPass,
        List<Card> received,
        String winner) {

    public record SeatView(int index, String id, String nick, int cardCount, int handPoints, int score,
            boolean passed) {}

    public record PlayedCard(String player, Card card) {}

    public record Trick(List<PlayedCard> plays, String winner) {}
}
