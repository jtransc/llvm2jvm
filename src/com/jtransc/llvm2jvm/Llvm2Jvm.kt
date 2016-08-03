package com.jtransc.llvm2jvm

import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import java.io.File

object Llvm2Jvm {
	fun llToJvm(llCode: String): ByteArray {
		val tokens = StrReader(llCode).tokenize()
		//println(tokens)
		//val tokens = GenericTokenize(StringReader(ll))
		val program = TokenReader(tokens).parse("Hello")
		val clazzBa = ClassGen.generate(program)
		//val clazz = getClassFromByteArray(program.className, clazzBa)
		return clazzBa
	}
}