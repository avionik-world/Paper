package io.papermc.generator.rewriter.parser;

public record ClosureAdvanceResult(boolean found, boolean advanced) {

    public static final ClosureAdvanceResult NONE = new ClosureAdvanceResult(false, false);

    public static ClosureAdvanceResult find(boolean found) {
        return new ClosureAdvanceResult(found, true);
    }

    public static ClosureAdvanceResult skip() {
        return new ClosureAdvanceResult(false, true);
    }
}
