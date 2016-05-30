import com.jtransc.error.invalidOp
import com.jtransc.error.noImpl
import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import com.jtransc.text.readUntil
import com.jtransc.text.readWhile

class Program(val className:String, val decls: List<Decl>)

fun TokenReader<String>.parse(className:String) = Program(className, parseToplevelList())

fun TokenReader<String>.readArgument(): Argument {
	return Argument(readType(), readReference() as LOCAL)
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
	var out: Type = when (peek()) {
		"[" -> {
			expect("[")
			val count = readInt()
			expect("x")
			val elementtype = readType()
			expect("]")
			Type.ARRAY(elementtype, count)
		}
		else -> readBasicType()
	}
	while (this.tryRead("*")) {
		out = Type.PTR(out)
	}
	return out
}

fun TokenReader<String>.readId(): String {
	return this.read()
}

fun TokenReader<String>.readInt(): Int {
	val vs = this.read()
	return vs.toInt()
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
	} else if (tryRead("c")) {
		// i8 array!
		return I8ARRAY(read())
	} else {
		val p = this.read()
		return when (p) {
			"getelementptr" -> noImpl("getelementptr")
			"-" -> INT(-this.read().toInt())
			else -> INT(p.toInt())
		}
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

			return Decl.DECFUN(type, name, args, body)
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
	// Global Variables
	//"%",

	// @<GlobalVarName> = [Linkage] [Visibility] [DLLStorageClass] [ThreadLocal]
	//	[unnamed_addr] [AddrSpace] [ExternallyInitialized]
	//	<global | constant> <Type> [<InitializerConstant>]
	//	[, section "name"] [, comdat [($name)]]
	//	[, align <Alignment>]
		"@" -> {
			expect(setOf("%", "@"))
			val id = read()
			println(id)
			expect("=")
			val linkage = tryReadLinkageType()
			val visibility = tryReadVisibilityStyle()
			val dllStorage = tryReadDllStorage()
			val threadLocal = tryReadThreadLocalStorageModel()
			val unnamedAddr = tryReadUnnamedAddr()
			val addrSpace = tryReadAddrSpace()
			val externallyInitialized = tryReadExternallyInitialized()
			val globalConstant = tryReadGlobalConstant()
			val type = readType()
			val value = readValue()
			while (tryRead(",")) {
				val attr = read()
				when (attr) {
					"section" -> {
						read()
					}
					"comdat" -> {
						read()
					}
					"align" -> {
						read()
					}
					else -> {
						noImpl("Unknown : $attr")
					}
				}
			}
			//[, section "name"] [, comdat [($name)]]
			//[, align <Alignment>]
			return Decl.DECVAR(id, value)
		}
		else -> invalidOp("$key")
	}
}

private fun TokenReader<String>.tryReadAddrSpace(): String? {
	return null
}

private fun TokenReader<String>.tryReadExternallyInitialized(): String? {
	return null
}

private fun TokenReader<String>.tryReadGlobalConstant(): String? {
	return tryReadGet("global", "constant")
}

private fun TokenReader<String>.tryReadUnnamedAddr(): String? {
	return tryReadGet("unnamed_addr")
}

private fun TokenReader<String>.tryReadThreadLocalStorageModel(): String? {
	return tryReadGet("localdynamic", "initialexec", "localexec")
}

private fun TokenReader<String>.tryReadLinkageType(): String? {
	return tryReadGet(
		"private", "internal", "available_externally",
		"linkonce", "weak", "common", "appending",
		"extern_weak", "linkonce_odr", "weak_odr",
		"external"
	)
}

private fun TokenReader<String>.tryReadVisibilityStyle(): String? {
	return tryReadGet(
		"default", "hidden", "protected"
	)
}

private fun TokenReader<String>.tryReadCallingConvention(): String? {
	val base = tryReadGet(
		"ccc", "fastcc", "coldcc", "cc", "webkit_jscc", "anyregcc", "preserve_mostcc",
		"preserve_allcc", "cxx_fast_tlscc", "swiftcc"

	)
	return when (base) {
		"cc" -> "cc" + read()
		else -> base
	}
}

