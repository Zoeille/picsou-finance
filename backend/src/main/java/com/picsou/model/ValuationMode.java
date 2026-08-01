package com.picsou.model;

/**
 * Whether the estimator is allowed to write the account balance.
 *
 * <p>{@link #MANUAL} is the escape hatch: the user's own figure wins and refreshes stop
 * overwriting it. Estimates are still computed and recorded so the comparison stays
 * visible — they just do not touch the balance.
 */
public enum ValuationMode {
    ESTIMATED,
    MANUAL
}
