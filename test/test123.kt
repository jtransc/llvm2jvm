import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import org.junit.Assert
import org.junit.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader

class Test123 {
	@Test fun test1() {
		// clang -S -emit-llvm a.c
		val ll = """
			; ModuleID = 'a.c'
			target datalayout = "e-m:o-i64:64-f80:128-n8:16:32:64-S128"
			target triple = "x86_64-apple-macosx10.11.0"

			; Function Attrs: nounwind ssp uwtable
			define i32 @sum(i32 %a, i32 %b) #0 {
			  %1 = alloca i32, align 4
			  %2 = alloca i32, align 4
			  store i32 %a, i32* %1, align 4
			  store i32 %b, i32* %2, align 4
			  %3 = load i32, i32* %1, align 4
			  %4 = load i32, i32* %2, align 4
			  %5 = add nsw i32 %3, %4
			  ret i32 %5
			}

			; Function Attrs: nounwind ssp uwtable
			define i32 @main() #0 {
			  %1 = alloca i32, align 4
			  store i32 0, i32* %1, align 4
			  %2 = call i32 @sum(i32 7, i32 3)
			  ret i32 %2
			}"""
		val tokens = StrReader(ll).tokenize()
		//val tokens = GenericTokenize(StringReader(ll))
		val program = TokenReader(tokens).parse("Hello")

		//program.dump()

		val clazzBa = ClassGen.generate(program)
		File("${program.className}.class").writeBytes(clazzBa)
		val clazz = getClassFromByteArray(program.className, clazzBa)
		//println(clazz)

		Assert.assertEquals(10, clazz.getMethod("main").invoke(null))
	}
}
