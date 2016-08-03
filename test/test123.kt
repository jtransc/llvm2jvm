import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import com.jtransc.text.captureStdout
import com.jtransc.vfs.CwdVfs
import org.junit.Assert
import org.junit.Test
import java.io.File

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
		Assert.assertEquals(Result(9), runCppProgram("""
			int sum(int a, int b, int c) { return a + b + c; }
			int main() { return 3 + sum(1, 2, 3); }
		""", optimizations = false))
	}

	@Test fun test3() {
		Assert.assertEquals(Result(9), runCppProgram("""
			int mul(int a, int b, int c) { return a * b * c; }
			int main() { return 3 + mul(1, 2, 3); }
		""", optimizations = false))
	}

	@Test fun test4() {
		Assert.assertEquals(Result(15), runCppProgram("""
			int mul(int a, int b, int c, int d, int e) { return a + b * c - d / e; }
			int main() { return 3 + mul(1, 2, 3, -10, 2); }
		""", optimizations = false))
	}

	@Test fun test5() {
		Assert.assertEquals(Result(0, "HELLO"), runCppProgram("""int main() { puts("HELLO"); return 0; }""", optimizations = false, debug = false))
	}

	@Test fun test6() {
		Assert.assertEquals(Result(0, "HELLO!"), runCppProgram("""
			char* data = "HELLO!";
			int main() { puts(data); return 0; }
		""", optimizations = false, debug = false))
	}

	@Test fun test7() {
		Assert.assertEquals(Result(0, "HELLO WORLD!"), runCppProgram("""
			char* data = "HELLO";
			char* data2 = " WORLD!";
			int main() { puts(data); puts(data2); return 0; }
		""", optimizations = false, debug = false))
	}

	@Test fun test8() {
		Assert.assertEquals(Result(0, "LO"), runCppProgram("""
			char* data = "HELLO";
			int main() { puts(data + 3); return 0; }
		""", optimizations = false, debug = false))
	}

	@Test fun test9() {
		Assert.assertEquals(Result(777), runCppProgram("""
			int data[] = {333, 777, 999};
			int main() { return data[1]; }
		""", optimizations = false, debug = false))
	}

	@Test fun test10() {
		Assert.assertEquals(Result(0, "999,777,333"), runCppProgram("""
			int data[] = {333, 777, 999};
			int main() { printf("%d,%d,%d", data[2], data[1], data[0]); return 0; }
		""", optimizations = false, debug = false))
	}

	@Test fun test11() {
		Assert.assertEquals(Result(0, "999,-1,333"), runCppProgram("""
			int data[] = {333, 777, 999};
			int main() { data[1] = -1; printf("%d,%d,%d", data[2], data[1], data[0]); return 0; }
		""", optimizations = false, debug = true))
	}

	private fun runCppProgram(cppCode: String, optimizations: Boolean, debug: Boolean = false): Any? {
		val llCode = compileCppProgram(cppCode, optimizations)
		if (debug) {
			println("IL:");
			println(llCode)
		}
		val tokens = StrReader(llCode).tokenize()
		//println(tokens)
		//val tokens = GenericTokenize(StringReader(ll))
		val program = TokenReader(tokens).parse("Hello")
		val clazzBa = ClassGen.generate(program)

		if (debug) {
			File("Hello.class").writeBytes(clazzBa)
		}

		val clazz = getClassFromByteArray(program.className, clazzBa)
		var retval: Any? = 0
		val stdout = captureStdout {
			retval = clazz.getMethod("main").invoke(null)
		}
		return Result(retval, stdout)
	}

	data class Result(val result: Any?, val stdout: String = "") {
		override fun toString(): String = if (stdout == "") "Result($result)" else "Result($result, $stdout)"
	}

	private fun compileCppProgram(code: String, optimizations: Boolean): String {
		val cwd = CwdVfs()
		cwd["temp.c"] = code
		val result = cwd.exec("clang", if (optimizations) "-O3" else "-O0", "-S", "-emit-llvm", "temp.c")
		if (!result.success) {
			throw RuntimeException("CLANG error: " + result.errorString)
		}
		return cwd["temp.ll"].readString()
	}
}
