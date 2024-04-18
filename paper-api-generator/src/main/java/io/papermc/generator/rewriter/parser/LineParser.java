package io.papermc.generator.rewriter.parser;

import io.papermc.generator.rewriter.context.ImportCollector;
import io.papermc.generator.rewriter.parser.step.IterativeStep;
import io.papermc.generator.rewriter.parser.step.StepManager;
import io.papermc.generator.rewriter.parser.step.model.AnnotationSteps;
import io.papermc.generator.rewriter.parser.step.model.ImportSteps;
import org.jetbrains.annotations.ApiStatus;
import java.util.EnumSet;
import java.util.Set;

@ApiStatus.Internal
public class LineParser {

    private final Set<ClosureType> closures = EnumSet.noneOf(ClosureType.class);
    private final StepManager stepManager = new StepManager();

    private ClosureType strongClosure;

    public boolean advanceEnclosure(ClosureType type, StringReader line) {
        return this.advanceEnclosure0(type, line) == ClosureAdvanceResult.CHANGED;
    }

    public ClosureAdvanceResult advanceEnclosure0(ClosureType type, StringReader line) {
        boolean inside = this.closures.contains(type);
        String closure = !inside ? type.start : type.end;

        // skip comments, char and string inside the upper closure for weak closure type
        if (!type.strong && inside) {
            ClosureType skipClosure = this.strongClosure;
            if (skipClosure == null) {
                for (ClosureType strongClosure : ClosureType.values()) {
                    if (!strongClosure.strong) {
                        continue;
                    }

                    if (this.advanceEnclosure(strongClosure, line)) {
                        skipClosure = strongClosure;
                        break;
                    }
                }
            }

            if (skipClosure != null) {
                ClosureAdvanceResult result;
                while ((result = this.advanceEnclosure0(skipClosure, line)) != ClosureAdvanceResult.CHANGED && line.canRead()) {
                    if (result == ClosureAdvanceResult.IGNORED) {
                        line.skip();
                    }
                }
                return ClosureAdvanceResult.SKIPPED;
            }
        }

        char previousChar = '\0';
        if (line.getCursor() >= 1) {
            previousChar = line.peek(-1);
        }

        if (line.trySkipString(closure)) { // closure has been consumed
            if (!inside) {
                if (!this.closures.add(type)) {
                    throw new ParserException("Nested same closure detected for " + type, line);
                }
            } else {
                // skip escape closed closure
                if (closure.length() == 1 && type.start.equals(type.end) && ClosureType.ALLOW_ESCAPE.contains(type)) {
                    if (previousChar == '\\') {
                        return ClosureAdvanceResult.SKIPPED;
                    }
                }

                if (!this.closures.remove(type)) {
                    throw new ParserException("Missing open closure? for " + type, line);
                }
            }
            if (type.strong) {
                this.strongClosure = !inside ? type : null;
            }
            return ClosureAdvanceResult.CHANGED;
        }
        return ClosureAdvanceResult.IGNORED;
    }

    public boolean skipComment(StringReader line) {
        int previousCursor = line.getCursor();
        if (this.closures.contains(ClosureType.COMMENT) || this.advanceEnclosure(ClosureType.COMMENT, line)) { // open comment?
            ClosureAdvanceResult result;
            while ((result = this.advanceEnclosure0(ClosureType.COMMENT, line)) != ClosureAdvanceResult.CHANGED && line.canRead()) { // closed comment?
                if (result == ClosureAdvanceResult.IGNORED) {
                    line.skip();
                }
            }
            return line.getCursor() > previousCursor;
        }
        return false;
    }

    public boolean skipCommentOrWhitespace(StringReader line) {
        boolean skipped = false;
        while (this.skipComment(line) || line.skipWhitespace() > 0) {
            skipped = true;
        }
        return skipped;
    }

    public boolean trySkipCommentOrWhitespaceUntil(StringReader line, char terminator) {
        int previousCursor = line.getCursor();
        boolean skipped = this.skipCommentOrWhitespace(line);
        if (skipped && line.canRead() && line.peek() != terminator) {
            line.setCursor(previousCursor);
            skipped = false;
        }

        return skipped;
    }

    public boolean peekSingleLineComment(StringReader line) {
        return line.peek() == '/' && line.canRead(2) && line.peek(1) == '/';
    }

    public boolean consumeImports(StringReader line, ImportCollector collector) {
    outerLoop:
        while (line.canRead()) {
            IterativeStep step;
            while ((step = this.stepManager.getSteps().poll()) != null) {
                step.run(line, this);
                if (!line.canRead()) {
                    break outerLoop;
                }
            }

            if (this.skipCommentOrWhitespace(line)) {
                continue;
            }

            if (this.peekSingleLineComment(line)) {
                // check single line comment only after multi line to avoid ignoring the end of multi line comment starting with // on the newline
                break;
            }

            // not commented
            char c = line.peek();
            if (AnnotationSteps.canStart(c)) { // handle annotation with param to avoid open curly bracket that occur in array argument
                this.stepManager.enqueue(new AnnotationSteps());
                continue;
            } else if (c == '{') {
                return true;
            } else if (ImportSteps.canStart(line)) {
                this.stepManager.enqueue(new ImportSteps(collector));
                continue;
            }

            line.skip();
        }
        return false;
    }

    public StepManager getSteps() {
        return this.stepManager;
    }
}
