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
    // ignoreNestedClosureTypes order matter here
    public ClosureAdvanceResult tryAdvanceEndClosure(@NotNull Closure closure, @NotNull StringReader line) {
        Preconditions.checkState(this.nearestClosure != null && this.nearestClosure.hasUpperClosure(closure), "Need to be in an upper closure of " + closure + " to find its end identifier");
        boolean directClosureFound = this.nearestClosure == closure;
        boolean canSearchEndClosure = this.nearestClosure == null || directClosureFound;

        char previousChar = '\0';
        if (line.getCursor() >= 1) {
            previousChar = line.peek(-1);
        }

        ClosureType type = closure.getType();
        if (canSearchEndClosure && line.trySkipString(type.end)) { // closure has been consumed
            // skip escape closed closure
            if (type.end.length() == 1 && type.start.equals(type.end) && ClosureType.ALLOW_ESCAPE.contains(type)) {
                if (previousChar == '\\') {
                    return ClosureAdvanceResult.skip();
                }
            }

            this.nearestClosure.onEnd(line);
            if (this.nearestClosure.parent() != null) {
                this.nearestClosure = this.nearestClosure.parent();
            } else {
                this.nearestClosure = null;
            }
            return ClosureAdvanceResult.find(directClosureFound);
        }

        return ClosureAdvanceResult.NONE;
    }

    public boolean trySkipNestedClosures(@NotNull Closure inClosure, @NotNull StringReader line, @NotNull List<ClosureType> computedTypes) {
        boolean directClosureFound = this.nearestClosure == inClosure;
        boolean isLeaf = this.nearestClosure != null && ClosureType.LEAFS.contains(this.nearestClosure.getType());
        if (this.nearestClosure != null && !directClosureFound) {
            final boolean advanced;
            if (isLeaf) {
                advanced = this.tryAdvanceEndClosure(this.nearestClosure.getType(), line) != ClosureTypeAdvanceResult.IGNORED;
            } else {
                advanced = this.tryAdvanceEndClosure(this.nearestClosure, line).advanced();
            }
            if (advanced) {
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

    // only valid for leaf closure type
    public ClosureTypeAdvanceResult tryAdvanceEndClosure(@NotNull ClosureType type, @NotNull StringReader line) {
        Preconditions.checkArgument(ClosureType.LEAFS.contains(type), "Only leaf closure can be advanced using its type only, for other, use the closure equivalent method to take in account nested closure");
        Preconditions.checkState(this.nearestClosure != null && this.nearestClosure.getType() == type, "Need an direct upper closure of " + type);

        char previousChar = '\0';
        if (line.getCursor() >= 1) {
            previousChar = line.peek(-1);
        }

        if (line.trySkipString(type.end)) { // closure has been consumed
            // skip escape closed closure
            if (type.end.length() == 1 && type.start.equals(type.end) && ClosureType.ALLOW_ESCAPE.contains(type)) {
                if (previousChar == '\\') {
                    return ClosureTypeAdvanceResult.SKIPPED;
                }
            }

            this.nearestClosure.onEnd(line);
            if (this.nearestClosure.parent() != null) {
                this.nearestClosure = this.nearestClosure.parent();
            } else {
                this.nearestClosure = null;
            }
            return ClosureTypeAdvanceResult.CHANGED;
        }
        return ClosureTypeAdvanceResult.IGNORED;
    }

    public boolean skipComment(@NotNull StringReader line) {
        int previousCursor = line.getCursor();
        if ((this.nearestClosure != null && this.nearestClosure.getType() == ClosureType.COMMENT) ||
            this.tryAdvanceStartClosure(ClosureType.COMMENT, line)) { // open comment?
            ClosureTypeAdvanceResult result;
            while ((result = this.tryAdvanceEndClosure(ClosureType.COMMENT, line)) != ClosureTypeAdvanceResult.CHANGED && line.canRead()) { // closed comment?
                if (result == ClosureTypeAdvanceResult.IGNORED) {
                    line.skip();
                }
            }
            return line.getCursor() > previousCursor;
        }
        return false;
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
