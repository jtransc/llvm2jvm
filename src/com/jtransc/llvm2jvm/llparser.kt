package com.jtransc.llvm2jvm

import com.jtransc.error.invalidOp
import com.jtransc.error.noImpl
import com.jtransc.numeric.toInt
import com.jtransc.text.StrReader
import com.jtransc.text.TokenReader
import com.jtransc.text.readUntil
import com.jtransc.text.readWhile
import java.io.ByteArrayOutputStream
import java.util.*

// http://llvm.org/docs/LangRef.html

class Program(val className: String, val decls: List<Decl>) {
	val internalClassName = className.replace('.', '/')
}

fun TokenReader<String>.parse(className: String) = Program(className, parseToplevelList())

fun TokenReader<String>.readArgument(): Argument {
	val type = readType()
	if (type == Type.VARARG) {
		return Argument(type, LOCAL("..."))
	} else {
		return Argument(type, readReference() as LOCAL)
	}
}

fun TokenReader<String>.readBasicType(): Type.Basic {
	val type = this.read()
	return when (type) {
		"..." -> Type.VARARG
		"void" -> Type.VOID
		else -> if (type.startsWith("i")) {
			Type.INT(type.substring(1).toInt())
		} else {
			invalidOp("Invalid BasicType: $type")
		}
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
		"{" -> {
			val elements = arrayListOf<Type>()
			expect("{")
			while (true) {
				if (tryRead("}")) break
				elements += readType()
				if (tryRead(",")) continue
				if (tryRead("}")) break
				invalidOp("Invalid")
			}
			Type.STRUCT(elements)
		}
		// struct reference!
		"%" -> {
			expect("%")
			Type.STRUCT_REF(read())
		}
		"opaque" -> {
			expect("opaque")
			Type.OPAQUE
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
	val v = this.read()
	if (v == "!") {
		return "!" + this.read()
	} else {
		return v
	}
}

fun TokenReader<String>.readInt(): Int {
	val vs = this.read()
	return vs.toInt()
}

fun TokenReader<String>.readLabel(): LABEL_NAME {
	expect("%")
	return LABEL_NAME(read())
}

fun TokenReader<String>.readReference(): Reference {
	return if (tryRead("%")) {
		LOCAL(this.read())
	} else if (tryRead("@")) {
		GLOBAL(this.read())
	} else if (tryRead("bitcast")) {
		expect("(")
		val fromType = readType()
		val ref = readReference()
		expect("to")
		val toType = readType()
		expect(")")
		BITCAST(ref, fromType, toType)
	} else {
		invalidOp("com.jtransc.llvm2jvm.readReference: '" + this.peek() + "'")
	}
}

fun TokenReader<String>.readValue(type: Type): Value {
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
				val indices = arrayListOf<TypedValue>()
				while (tryRead(",")) {
					indices += readTypedValue()
				}
				expect(")")
				GETELEMENTPTR(inbounds, type, base, indices)
			}
			"-" -> INT(-this.read().toInt())
			"[" -> {
				val args = arrayListOf<TypedValue>()
				while (true) {
					if (tryRead("]")) break
					args += readTypedValue()
					if (tryRead("]")) break
					if (tryRead(",")) continue
					invalidOp("Invalid")
				}
				GENERICARRAY(args)
			}
			// Struct literals
			"{" -> {
				val args = arrayListOf<TypedValue>()
				while (true) {
					if (tryRead("}")) break
					args += readTypedValue()
					if (tryRead(",")) continue
					if (tryRead("}")) break
					invalidOp("Invalid")
				}
				GENERICSTRUCT(args)
			}
			"bitcast" -> {
				expect("(")
				val fromValue = readType()
				val ref = readReference()
				expect("to")
				val toType = readType()
				expect(")")
				BITCAST(ref, fromValue, toType)
			}
			"true" -> BOOL(true)
			"false" -> BOOL(false)
			"null" -> NULL
			else -> {
				if (type == Type.INT64) {
					LONG(p.toLong())
				} else {
					INT(p.toInt())
				}
			}
		}
	}
}

