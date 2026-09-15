package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DaoSpamFamilyEvidence {
    @Query("UPDATE alias_delivery SET" +
            " predicted_family_id = :familyId," +
            " family_score = :score," +
            " family_score_raw = :raw," +
            " family_text = :text," +
            " family_structure = :structure," +
            " family_links = :links," +
            " family_sender = :sender," +
            " family_assessed_at = :assessedAt" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int setFamilyMatch(String accountUuid, long messageId, Long familyId,
                       double score, double raw, double text, double structure,
                       double links, double sender, long assessedAt);

    @Query("UPDATE alias_delivery SET" +
            " predicted_family_id = NULL," +
            " family_score = NULL," +
            " family_score_raw = NULL," +
            " family_text = NULL," +
            " family_structure = NULL," +
            " family_links = NULL," +
            " family_sender = NULL," +
            " family_assessed_at = :assessedAt" +
            " WHERE account_uuid = :accountUuid AND message_id = :messageId")
    int clearFamilyMatch(String accountUuid, long messageId, long assessedAt);

    @Query("SELECT * FROM alias_delivery" +
            " WHERE account_uuid = :accountUuid" +
            " AND predicted_family_id IS NOT NULL" +
            " ORDER BY family_score DESC, received DESC")
    LiveData<List<EntityAliasDelivery>> liveFamilyMatches(String accountUuid);
}
