package eu.faircode.email;

/** Read-only projection for the Spam Family Lab overview. */
public class TupleSpamFamilyOverview {
    public long family_id;
    public String name;
    public boolean active;
    public int confirmed_count;
    public long created_at;
    public long updated_at;

    public int exemplar_count;
    public int predicted_count;
    public int strong_count;
    public int strong_unknown_count;
    public int strong_ham_count;
    public Double max_score;

    public Integer rescore_processed;
    public Integer rescore_matches;
    public Long rescore_cursor;
}