fun TokenReader<String>.readTypedValue(): TypedValue {
	val type = readType()
	return TypedValue(type, readValue(type))
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
				tryRead("nocapture")
				tryRead("readonly")
				if (tryRead(",")) continue
				if (peek() == ")") break
				invalidOp("Expected , or )")
			}
			expect(")")
			readAttributes()
			return Decl.DECLARE(type, name, args)
		}
		"define" -> {
			expect("define")
			val linkage = tryReadLinkageType()
			val type = readType()
			val name = readReference()
			val args = arrayListOf<Argument>()
			expect("(")
			while (peek() != ")") {
				if (peek() == ")") break
				args += readArgument()
				if (tryRead(",")) continue
				if (peek() == ")") break
				invalidOp("expected , or )")
			}
			expect(")")
			readAttributes()
			tryRead("comdat")
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
	//	<global | constant> <com.jtransc.llvm2jvm.Type> [<InitializerConstant>]
	//	[, section "name"] [, comdat [($name)]]
	//	[, align <Alignment>]
		"%" -> {
			expect("%")
			val id = read()
			expect("=")
			expect("type")
			val type = readType()
			return Decl.DECTYPE(id, type)
		}
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
			val value = readValue(type)
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
		"!" -> {
			val id = readId()
			expect("=")
			expect("!")
			expect("{")
			while (true) {
				if (read() == "}") {
					unread()
					break
				}
			}
			expect("}")
			return Decl.METADATA(id)
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
	while (tryRead(",")) {
		val readed = expect(setOf("align", "!"))
		if (readed == "!") expect("tbaa")
		val id = readId()
		//println("$readed: $id")
		//println("-")
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
	data class FLOAT(val width: Int) : Basic
	data class PTR(val type: Type) : Type
	data class ARRAY(val type: Type, val count: Int) : Type
	data class STRUCT(val types: List<Type>) : Type
	data class STRUCT_REF(val name: String) : Type
	data class FUNCTION(val rettype: Type, val args: ArrayList<Type>) : Type
	companion object {
		val INT1 = INT(1)
		val INT8 = INT(8)
		val INT16 = INT(16)
		val INT32 = INT(32)
		val INT64 = INT(64)
		val FLOAT32 = FLOAT(32)
		val FLOAT64 = FLOAT(64)

		val PTR_INT = INT32
		val OPAQUE: Type = STRUCT(listOf())
	}
}

fun Type.isLongOrDouble() = (this == Type.INT64) || (this == Type.FLOAT64)

fun Type.toJavaType(): String {
	return when (this) {
		is Type.VOID -> "V"
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
	//is com.jtransc.llvm2jvm.Type.ARRAY -> "[" + this.type.com.jtransc.llvm2jvm.toJavaType()
		is Type.ARRAY -> "I" // Pointer
		is Type.PTR -> "I" // Pointer
		is Type.STRUCT -> "I" // Pointer
		is Type.STRUCT_REF -> "I" // Pointer
		Type.VARARG -> "[I"
	//com.jtransc.llvm2jvm.Type.VARARG -> "[Ljava/lang/Object;"
	//com.jtransc.llvm2jvm.Type.VARARG -> "I"
		else -> noImpl("type: $this")
	}
}

fun Type.getSizeInBits(): Int {
	return when (this) {
		is Type.INT -> this.width
		is Type.ARRAY -> this.count * this.type.getSizeInBytes()
		is Type.PTR -> 32
		Type.VARARG -> 32
		is Type.STRUCT -> this.types.sumBy { it.getSizeInBits() }
		is Type.STRUCT_REF -> invalidOp("Cannot get size of a struct_ref")
		else -> noImpl("type: $this")
	}
}

fun Type.getOffsetInBits(offset: Int): Int {
	return when (this) {
		is Type.STRUCT -> this.types.take(offset).sumBy { it.getSizeInBits() }
		else -> noImpl("type: $this")
	}
}

fun Type.getSizeInBytes(): Int = getSizeInBits() / 8
fun Type.getOffsetInBytes(offset: Int): Int = getOffsetInBits(offset) / 8

interface Value
interface Reference : Value {
	val id: String
}

data class GETELEMENTPTR(val inbounds: Boolean, val type1: Type, val value: TypedValue, val offsets: List<TypedValue>) : Value
data class GENERICSTRUCT(val values: List<TypedValue>) : Value
data class GENERICARRAY(val values: List<TypedValue>) : Value
data class BOOL(val value: Boolean) : Value
object NULL : Value
data class INT(val value: Int) : Value
data class LONG(val value: Long) : Value
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

data class LABEL_NAME(val id: String)
data class LOCAL(override val id: String) : Reference
data class GLOBAL(override val id: String) : Reference

//data class BITCAST(val fromValue: TypedValue, val toType: Type) : Reference
data class BITCAST(val ref: Reference, val fromType: Type, val toType: Type) : Reference {
	override val id: String get() = ref.id
}

class Argument(val type: Type, val local: LOCAL)

class Body(val stms: List<Stm>)

interface Decl {
	open class DECLARE_BASE(val type: Type, val name: Reference, val argTypes: List<Type>) : Decl

	class DECLARE(type: Type, name: Reference, args: List<Type>) : DECLARE_BASE(type, name, args)
	class DECFUN(type: Type, name: Reference, val args: List<Argument>, val body: Body) : DECLARE_BASE(type, name, args.map { it.type })
	class DECVAR(val id: String, val type: Type, val value: Value) : Decl
	class COMDAT(val id: String, val selectionKind: String) : Decl
	class METADATA(val id: String) : Decl
	object EMPTY : Decl

	class DECTYPE(val id: String, val type: Type) : Decl
}

class TypedValue(val type: Type, val value: Value)

interface Stm {
	class ALLOCA(val target: LOCAL, val type: Type) : Stm
	class LOAD(val target: LOCAL, val targetType: Type, val from: TypedValue) : Stm
	class BINOP(val target: LOCAL, val op: String, val type: Type, val left: Value, val right: Value) : Stm {
		val typedLeft = TypedValue(type, left)
		val typedRight = TypedValue(type, right)

		//eq: equal
		//ne: not equal
		//ugt: unsigned greater than
		//uge: unsigned greater or equal
		//ult: unsigned less than
		//ule: unsigned less or equal
		//sgt: signed greater than
		//sge: signed greater or equal
		//slt: signed less than
		//sle: signed less or equal
	}

	class ASSIGN(val target: LOCAL, val src: TypedValue) : Stm
	class STORE(val src: TypedValue, val dst: TypedValue) : Stm
	class RET(val typedValue: TypedValue) : Stm
	class CALL(val target: LOCAL?, val rettype: Type, val name: Reference, val args: List<TypedValue>) : Stm
	class LABEL(val name: String) : Stm
	//class GETELEMETPTR(val target: LOCAL, val inbounds: Boolean, val type1: Type, val ptr: TypedValue, val offsets: List<TypedValue>) : Stm
	class JUMP_IF(val cond: TypedValue, val branchTrue: Reference, val branchFalse: Reference) : Stm
	class JUMP(val branch: Reference) : Stm
	class PHI(val target: LOCAL, val type: Type, val labelsToValues: Map<String, TypedValue>) : Stm
	class TERNARY(val target: LOCAL, val cond: TypedValue, val vtrue: TypedValue, val vfalse: TypedValue) : Stm
	class SEXT(val target: LOCAL, val from: TypedValue, val toType: Type) : Stm
	class BITCAST(val target: LOCAL, val from: TypedValue, val toType: Type) : Stm
}

fun TokenReader<String>.readCall(target: LOCAL?): Stm {
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

fun TokenReader<String>.readDefinition(): Stm {
	val kind = read()
	return when (kind) {
		"%" -> {
			unread()
			val target = readReference() as LOCAL
			expect("=")
			val op = read()
			when (op) {
				"alloca" -> {
					val type = readType()
					tryReadExtra()
					Stm.ALLOCA(target, type)
				}
				"load" -> {
					val type1 = readType()
					expect(",")
					val from = readTypedValue()
					tryReadExtra()
					Stm.LOAD(target, type1, from)
				}
				"add", "sub", "mul", "sdiv", "xor", "or" -> {
					tryRead("nsw")
					val type = readType()
					val src = readValue(type)
					expect(",")
					val dst = readValue(type)
					tryReadExtra()
					Stm.BINOP(target, op, type, src, dst)
				}
				"icmp" -> {
					val compOp = read()
					val type = readType()
					val src = readValue(type)
					expect(",")
					val dst = readValue(type)
					Stm.BINOP(target, compOp, type, src, dst)

				}
				"phi" -> {
					val type = readType()
					val args = hashMapOf<String, TypedValue>()
					while (true) {
						expect("[")
						val value = TypedValue(type, readValue(type))
						expect(",")
						val label = readLabel().id
						expect("]")
						args[label] = value
						if (tryRead(",")) continue else break
					}
					Stm.PHI(target, type, args)
				}
				"tail", "call" -> {
					if (op == "tail") expect("call")
					readCall(target)
				}
				"getelementptr" -> {
					val inbounds = tryRead("inbounds")
					val type1 = readType()
					expect(",")
					val ptr = readTypedValue()
					val offsets = arrayListOf<TypedValue>()
					while (tryRead(",")) {
						offsets += readTypedValue()
					}
					//Stm.GETELEMETPTR(target, inbounds, type1, ptr, offsets)
					//Stm.ASSIGN(target, TypedValue(type1, GETELEMENTPTR(inbounds, type1, ptr, offsets)))
					Stm.ASSIGN(target, TypedValue(Type.PTR_INT, GETELEMENTPTR(inbounds, type1, ptr, offsets)))
				}
				"select" -> { // ternary operator
					val cond = readTypedValue()
					expect(",")
					val vtrue = readTypedValue()
					expect(",")
					val vfalse = readTypedValue()
					Stm.TERNARY(target, cond, vtrue, vfalse)
				}
				"sext" -> {
					val from = readTypedValue()
					expect("to")
					val toType = readType()
					Stm.SEXT(target, from, toType)
				}
				"bitcast" -> {
					val from = readTypedValue()
					expect("to")
					val toType = readType()
					Stm.BITCAST(target, from, toType)
				}
				else -> invalidOp("com.jtransc.llvm2jvm.readDefinition: $op")
			}
		}
		"store" -> {
			val src = readTypedValue()
			expect(",")
			val dst = readTypedValue()
			tryReadExtra()
			Stm.STORE(src, dst)
		}
		"ret" -> Stm.RET(readTypedValue())
		"br" -> {
			//br i1 <cond>, label <iftrue>, label <iffalse>
			//br label <dest>          ; Unconditional branch
			val kind2 = read()
			when (kind2) {
				"i1" -> {
					unread()
					val cond = readTypedValue()
					expect(",")
					expect("label")
					val branchTrue = readReference()
					expect(",")
					expect("label")
					val branchFalse = readReference()
					Stm.JUMP_IF(cond, branchTrue, branchFalse)
				}
				"label" -> {
					val branch = readReference()
					Stm.JUMP(branch)
				}
				else -> invalidOp("Unsupported $kind2")
			}
		}
		"call" -> {
			readCall(null)
		}
		else -> {
			unread()
			val label = tryReadLabel()
			if (label != null) {
				Stm.LABEL(label)
			} else {
				invalidOp("com.jtransc.llvm2jvm.readDefinition().KIND($kind): " + this.list[position - 2] + " " + this.list[position - 1] + " " + kind)
			}
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
	"@", "$", "!", "%",
	",", "=",
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
			in 'a'..'z', in 'A'..'Z', in '0'..'9', '_', '.' -> {
				return readWhile { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' }!!
			}
			'\u0000' -> {
				//readch()
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