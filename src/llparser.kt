import com.jtransc.error.invalidOp
import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import com.jtransc.text.readUntil
import com.jtransc.text.readWhile

fun TokenReader<String>.parse() {
	parseToplevel()
}

fun TokenReader<String>.readType(): Type {
	val type = this.read()
	var out: Type = Type.Basic(type)
	while (this.tryRead("*")) {
		out = Type.Pointer(out)
	}
	return out
}

fun TokenReader<String>.readId(): String {
	return this.read()
}

fun TokenReader<String>.readRef(): Reference {
	expect("%")
	return Reference.LOCAL(this.read())
}

fun TokenReader<String>.parseToplevel() {
	while (this.hasMore) {
		val key = peek()
		when (key) {
			"target" -> {
				expect("target")
				val type = read()
				expect("=")
				val string = read()
				println("target $type = $string")
			}
			"define" -> {
				expect("define")
				val type = readType()
				expect("@")
				val name = readId()
				expect("(")
				while (peek() != ")") {
					val type = readType()
					expect("%")
					val name = readId()
					if (tryRead(",")) continue
					if (peek() == ")") break
				}
				expect(")")

				while (peek() != "{") {
					if (tryRead("#")) {
						val number = read()
					} else {
						invalidOp("invalid ${peek()}")
					}
				}

				expect("{")

				readDefinitions()

				expect("}")
			}
			else -> invalidOp("$key")
		}
	}
}

fun TokenReader<String>.tryReadExtra() {
	if (tryRead(",")) {
		expect("align")
		readId()
	}
}

fun TokenReader<String>.readDefinitions() {
	while (peek() != "}") {
		readDefinition()
	}
}

interface Type {
	class Basic(val str: String) : Type
	class Pointer(val type: Type) : Type
}

interface Reference {
	class LOCAL(id:String) : Reference
}

interface Stm {
	class ALLOCA(val target: Reference, val type: Type) : Stm
	class LOAD(val target: Reference, val type1: Type, val type2: Type, val id: Reference) : Stm
	class ADD(val target: Reference, val type: Type, val src: Reference, val dst: Reference) : Stm
	class STORE(val srcType: Type, val srcName: Reference, val dstType: Type, val dstName: Reference) : Stm
	class RET(val type: Type, val reg: Reference) : Stm
}

fun TokenReader<String>.readDefinition(): Stm {
	val kind = read()
	when (kind) {
		"%" -> {
			unread()
			val target = readRef()
			expect("=")
			val op = read()
			when (op) {
				"alloca" -> {
					val type = readType()
					tryReadExtra()
					return Stm.ALLOCA(target, type)
				}
				"load" -> {
					val type1 = readType()
					expect(",")
					val type2 = readType()
					val id = readRef()
					tryReadExtra()
					return Stm.LOAD(target, type1, type2, id)
				}
				"add" -> {
					tryRead("nsw")
					val type = readType()
					val src = readRef()
					expect(",")
					val dst = readRef()
					tryReadExtra()
					return Stm.ADD(target, type, src, dst)
				}
				else -> invalidOp(op)
			}
		}
		"store" -> {
			val srcType = readType()
			val srcName = readRef()
			expect(",")
			val dstType = readType()
			val dstName = readRef()
			tryReadExtra()
			return Stm.STORE(srcType, srcName, dstType, dstName)
		}
		"ret" -> {
			val type = readType()
			val reg = readRef()
			return Stm.RET(type, reg)
		}
		else -> invalidOp(kind)
	}
}

fun StrReader.tokenize(): List<String> {
	val out = arrayListOf<String>()
	while (this.hasMore) {
		val result = readToken()
		out += result
	}
	return out
}

val ops = setOf(
	"%", ",", "=", "@", "'", ".", ":", "(", ")", "[", "]", "{", "}", "#",
	"*", "-", "+"
)

fun StrReader.readToken(): String {
	// skip spaces
	mainloop@while (true) {
		readWhile { it.isWhitespace() }
		if (peek(3) in ops) return read(3)
		if (peek(2) in ops) return read(2)
		if (peek(1) in ops) return read(1)
		val ch = peekch()
		return when (ch) {
			';' -> {
				this.readUntil('\n')
				continue@mainloop
			}
			'"' -> {
				this.offset++
				val out = this.readUntil { it == '"' }!!
				this.offset++
				return out
			}
			in 'a'..'z', in 'A'..'Z', in '0'..'9', '_' -> {
				return readWhile { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }!!
			}
			else -> invalidOp("Unknown character '$ch'")
		}
	}
}