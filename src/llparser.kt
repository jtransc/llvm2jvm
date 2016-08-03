import com.jtransc.error.invalidOp
import com.jtransc.error.noImpl
import com.jtransc.numeric.toInt
import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import com.jtransc.text.readUntil
import com.jtransc.text.readWhile
import java.io.ByteArrayOutputStream
import java.util.*

class Program(val className: String, val decls: List<Decl>)

fun TokenReader<String>.parse(className: String) = Program(className, parseToplevelList())

fun TokenReader<String>.readArgument(): Argument {
	return Argument(readType(), readReference() as LOCAL)
}

fun TokenReader<String>.readBasicType(): Type.Basic {
	val type = this.read()
	if (type.startsWith("i")) {
		return Type.INT(type.substring(1).toInt())
	} else if (type == "...") {
		return Type.VARARG
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
	if (this.tryRead("(")) {
		val args = arrayListOf<Type>()
		while (true) {
			args += this.readType()
			if (this.tryRead(",")) continue
			if (this.tryRead(")")) break
			//invalidOp("Not implemented functional types")
		}
		out = Type.FUNCTION(out, args)
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
	} else if (tryRead("bitcast")) {
		expect("(")
		val fromType = readType()
		val ref = readReference()
		expect("to")
		val toType = readType()
		expect(")")
		return BITCAST(ref, fromType, toType)
	} else {
		invalidOp("readReference: '" + this.peek() + "'")
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
			"getelementptr" -> {
				val inbounds = tryRead("inbounds")
				expect("(")
				val type = readType()
				expect(",")
				val base = readTypedValue()
				expect(",")
				val idx1 = readTypedValue()
				expect(",")
				val idx2 = readTypedValue()
				expect(")")
				GETELEMENTPTR(inbounds, type, base, idx1, idx2)
			}
			"-" -> INT(-this.read().toInt())
			"[" -> {
				val args = arrayListOf<TypedValue>()
				while (true) {
					if (tryRead("]")) break
					args += readTypedValue()
					if (tryRead("]")) break
					if (tryRead(",")) continue
				}
				GENERICARRAY(args)
			}
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

fun TokenReader<String>.readAttributes() {
	// Attributes
	while (peek() == "#") {
		if (tryRead("#")) {
			val number = read()
		} else {
			invalidOp("invalid ${peek()}")
		}
	}

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
		"declare" -> {
			expect("declare")
			val type = readType()
			val name = readReference()
			val args = arrayListOf<Type>()
			expect("(")
			while (peek() != ")") {
				args += readType()
				if (tryRead(",")) continue
				if (peek() == ")") break
			}
			expect(")")
			readAttributes()
			return Decl.DECLARE(type, name, args)
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
			readAttributes()
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
			while (peek() != "}") readAttribute()
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
			//println(id)
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
			return Decl.DECVAR(id, type, value)
		}
		"\$" -> { // Comdats
			expect(setOf("\$"))
			val id = read()
			expect("=")
			expect("comdat")
			val selectionKind = read()
			when (selectionKind) {
				"any" -> Unit
				"exactmatch" -> Unit
				"largest" -> Unit
				"noduplicates" -> Unit
				"samesize" -> Unit
			}
			return Decl.COMDAT(id, selectionKind)
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
	object VARARG : Basic
	data class INT(val width: Int) : Basic
	data class PTR(val type: Type) : Type
	data class ARRAY(val type: Type, val count: Int) : Type
	data class FUNCTION(val rettype: Type, val args: ArrayList<Type>) : Type
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
				1 -> "Z"
				8 -> "B"
				16 -> "S"
				32 -> "I"
				64 -> "J"
				else -> noImpl("Int width: $width")
			}
		}
	//is Type.ARRAY -> "[" + this.type.toJavaType()
		is Type.ARRAY -> "I" // Pointer
		is Type.PTR -> "I" // Pointer
		Type.VARARG -> "[I"
		//Type.VARARG -> "[Ljava/lang/Object;"
		//Type.VARARG -> "I"
		else -> noImpl("type: $this")
	}
}

fun Type.getSizeInBits(): Int {
	return when (this) {
		is Type.INT -> this.width
		is Type.ARRAY -> this.count * this.type.getSizeInBytes()
		is Type.PTR -> 32
		Type.VARARG -> 32
		else -> noImpl("type: $this")
	}
}

fun Type.getSizeInBytes(): Int = getSizeInBits() / 8

interface Value
interface Reference : Value {
	val id: String
}

data class GETELEMENTPTR(val inbounds: Boolean, val type1: Type, val value: TypedValue, val idx1: TypedValue, val idx2: TypedValue) : Value

data class GENERICARRAY(val values: List<TypedValue>) : Value
data class INT(val value: Int) : Value
data class I8ARRAY(val value: String) : Value {
	val bytes by lazy {
		val out = ByteArrayOutputStream()
		val r = StrReader(value)
		while (!r.eof) {
			val ch = r.readch()
			when (ch) {
				'\\' -> out.write(r.read(2).toInt(16))
				else -> out.write(ch.toInt())
			}
		}
		out.toByteArray()
	}
}

data class LOCAL(override val id: String) : Reference
data class GLOBAL(override val id: String) : Reference
data class BITCAST(val ref: Reference, val fromType: Type, val toType: Type) : Reference {
	override val id: String get() = ref.id
}

class Argument(val type: Type, val name: LOCAL)

class Body(val stms: List<Stm>)

interface Decl {
	open class DECLARE_BASE(val type: Type, val name: Reference, val argTypes: List<Type>) : Decl

	class DECLARE(type: Type, name: Reference, args: List<Type>) : DECLARE_BASE(type, name, args)
	class DECFUN(type: Type, name: Reference, val args: List<Argument>, val body: Body) : DECLARE_BASE(type, name, args.map { it.type })
	class DECVAR(val id: String, val type: Type, val value: Value) : Decl
	class COMDAT(val id: String, val selectionKind: String) : Decl
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
	class CALL(val target: LOCAL, val rettype: Type, val name: Reference, val args: List<TypedValue>) : Stm
	class LABEL(val name: String) : Stm
	class GETELEMETPTR(val target: LOCAL, val inbounds: Boolean, val type1: Type, val ptr: TypedValue, val offset: TypedValue) : Stm
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
				"getelementptr" -> {
					val inbounds = tryRead("inbounds")
					val type1 = readType()
					expect(",")
					val ptr = readTypedValue()
					expect(",")
					val offset = readTypedValue()
					return Stm.GETELEMETPTR(target, inbounds, type1, ptr, offset)
				}
				else -> invalidOp("readDefinition: $op")
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
		else -> {
			unread()
			val label = tryReadLabel()
			if (label != null) {
				return Stm.LABEL(label)
			}
			invalidOp(kind)
		}
	}
}

fun TokenReader<String>.tryReadLabel(): String? {
	val name = read()
	if (read() != ":") {
		unread()
		unread()
		return null
	}
	return name
}

fun StrReader.tokenize(): List<String> {
	val out = arrayListOf<String>()
	while (this.hasMore) {
		val result = readToken() ?: break
		out += result
	}
	return out
}

val OPS: Set<String> = setOf(
	"%", ",", "=",
	"@", "$",
	"'", ":", "(", ")", "[", "]", "{", "}", "#",
	"*", "-", "+",
	"..."
)

fun StrReader.readToken(): String? {
	// skip spaces
	mainloop@while (hasMore) {
		readWhile { it.isWhitespace() }
		if (peek(3) in OPS) return read(3)
		if (peek(2) in OPS) return read(2)
		if (peek(1) in OPS) return read(1)
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