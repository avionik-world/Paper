package io.papermc.generator.rewriter.parser.step.model;

import io.papermc.generator.rewriter.parser.ClosureAdvanceResult;
import io.papermc.generator.rewriter.parser.ClosureType;
import io.papermc.generator.rewriter.parser.LineParser;
import io.papermc.generator.rewriter.parser.ParserException;
import io.papermc.generator.rewriter.parser.ProtoTypeName;
import io.papermc.generator.rewriter.parser.step.IterativeStep;
import io.papermc.generator.rewriter.parser.step.StepHolder;
import io.papermc.generator.rewriter.parser.StringReader;
import io.papermc.generator.utils.NamingManager;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

// start once "@" is detected unless commented
// order is: skipAtSign -> skipPartName -> checkOpenParenthesis (-> skipParentheses)
public final class AnnotationSteps implements StepHolder {

    public static boolean canStart(char currentChar) {
        return currentChar == '@';
    }

    private final IterativeStep skipParenthesesStep = IterativeStep.createUntil(this::skipParentheses);

    private @MonotonicNonNull ProtoTypeName name;

    public void skipAtSign(StringReader line, LineParser parser) {
        line.skip(); // skip @
    }

    public boolean skipPartName(StringReader line, LineParser parser) {
        boolean checkStartId = this.name == null || this.name.shouldCheckStartIdentifier();

        if (!checkStartId) { // this part is not in the import steps since import always need a semicolon at their end so it's easier to parse them
            if (!parser.trySkipCommentOrWhitespaceUntil(line, ProtoTypeName.IDENTIFIER_SEPARATOR)) { // expect a dot for multi line annotation when the previous line doesn't end by a dot itself
                return false;
            }
        } else {
            parser.skipCommentOrWhitespace(line);
        }
        if (!line.canRead()) {
            return true;
        }

        this.name = line.getPartNameUntil('(', parser::skipCommentOrWhitespace, this.name);

        if (line.canRead() && parser.peekSingleLineComment(line)) {
            // ignore single line comment at the end and allow the name to continue
            line.setCursor(line.getTotalLength());
        }
        return !line.canRead();
    }

    public boolean skipParentheses(StringReader line, LineParser parser) {
        ClosureAdvanceResult result;
        while ((result = parser.advanceEnclosure0(ClosureType.PARENTHESIS, line)) != ClosureAdvanceResult.CHANGED) { // closed parenthesis?
            if (!line.canRead()) { // parenthesis on another line?
                return true;
            }
            if (result == ClosureAdvanceResult.IGNORED) {
                line.skip();
            }
        }
        return false;
    }

    public void checkAnnotationName(StringReader line, LineParser parser) {
        String name = this.name.getFinalName();

        if (name.isEmpty()) {
            throw new ParserException("Invalid java source, annotation name is empty", line);
        }

        if (name.equals("interface")) {
            // skip this one: annotation definition (@interface)
            // note: this can't be skipped before (i.e. in canStart) since space/comments are allowed between '@' and 'interface'
            parser.getSteps().clearRemaining();
            return;
        }

        if (!NamingManager.isValidName(name)) { // keyword are checked after to simplify things
            throw new ParserException("Invalid java source, annotation name contains a reserved keyword or a syntax error", line);
        }
    }

    public boolean checkOpenParenthesis(StringReader line, LineParser parser) {
        parser.skipCommentOrWhitespace(line); // since skipPartName fail fast this is needed for space between the typeName and the parenthesis
        if (!line.canRead()) {
            return true;
        }

        if (parser.advanceEnclosure(ClosureType.PARENTHESIS, line)) { // open parenthesis?
            parser.getSteps().addPriority(this.skipParenthesesStep);
        }
        return false;
    }

    @Override
    public IterativeStep[] initialSteps() {
        return new IterativeStep[] {
            IterativeStep.create(this::skipAtSign),
            IterativeStep.createUntil(this::skipPartName),
            IterativeStep.create(this::checkAnnotationName),
            IterativeStep.createUntil(this::checkOpenParenthesis),
        };
    }
}