private fun TokenReader<String>.tryReadDllStorage(): String? {
	return tryReadGet("dllimport", "dllexport")
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

fun TokenReader<String>.tryReadParameterAttributes() {
	loop@while (true) {
		when (peek()) {
			"zeroext", "signext", "inreg", "byval", "inalloca",
			"sret", "noalias", "nocapture", "nest",
			"returned", "nonnull"
			-> {
				read()
				continue@loop
			}
			"align", "dereferenceable", "dereferenceable_or_null", "swiftself", "swifterror" -> {
				read()
				read()
				continue@loop
			}
			else -> break@loop
		}
	}
}

fun TokenReader<String>.readDefinitions(): Body {
	val stms = arrayListOf<Stm>()
	while (peek() != "}") stms += readDefinition()
	return Body(stms)
}

interface Type {
	interface Basic : Type
	object VOID : Basic
	data class INT(val width: Int) : Basic
	data class PTR(val type: Type) : Type
	data class ARRAY(val type: Type, val count: Int) : Type
	companion object {
		val INT8 = INT(8)
		val INT16 = INT(16)
		val INT32 = INT(32)
		val INT64 = INT(64)
	}
}

fun Type.toJavaType(): String {
	return when (this) {
		is Type.INT -> {
			when (this.width) {
				32 -> "I"
				else -> noImpl("Int width: $width")
			}
		}
		else -> noImpl("type: $this")
	}
}

interface Value
interface Reference : Value {
	val id: String
}

data class INT(val value:Int) : Value
data class I8ARRAY(val value:String) : Value
data class LOCAL(override val id:String) : Reference
data class GLOBAL(override val id:String) : Reference

class Argument(val type: Type, val name: LOCAL)

class Body(val stms: List<Stm>)

interface Decl {
	class DECFUN(val type: Type, val name: Reference, val args: List<Argument>, val body: Body) : Decl
	class DECVAR(val id: String, val value: Value) : Decl
	object EMPTY : Decl
}

class TypedValue(val type: Type, val value: Value)

interface Stm {
	class ALLOCA(val target: LOCAL, val type: Type) : Stm
	class LOAD(val target: LOCAL, val targetType: Type, val from: TypedValue) : Stm
	class BINOP(val target: LOCAL, val op: String, val type: Type, val left: Value, val right: Value) : Stm {
		val typedLeft = TypedValue(type, left)
		val typedRight = TypedValue(type, right)
	}

	class STORE(val src: TypedValue, val dst: TypedValue) : Stm
	class RET(val typedValue: TypedValue) : Stm
	class CALL(val target:LOCAL, val rettype: Type, val name: Reference, val args: List<TypedValue>) : Stm
}

fun TokenReader<String>.readDefinition(): Stm {
	val kind = read()
	when (kind) {
		"%" -> {
			unread()
			val target = readReference() as LOCAL
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
				"add", "sub", "mul", "sdiv" -> {
					tryRead("nsw")
					val type = readType()
					val src = readValue()
					expect(",")
					val dst = readValue()
					tryReadExtra()
					return Stm.BINOP(target, op, type, src, dst)
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
		"ret" -> return Stm.RET(readTypedValue())
		else -> invalidOp(kind)
	}
}

fun StrReader.tokenize(): List<String> {
	val out = arrayListOf<String>()
	while (this.hasMore) {
		val result = readToken() ?: break
		out += result
	}
	return out
}

val ops = setOf(
	"%", ",", "=", "@", "'", ":", "(", ")", "[", "]", "{", "}", "#",
	"*", "-", "+"
)

fun StrReader.readToken(): String? {
	// skip spaces
	mainloop@while (hasMore) {
		readWhile { it.isWhitespace() }
		if (peek(3) in ops) return read(3)
		if (peek(2) in ops) return read(2)
		if (peek(1) in ops) return read(1)
		val ch = peekch()
		when (ch) {
			';', '!' -> {
				this.readUntil('\n')
				continue@mainloop
			}
			'"' -> {
				this.offset++
				val out = this.readUntil { it == '"' }!!
				this.offset++
				return out
			}
			in 'a'..'z', in 'A'..'Z', in '0'..'9', '_', '.' -> {
				return readWhile { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' }!!
			}
			'\u0000' -> {
				readch()
				continue@mainloop
			}
			else -> {
				invalidOp("Unknown character '$ch'")
				//return "$ch"
			}
		}
	}
	return null
}

fun <T> TokenReader<T>.tryReadGet(vararg expected: T): T? {
	val v = peek()
	return if (this.tryRead(*expected)) v else null
}