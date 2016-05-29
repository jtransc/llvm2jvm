import com.jtransc.error.invalidOp
import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import com.jtransc.text.readUntil
import com.jtransc.text.readWhile
import org.junit.Test

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
			}

			attributes #0 = { nounwind ssp uwtable "disable-tail-calls"="false" "less-precise-fpmad"="false" "no-frame-pointer-elim"="true" "no-frame-pointer-elim-non-leaf" "no-inf$

			!llvm.module.flags = !{!0}
			!llvm.ident = !{!1}

			!0 = !{i32 1, !"PIC Level", i32 2}
			!1 = !{!"Apple LLVM version 7.3.0 (clang-703.0.31)"}
		"""
		val tokens = StrReader(ll).tokenize()
		//val tokens = GenericTokenize(StringReader(ll))
		TokenReader(tokens).parse()
	}
}