package com.jtransc.llvm2jvm

import com.jtransc.text.TokenReader
import com.jtransc.vfs.CwdVfs
import java.io.File

fun main(_args: Array<String>) {
	if (_args.isEmpty()) {
		println("llvm2jvm <file.ll>")
		System.exit(-1)
	}

	val args = TokenReader(_args.toList())
	val files = arrayListOf<File>()
	while (args.hasMore) {
		val arg = args.read()
		when (arg) {
			else -> {
				files += File(arg)
			}
		}
	}

	val cwd = CwdVfs()
	val result = cwd.exec("clang", "-O3", "-S", "-emit-llvm", *(files.map { it.absolutePath }.toTypedArray()), "-o", "temp.ll")
	println(result.success)
	println(result.errorString)
	File("temp.class").writeBytes(Llvm2Jvm.llToJvm(File("temp.ll").readText()))
}