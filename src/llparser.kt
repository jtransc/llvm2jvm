import com.jtransc.error.invalidOp
import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import com.jtransc.text.readUntil
import com.jtransc.text.readWhile

class Program(val decls: List<Decl>)

fun TokenReader<String>.parse() = Program(parseToplevelList())

fun TokenReader<String>.readArgument(): Argument {
	return Argument(readType(), readReference())
}

fun TokenReader<String>.readBasicType(): Type.Basic {
	val type = this.read()
	if (type.startsWith("i")) {
		return Type.INT(type.substring(1).toInt())
	} else {
		invalidOp("Invalid BasicType: $type")
	}
}

fun TokenReader<String>.readType(): Type {
	var out: Type = readBasicType()
	while (this.tryRead("*")) {
		out = Type.PTR(out)
	}
	return out
}

fun TokenReader<String>.readId(): String {
	return this.read()
}

fun TokenReader<String>.readReference(): Reference {
	if (tryRead("%")) {
		return LOCAL(this.read())
	} else if (tryRead("@")) {
		return GLOBAL(this.read())
	} else {
		invalidOp(":" + this.peek())
	}
}

fun TokenReader<String>.readValue(): Value {
	if (peek() in setOf("%", "@")) {
		return readReference()
	} else {
		return INT(this.read())
	}
}

fun TokenReader<String>.readTypedValue(): TypedValue {
	return TypedValue(readType(), readValue())
}

fun TokenReader<String>.parseToplevelList(): List<Decl> {
	val out = arrayListOf<Decl>()
	while (this.hasMore) out += parseToplevel();
	return out
}

fun TokenReader<String>.parseToplevel(): Decl {
	val key = peek()
	when (key) {
		"target" -> {
			expect("target")
			val type = read()
			expect("=")
			val string = read()
			//println("target $type = $string")
			return Decl.EMPTY
		}
		"define" -> {
			expect("define")
			val type = readType()
			val name = readReference()
			val args = arrayListOf<Argument>()
			expect("(")
			while (peek() != ")") {
				args += readArgument()
				if (tryRead(",")) continue
				if (peek() == ")") break
			}
			expect(")")

			// Attributes
			while (peek() != "{") {
				if (tryRead("#")) {
					val number = read()
				} else {
					invalidOp("invalid ${peek()}")
				}
			}

			expect("{")

			val body = readDefinitions()

			expect("}")

			return Decl.DEFINE(type, name, args, body)
		}
		"attributes" -> {
			expect("attributes")
			expect("#")
			val id = read()
			expect("=")
			expect("{")
			while (peek() != "}") {
				readAttribute()
			}
			expect("}")
			return Decl.EMPTY
		}
		else -> invalidOp("$key")
	}

}

fun TokenReader<String>.readAttribute() {
	// @TODO: proper reading!
	read()
}

fun TokenReader<String>.tryReadExtra() {
	if (tryRead(",")) {
		expect("align")
		readId()
	}
}

fun TokenReader<String>.readDefinitions(): Body {
	val stms = arrayListOf<Stm>()
	while (peek() != "}") stms += readDefinition()
	return Body(stms)
}

interface Type {
	interface Basic : Type
	class INT(val width: Int) : Basic
	class PTR(val type: Type) : Type
}

interface Value
interface Reference : Value

class INT(val value:String) : Value
class LOCAL(val id:String) : Reference
class GLOBAL(val id:String) : Reference

class Argument(val type: Type, val name: Reference)

class Body(val stms: List<Stm>)

interface Decl {
	class DEFINE(val type: Type, val name: Reference, val args: List<Argument>, val body: Body) : Decl

	object EMPTY : Decl
}

class TypedValue(val type: Type, val value: Value)

interface Stm {
	class ALLOCA(val target: Reference, val type: Type) : Stm
	class LOAD(val target: Reference, val targetType: Type, val from: TypedValue) : Stm
	class ADD(val target: Reference, val type: Type, val left: Value, val right: Value) : Stm

	class STORE(val src: TypedValue, val dst: TypedValue) : Stm
	class RET(val type: Type, val ref: Value) : Stm
	class CALL(val target:Reference, val rettype: Type, val name: Reference, val args: List<TypedValue>) : Stm
}

fun TokenReader<String>.readDefinition(): Stm {
	val kind = read()
	when (kind) {
		"%" -> {
			unread()
			val target = readReference()
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
					val from = readTypedValue()
					tryReadExtra()
					return Stm.LOAD(target, type1, from)
				}
				"add" -> {
					tryRead("nsw")
					val type = readType()
					val src = readValue()
					expect(",")
					val dst = readValue()
					tryReadExtra()
					return Stm.ADD(target, type, src, dst)
				}
				"call" -> {
					val rettype = readType()
					val name = readReference()
					val args = arrayListOf<TypedValue>()
					expect("(")
					while (peek() != ")") {
						args += readTypedValue()
						if (tryRead(",")) continue
					}
					expect(")")
					tryReadExtra()
					return Stm.CALL(target, rettype, name, args)
				}
				else -> invalidOp(op)
			}
		}
		"store" -> {
			val src = readTypedValue()
			expect(",")
			val dst = readTypedValue()
			tryReadExtra()
			return Stm.STORE(src, dst)
		}
		"ret" -> {
			val type = readType()
			val reg = readValue()
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
			else -> {
				//invalidOp("Unknown character '$ch'")
				return "$ch"
			}
		}
	}
}