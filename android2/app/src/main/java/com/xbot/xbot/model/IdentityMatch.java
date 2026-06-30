package com.xbot.xbot.model;

import com.xbot.xbot.data.PersonEntity;

/** Face identity match result. */
public class IdentityMatch {
    public final PersonEntity person;
    public final double similarity;

    public IdentityMatch(PersonEntity person, double similarity) {
        this.person = person;
        this.similarity = similarity;
    }
}
