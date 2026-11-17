package com.ezfiles.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeFoldingTest {
    private fun folds(code: String, ext: String) = CodeFolding.regions(code.trimIndent().lines(), ext)

    @Test
    fun javaMethodsClassesAndImports() {
        val code = """
            import java.util.List;
            import java.util.Map;

            public class Game {
                int guess(int n) {
                    return n;
                }
            }
        """
        val f = folds(code, "java")
        assertEquals(CodeFold(0, 1, 1, FoldKind.Imports), f[0])
        assertTrue(CodeFold(3, 7, 7, FoldKind.Block) in f)
        assertTrue(CodeFold(4, 6, 6, FoldKind.Block) in f)
    }

    @Test
    fun elseLineStaysVisible() {
        val code = """
            if (x) {
                a();
            } else {
                b();
            }
        """
        val f = folds(code, "cpp")
        assertEquals(CodeFold(0, 2, 1, FoldKind.Block), f.first { it.start == 0 })
        assertEquals(" …", CodeFolding.placeholder(f.first { it.start == 0 }, code.trimIndent().lines()))
        assertEquals(CodeFold(2, 4, 4, FoldKind.Block), f.first { it.start == 2 })
    }

    @Test
    fun bracesInStringsCommentsAndCharsAreIgnored() {
        val code = """
            fn main() {
                let s = "{ not a block";
                let c = '{';
                // } not the end
                println!("{}", s);
            }
        """
        assertEquals(listOf(CodeFold(0, 5, 5, FoldKind.Block)), folds(code, "rs"))
    }

    @Test
    fun blockCommentsFold() {
        val code = """
            /**
             * Docs.
             */
            int x = 1;
        """
        assertEquals(listOf(CodeFold(0, 2, 2, FoldKind.Comment)), folds(code, "c"))
    }

    @Test
    fun pythonFoldsByIndentation() {
        val code = """
            import os
            from sys import argv

            def main():
                ""${'"'}Run it.
                Twice.""${'"'}
                for a in argv:
                    print(a)  # {

            class A:
                pass
        """
        val f = folds(code, "py")
        assertEquals(CodeFold(0, 1, 1, FoldKind.Imports), f[0])
        assertEquals(CodeFold(3, 7, 7, FoldKind.Block), f.first { it.start == 3 })
        assertEquals(CodeFold(4, 5, 5, FoldKind.Comment), f.first { it.start == 4 })
        assertEquals(CodeFold(6, 7, 7, FoldKind.Block), f.first { it.start == 6 })
        assertEquals(CodeFold(9, 10, 10, FoldKind.Block), f.first { it.start == 9 })
    }

    @Test
    fun placeholdersLookLikeTheIde() {
        val lines = "fun a() {\n    b()\n}".lines()
        assertEquals("…}", CodeFolding.placeholder(CodeFolding.regions(lines, "kt").single(), lines))
    }
}
