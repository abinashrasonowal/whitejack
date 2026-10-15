package com.whitejack.bots.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whitejack.bots.Choice;
import com.whitejack.bots.view.HeartsView;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Card counting from the hand's history. Seats run me, lefty, cross, righty, so play passes from
 * me to lefty and the player on my right plays just before me.
 */
class HeartsBrainTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String SEATS = "[" + seat(0, "me") + "," + seat(1, "lefty") + "," + seat(2, "cross") + ","
            + seat(3, "righty") + "]";

    private static String seat(int index, String id) {
        return seat(index, id, 0);
    }

    private static String seat(int index, String id, int handPoints) {
        return "{'index':" + index + ",'id':'" + id + "','nick':'" + id + "','cardCount':10,'handPoints':" + handPoints
                + ",'score':0,'passed':true}";
    }

    /** "QS", "10H", "2C" as a wire card. */
    private static String c(String code) {
        String rank = switch (code.substring(0, code.length() - 1)) {
            case "2" -> "TWO";
            case "3" -> "THREE";
            case "4" -> "FOUR";
            case "5" -> "FIVE";
            case "6" -> "SIX";
            case "7" -> "SEVEN";
            case "8" -> "EIGHT";
            case "9" -> "NINE";
            case "10" -> "TEN";
            case "J" -> "JACK";
            case "Q" -> "QUEEN";
            case "K" -> "KING";
            default -> "ACE";
        };
        String suit = switch (code.charAt(code.length() - 1)) {
            case 'C' -> "CLUBS";
            case 'D' -> "DIAMONDS";
            case 'H' -> "HEARTS";
            default -> "SPADES";
        };
        return "{'rank':'" + rank + "','suit':'" + suit + "'}";
    }

    private static String cards(String... codes) {
        return "[" + Arrays.stream(codes).map(HeartsBrainTest::c).collect(Collectors.joining(",")) + "]";
    }

    /** "lefty:5S cross:9S" as plays. */
    private static String plays(String plays) {
        if (plays.isBlank()) return "[]";
        return "[" + Arrays.stream(plays.split(" ")).map(p -> p.split(":"))
                .map(p -> "{'player':'" + p[0] + "','card':" + c(p[1]) + "}").collect(Collectors.joining(",")) + "]";
    }

    private static String trick(String plays, String winner) {
        return "{'plays':" + plays(plays) + ",'winner':'" + winner + "'}";
    }

    private static HeartsView view(String seats, String hand, String tricks, String trick, boolean broken)
            throws Exception {
        int played = tricks.isEmpty() ? 0 : tricks.split("'winner'").length - 1;
        String json = "{'phase':'PLAYING','hand':0,'passDirection':'LEFT','heartsBroken':" + broken
                + ",'tricksPlayed':" + played + ",'seats':" + seats + ",'myHand':" + hand + ",'legal':" + hand
                + ",'trick':" + plays(trick) + ",'tricks':[" + tricks + "],'onClock':'me'}";
        return JSON.readValue(json.replace('\'', '"'), HeartsView.class);
    }

    private static String played(HeartsView view) {
        Choice choice = new HeartsBrain().choose(view, "me").orElseThrow();
        JsonNode card = choice.intent(choice.fallback()).path("card");
        return card.path("rank").asText() + " " + card.path("suit").asText();
    }

    @Test
    void leadsSpadesToFlushTheQueenWhenItHoldsNoHighSpade() throws Exception {
        String tricks = trick("me:2C lefty:3C cross:KC righty:5C", "cross") + ","
                + trick("cross:4C righty:6C me:7C lefty:9C", "lefty");
        HeartsView v = view(SEATS, cards("8S", "3S", "8C", "5D", "9D", "JD"), tricks, "", false);
        assertTrue(played(v).endsWith("SPADES"), played(v));
    }

    @Test
    void neverLeadsTheAceOfSpadesWhileTheQueenIsOut() throws Exception {
        HeartsView v = view(SEATS, cards("AS", "10D"), trick("me:2C lefty:3C cross:KC righty:5C", "cross"), "", false);
        assertEquals("TEN DIAMONDS", played(v));
    }

    @Test
    void takesACleanTrickInLastSeatWithItsHighestCardOnceTheQueenIsGone() throws Exception {
        String tricks = trick("righty:2S me:4S lefty:QS cross:KS", "cross");
        HeartsView v = view(SEATS, cards("3S", "AS"), tricks, "lefty:5S cross:9S righty:7S", true);
        assertEquals("ACE SPADES", played(v));
    }

    @Test
    void ducksWhenTheTrickHasPointsEvenInLastSeat() throws Exception {
        HeartsView v = view(SEATS, cards("3D", "AD"), "", "lefty:5D cross:2H righty:7D", true);
        assertEquals("THREE DIAMONDS", played(v));
    }

    @Test
    void doesNotDiscardHeartsOntoAMoonShooter() throws Exception {
        String seats = "[" + seat(0, "me") + "," + seat(1, "lefty", 18) + "," + seat(2, "cross") + "," + seat(3, "righty") + "]";
        String tricks = trick("lefty:AS cross:QS righty:2H me:3S", "lefty") + ","
                + trick("lefty:AH cross:4H righty:5H me:6H", "lefty");
        HeartsView shooting = view(seats, cards("2S", "10H", "9C"), tricks, "lefty:KD cross:3D righty:4D", true);
        assertEquals("NINE CLUBS", played(shooting));

        // The same discard with the points split: dump the heart.
        String split = "[" + seat(0, "me") + "," + seat(1, "lefty", 14) + "," + seat(2, "cross", 4) + ","
                + seat(3, "righty") + "]";
        String splitTricks = trick("lefty:AS cross:QS righty:2H me:3S", "lefty") + ","
                + trick("lefty:AH cross:4H righty:5H me:6H", "cross");
        HeartsView normal = view(split, cards("2S", "10H", "9C"), splitTricks, "lefty:KD cross:3D righty:4D", true);
        assertEquals("TEN HEARTS", played(normal));
    }

    @Test
    void takesAPointTrickFromAMoonShooter() throws Exception {
        String seats = "[" + seat(0, "me") + "," + seat(1, "lefty", 18) + "," + seat(2, "cross") + "," + seat(3, "righty") + "]";
        String tricks = trick("lefty:AS cross:QS righty:2H me:3S", "lefty") + ","
                + trick("lefty:AH cross:4H righty:5H me:6H", "lefty");
        HeartsView v = view(seats, cards("2C", "KC", "AC"), tricks, "lefty:QC cross:7H righty:3C", true);
        assertEquals("KING CLUBS", played(v));
    }

    @Test
    void thePositionCarriesThePassTheTricksTheVoidsAndTheQueen() throws Exception {
        String json = "{'phase':'PLAYING','hand':0,'passDirection':'LEFT','heartsBroken':false,'tricksPlayed':1,"
                + "'seats':" + SEATS + ",'myHand':" + cards("3S", "9D") + ",'legal':" + cards("3S", "9D")
                + ",'trick':[],'tricks':[" + trick("me:2C lefty:5C cross:8D righty:KC", "righty") + "],"
                + "'myPass':" + cards("QS", "AH", "KH") + ",'received':" + cards("2D", "4S", "JC") + ",'onClock':'me'}";
        HeartsView v = JSON.readValue(json.replace('\'', '"'), HeartsView.class);
        String state = HeartsBrain.describe(v, "me");
        assertTrue(state.contains("You passed Q♠ A♥ K♥ to lefty."), state);
        assertTrue(state.contains("still in lefty's hand): Q♠ A♥ K♥"), state);
        assertTrue(state.contains("You received 2♦ 4♠ J♣ from righty."), state);
        assertTrue(state.contains("1. you 2♣, lefty 5♣, cross 8♦, righty K♣ -> taken by righty"), state);
        assertTrue(state.contains("Known voids: cross is void in ♣."), state);
        assertTrue(state.contains("The Q♠ is in lefty's hand (you passed it)."), state);
        assertTrue(state.contains("lefty on your left, cross across from you, righty on your right"), state);
        assertTrue(state.contains("9♦ (5 higher out, 6 lower)"), state);
    }
}
