import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import com.jtransc.vfs.CwdVfs
import com.jtransc.vfs.SyncVfs
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

	@Test fun test2() {
		Assert.assertEquals(9, runCppProgram("""
			int sum(int a, int b, int c) {
				return a + b + c;
			}

			int main() {
				return 3 + sum(1, 2, 3);
			}
		""", optimizations = false))
	}

	@Test fun test3() {
		Assert.assertEquals(9, runCppProgram("""
			int mul(int a, int b, int c) { return a * b * c; }
			int main() { return 3 + mul(1, 2, 3); }
		""", optimizations = false))
	}

	@Test fun test4() {
		Assert.assertEquals(15, runCppProgram("""
			int mul(int a, int b, int c, int d, int e) { return a + b * c - d / e; }
			int main() { return 3 + mul(1, 2, 3, -10, 2); }
		""", optimizations = false))
	}

	private fun runCppProgram(cppCode: String, optimizations: Boolean): Any? {
		val llCode = compileCppProgram(cppCode, optimizations)
		val tokens = StrReader(llCode).tokenize()
		//val tokens = GenericTokenize(StringReader(ll))
		val program = TokenReader(tokens).parse("Hello")
		val clazzBa = ClassGen.generate(program)
		val clazz = getClassFromByteArray(program.className, clazzBa)
		return clazz.getMethod("main").invoke(null)
	}

	private fun compileCppProgram(code: String, optimizations: Boolean): String {
		val cwd = CwdVfs()
		cwd["temp.c"] = code
		cwd.exec("clang", if (optimizations) "-O3" else "-O0", "-S", "-emit-llvm", "temp.c")
		return cwd["temp.ll"].readString()
	}
}
