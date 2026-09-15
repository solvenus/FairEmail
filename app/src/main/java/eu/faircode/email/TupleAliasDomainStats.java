package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

/** Aggregated sender-domain history for one envelope alias. */
public class TupleAliasDomainStats {
    public String domain;
    public int messages;
    public int unsubscribe;
    public int ham;
    public int spam;

    public double unsubscribeRate() {
        return messages <= 0 ? 0 : (double) unsubscribe / messages;
    }
}
