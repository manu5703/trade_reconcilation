package com.recon.core.match;

/** One step of the matching pipeline. Each pass only looks at records earlier passes left unmatched. */
interface MatchPass {

    void apply(MatchState state);
}
