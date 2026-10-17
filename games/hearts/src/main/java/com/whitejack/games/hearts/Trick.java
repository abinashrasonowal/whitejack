package com.whitejack.games.hearts;

import com.whitejack.contract.PlayerId;
import java.util.List;

/** A finished trick: the cards in the order they were played, and who took them. Public. */
public record Trick(List<PlayedCard> plays, PlayerId winner) {
    public Trick {
        plays = List.copyOf(plays);
    }
}
