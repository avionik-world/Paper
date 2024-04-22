package io.papermc.generator.rewriter.parser;

import io.papermc.generator.rewriter.parser.closure.ClosureType;

public enum ClosureTypeAdvanceResult {
    /**
     * When the pointer advance on a closure. Escaped end closure are skipped!
     */
    CHANGED,
    /**
     * When the pointer doesn't advance and cannot find the closure.
     */
    IGNORED,
    /**
     * When the pointer advance by reaching a leaf closure escaped by '\' char preceding it.
     *
     * @see ClosureType#ALLOW_ESCAPE
     */
    SKIPPED
}
