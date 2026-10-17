package com.whitejack.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.Objects;

/** Natural order is ace-high by rank, then suit, so that sorting a hand is not each game's job. */
public record Card(Rank rank, Suit suit) implements Comparable<Card> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Comparator<Card> ORDER =
            Comparator.comparingInt((Card c) -> c.rank.value()).thenComparing(Card::suit);

    public Card {
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(suit, "suit");
    }

    public static Card of(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }

    /**
     * A card from an intent's JSON, {@code {"rank":"QUEEN","suit":"SPADES"}}, for a game's
     * {@code parseIntent}.
     *
     * @throws IllegalArgumentException for anything else, which the engine reports as a malformed intent
     */
    public static Card fromJson(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("expected a card {rank, suit}");
        try {
            return JSON.treeToValue(node, Card.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("not a card: " + node);
        }
    }

    @Override
    public int compareTo(Card other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return rank.symbol() + suit.symbol();
    }
}
