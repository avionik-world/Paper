package io.papermc.generator.rewriter.parser;

import com.google.common.base.Preconditions;
import io.papermc.generator.rewriter.context.ImportCollector;
import io.papermc.generator.rewriter.parser.closure.AbstractClosure;
import io.papermc.generator.rewriter.parser.closure.Closure;
import io.papermc.generator.rewriter.parser.closure.ClosureType;
import io.papermc.generator.rewriter.parser.step.IterativeStep;
import io.papermc.generator.rewriter.parser.step.StepManager;
import io.papermc.generator.rewriter.parser.step.model.AnnotationSkipSteps;
import io.papermc.generator.rewriter.parser.step.model.ImportStatementSteps;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;

@ApiStatus.Internal
public class LineParser {

    private final StepManager stepManager = new StepManager();

    private Closure nearestClosure;

    @Nullable
    public Closure getNearestClosure() {
        return this.nearestClosure;
    }

    // internal use only or when nearestClosure = null
    // doesn't support leaf closure char escape
    public boolean tryAdvanceStartClosure(@NotNull ClosureType type, @NotNull StringReader line) {
        if (line.trySkipString(type.start)) { // closure has been consumed
            Closure previousNearestClosure = this.nearestClosure;
            this.nearestClosure = Closure.create(type);
            if (previousNearestClosure != null) {
                if (ClosureType.LEAFS.contains(previousNearestClosure.getType())) {
                    throw new ParserException("Nested closure in a leaf closure is not allowed", line);
                }
                ((AbstractClosure) this.nearestClosure).setParent(previousNearestClosure);
            }
            this.nearestClosure.onStart(line);
            return true;
        }
        return false;
    }

    // for all closure, leaf closure type should use the other similar method after this one if possible
    public ClosureAdvanceResult tryAdvanceEndClosure(@NotNull Closure closure, @NotNull StringReader line) {
        Preconditions.checkState(this.nearestClosure != null && this.nearestClosure.hasUpperClosure(closure), "Need to be in an upper closure of " + closure + " to find its end identifier");
        boolean directClosureFound = this.nearestClosure == closure;
        if (!directClosureFound) {
            return ClosureAdvanceResult.IGNORED;
        }

        char previousChar = '\0';
        if (line.getCursor() >= 1) {
            previousChar = line.peek(-1);
        }

        ClosureType type = closure.getType();
        if (line.trySkipString(type.end)) { // closure has been consumed
            // skip escape closed closure
            if (type.escapableByPreviousChar() && previousChar == '\\') {
                return ClosureAdvanceResult.SKIPPED;
            }

            this.nearestClosure.onEnd(line);
            this.nearestClosure = this.nearestClosure.parent();
            return ClosureAdvanceResult.CHANGED;
        }

        return ClosureAdvanceResult.IGNORED;
    }

    // computedTypes list order matters here
    public boolean trySkipNestedClosures(@NotNull Closure inClosure, @NotNull StringReader line, @NotNull List<ClosureType> computedTypes) {
        boolean directClosureFound = this.nearestClosure == inClosure;
        boolean isLeaf = this.nearestClosure != null && ClosureType.LEAFS.contains(this.nearestClosure.getType());
        if (this.nearestClosure != null && !directClosureFound) {
            final ClosureAdvanceResult result;
            if (isLeaf) {
                result = this.tryAdvanceEndLeafClosure(this.nearestClosure.getType(), line);
            } else {
                result = this.tryAdvanceEndClosure(this.nearestClosure, line);
            }
            if (result != ClosureAdvanceResult.IGNORED) {
                return true;
            }
        }

        if (this.nearestClosure == null || !isLeaf) { // leaf take the priority and doesn't allow any other nested type
            for (ClosureType type : computedTypes) {
                if (this.tryAdvanceStartClosure(type, line)) {
                    return true;
                }
            }
        }
        return false;
    }

    public ClosureAdvanceResult tryAdvanceEndLeafClosure(@NotNull ClosureType type, @NotNull StringReader line) {
        Preconditions.checkArgument(ClosureType.LEAFS.contains(type), "Only leaf closure can be advanced using its type only, for other types use the closure equivalent method to take in account nested closures");
        Preconditions.checkState(this.nearestClosure != null && this.nearestClosure.getType() == type, "Need a direct upper closure of " + type);

        char previousChar = '\0';
        if (line.getCursor() >= 1) {
            previousChar = line.peek(-1);
        }

        if (line.trySkipString(type.end)) { // closure has been consumed
            // skip escape closed closure
            if (type.escapableByPreviousChar() && previousChar == '\\') {
                return ClosureAdvanceResult.SKIPPED;
            }

            this.nearestClosure.onEnd(line);
            this.nearestClosure = this.nearestClosure.parent();
            return ClosureAdvanceResult.CHANGED;
        }
        return ClosureAdvanceResult.IGNORED;
    }

    // generic usage that check other leaf closure
    private boolean skipLeafClosure(@NotNull ClosureType type, @NotNull StringReader line) {
        final boolean isInClosure;
        if (this.nearestClosure != null) {
            isInClosure = this.nearestClosure.getType() == type;
            if (!isInClosure && ClosureType.LEAFS.contains(this.nearestClosure.getType())) {
                // don't try to advance the pointer when the nearest closure is another
                // leaf closure (leaf closure doesn't allow nested type)
                return false;
            }
        } else {
            isInClosure = false;
        }

        int previousCursor = line.getCursor();
        if (isInClosure ||
            this.tryAdvanceStartClosure(type, line)) { // open closure?
            ClosureAdvanceResult result;
            while ((result = this.tryAdvanceEndLeafClosure(type, line)) != ClosureAdvanceResult.CHANGED && line.canRead()) { // closed closure?
                if (result == ClosureAdvanceResult.IGNORED) {
                    line.skip();
                }
            }
            return line.getCursor() > previousCursor;
        }
        return false;
    }

    public boolean skipComment(@NotNull StringReader line) {
        return this.skipLeafClosure(ClosureType.COMMENT, line);
    }

    public boolean skipCommentOrWhitespace(@NotNull StringReader line) {
        boolean skipped = false;
        while (this.skipComment(line) || line.skipWhitespace() > 0) {
            skipped = true;
        }
        return skipped;
    }

    public boolean trySkipCommentOrWhitespaceUntil(@NotNull StringReader line, char terminator) {
        int previousCursor = line.getCursor();
        boolean skipped = this.skipCommentOrWhitespace(line);
        if (skipped && line.canRead() && line.peek() != terminator) {
            line.setCursor(previousCursor);
            skipped = false;
        }

        return skipped;
    }

    // note: always check single line comment AFTER multi line comment unless exception
    public boolean peekSingleLineComment(@NotNull StringReader line) {
        return line.canRead(2) && line.peek() == '/' && line.peek(1) == '/';
    }

    public boolean consumeImports(@NotNull StringReader line, @NotNull ImportCollector collector) {
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
            if (AnnotationSkipSteps.canStart(c)) { // handle annotation with param to avoid open curly bracket that occur in array argument
                this.stepManager.enqueue(new AnnotationSkipSteps());
                continue;
            } else if (c == '{') {
                return true;
            } else if (ImportStatementSteps.canStart(line)) {
                this.stepManager.enqueue(new ImportStatementSteps(collector));
                continue;
            }

            line.skip();
        }
        return false;
    }

    @NotNull
    public StepManager getSteps() {
        return this.stepManager;
    }
}
