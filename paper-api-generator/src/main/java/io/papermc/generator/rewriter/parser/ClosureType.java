package io.papermc.generator.rewriter.parser;

import java.util.EnumSet;

public enum ClosureType { // todo refactor? this become bigger than initially planned
    PARENTHESIS("(", ")", false),
    CURLY_BRACKET("{", "}", false),
    BRACKET("[", "]", false),
    COMMENT("/*", "*/"),

    // escape allowed
    // todo could handle the extra newline needed after """
    PARAGRAPH("\"\"\"", "\"\"\""), // order matter here this one must be checked before string for the strong check
    STRING("\"", "\""),
    CHAR("'", "'");

    public final String start;
    public final String end;
    public final boolean strong; // once within this closure ignore all the weak ones

    ClosureType(String start, String end) {
        this(start, end, true);
    }

    ClosureType(String start, String end, boolean strong) {
        this.start = start;
        this.end = end;
        this.strong = strong;
    }

    public static final EnumSet<ClosureType> ALLOW_ESCAPE = EnumSet.of(STRING, CHAR, PARAGRAPH);
}
