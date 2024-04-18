package io.papermc.generator.rewriter.data.sample.parser.area;

@AnnotationTrapClass.Trapped(strings = {"trap: )   \" )  {"}, chars = {')', '{', '\''}, paragraphs = """
    )
    \"\"\"
    {
    """)
public class AnnotationTrapClass { // << 33

    @interface Trapped {
        String[] strings();

        char[] chars();

        String[] paragraphs();
    }
}
