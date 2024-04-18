package io.papermc.generator.rewriter.parser;

public enum ClosureAdvanceResult {
    /**
     * When the pointer advance on a closure. Escaped end closure are skipped!
     */
    CHANGED,
    /**
     * When the pointer doesn't advance and cannot find the closure.
     */
    IGNORED,
    /**
     * When the pointer advance by skipping strong closure within weak closure
     * or by reaching a weak end closure escaped by '\' char preceding it.
     */
    SKIPPED
}
