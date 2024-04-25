package io.papermc.generator.rewriter.parser.closure;

import io.papermc.generator.rewriter.parser.ParserException;
import io.papermc.generator.rewriter.parser.StringReader;
import org.jetbrains.annotations.Nullable;
import java.util.function.Supplier;

public interface Closure {

    ClosureType getType();

    @Nullable
    Closure parent();

    default boolean hasUpperClosure(Closure closure) {
        Closure parent = this;
        do {
            if (parent == closure) {
                return true;
            }
            parent = parent.parent();
        } while (parent != null);
        return false;
    }

    default void onStart(StringReader line) {

    }

    default void onEnd(StringReader line) {

    }

    static Closure create(ClosureType type) {
        class SpecialClosure {
            public static final Supplier<Closure> PARAGRAPH = () -> new AbstractClosure(ClosureType.PARAGRAPH) {

                @Override
                public void onStart(StringReader line) {
                    if (line.canRead()) {
                        throw new ParserException("Paragraph closure start must be followed by a newline", line);
                    }
                }
            };
        }

        if (type == ClosureType.PARAGRAPH) {
            return SpecialClosure.PARAGRAPH.get();
        }
        return new AbstractClosure(type);
    }
}
