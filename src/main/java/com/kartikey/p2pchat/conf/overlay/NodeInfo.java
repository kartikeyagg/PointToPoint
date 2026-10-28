package com.kartikey.p2pchat.conf.overlay;

/**
 * What the overlay needs to know about a participant to decide how much it can forward.
 *
 * @param id         stable participant id
 * @param uploadKbps measured/declared upload budget
 * @param canRelay   false for strict-NAT or mobile nodes: they only ever receive (leaf in every stripe)
 */
public record NodeInfo(long id, int uploadKbps, boolean canRelay) {}
